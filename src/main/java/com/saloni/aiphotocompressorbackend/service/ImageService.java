package com.saloni.aiphotocompressorbackend.service;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.saloni.aiphotocompressorbackend.entity.ImageEntity;
import com.saloni.aiphotocompressorbackend.repository.ImageRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorConvertOp;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

@Service
public class ImageService {

    @Autowired
    private ImageRepository imageRepository;

    @Value("${app.upload.dir}")
    private String uploadDir;

    public ImageEntity compressAndSaveImage(
            MultipartFile file,
            Long targetSizeKB,
            String compressionMode,
            String outputFormat,
            Long userId
    ) throws Exception {

        if (file == null || file.isEmpty()) {
            throw new RuntimeException("No file uploaded");
        }

        if (targetSizeKB == null || targetSizeKB <= 0) {
            throw new RuntimeException("Invalid target size");
        }

        String safeFormat = normalizeFormat(outputFormat);

        File uploadFolder = new File(uploadDir).getAbsoluteFile();

        if (!uploadFolder.exists()) {
            uploadFolder.mkdirs();
        }

        String originalExt = getExtension(file.getOriginalFilename());

        String originalName =
                UUID.randomUUID() + "_original." + originalExt;

        File originalFile =
                new File(uploadFolder, originalName);

        Files.copy(
                file.getInputStream(),
                originalFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING
        );

        long originalSizeBytes = originalFile.length();

        double originalSizeKB =
                roundTo2(originalSizeBytes / 1024.0);

        BufferedImage inputImage =
                ImageIO.read(originalFile);

        if (inputImage == null) {
            throw new RuntimeException("Unsupported image");
        }

        // FIX ROTATION
        inputImage = fixOrientation(originalFile, inputImage);
        if (inputImage.getWidth() > 2500 || inputImage.getHeight() > 2500) {

            int newWidth = inputImage.getWidth() / 2;
            int newHeight = inputImage.getHeight() / 2;

            BufferedImage reducedImage =
                    resizeImage(
                            inputImage,
                            newWidth,
                            newHeight,
                            false
                    );

            inputImage.flush();

            inputImage = reducedImage;
        }
        BufferedImage workingImage;

        if (safeFormat.equals("png")) {
            workingImage = ensureARGB(inputImage);
        } else {
            workingImage = convertToRGB(inputImage);
        }
        inputImage.flush();
        String compressedName =
                UUID.randomUUID() + "_compressed." + safeFormat;

        File compressedFile =
                new File(uploadFolder, compressedName);

        boolean isDocumentLike =
                isDocumentImage(inputImage);

        boolean ultraCompression =
                targetSizeKB <= 30;
        boolean tinyCompression =
                targetSizeKB <= 20;
        boolean passportMode =
                targetSizeKB <= 10;
        if (passportMode) {

            forcePassportCompression(
                    workingImage,
                    compressedFile
            );

        } else if (tinyCompression) {

            forceTinyCompression(
                    workingImage,
                    compressedFile,
                    isDocumentLike
            );

        } else {

            compressSmartly(
                    workingImage,
                    compressedFile,
                    safeFormat,
                    targetSizeKB,
                    isDocumentLike,
                    ultraCompression
            );
        }

        long compressedSizeBytes =
                compressedFile.length();

        double compressedSizeKB =
                roundTo2(compressedSizeBytes / 1024.0);

        double savings = 0;

        if (originalSizeKB > 0) {
            savings =
                    roundTo2(
                            ((originalSizeKB - compressedSizeKB)
                                    / originalSizeKB) * 100.0
                    );
        }

        ImageEntity image = new ImageEntity();

        image.setUserId(userId);
        image.setTargetSizeKB(targetSizeKB);
        image.setCompressedFileName(compressedName);
        image.setCompressedFilePath(compressedFile.getAbsolutePath());
        image.setOriginalSize(originalSizeKB);
        image.setCompressedSize(compressedSizeKB);
        image.setCompressionMode(compressionMode);
        image.setOutputFormat(safeFormat.toUpperCase());
        image.setSavings(savings);

        if (compressedSizeKB <= targetSizeKB) {
            image.setScore("Excellent");
        } else {
            image.setScore("Optimized");
        }
        workingImage.flush();
        return imageRepository.save(image);
    }

    // =====================================================
    // SMART COMPRESSION
    // =====================================================

    private void compressSmartly(
            BufferedImage inputImage,
            File outputFile,
            String format,
            Long targetSizeKB,
            boolean isDocumentLike,
            boolean ultraCompression
    ) throws Exception {

        long targetBytes =
                targetSizeKB * 1024;

        File bestFile = null;

        long bestDiff = Long.MAX_VALUE;

        double[] scales;

        float[] qualities;

        // ULTRA MODE
        if (ultraCompression) {

            scales = isDocumentLike
                    ? new double[]{0.80, 0.65, 0.50, 0.40}
                    : new double[]{0.75, 0.60, 0.45, 0.35};

            qualities = isDocumentLike
                    ? new float[]{0.55f, 0.45f, 0.35f}
                    : new float[]{0.60f, 0.50f, 0.40f, 0.30f};

        } else {

            scales = isDocumentLike
                    ? new double[]{1.0, 0.95, 0.90}
                    : new double[]{1.0, 0.92, 0.85, 0.78};

            qualities = isDocumentLike
                    ? new float[]{0.92f, 0.88f, 0.84f}
                    : new float[]{0.90f, 0.84f, 0.78f};
        }

        for (double scale : scales) {

            int minDim =
                    ultraCompression
                            ? (isDocumentLike ? 800 : 280)
                            : (isDocumentLike ? 900 : 350);

            int width =
                    Math.max(
                            (int) (inputImage.getWidth() * scale),
                            minDim
                    );

            int height =
                    Math.max(
                            (int) (inputImage.getHeight() * scale),
                            minDim
                    );

            BufferedImage resized =
                    resizeImage(
                            inputImage,
                            width,
                            height,
                            format.equals("png")
                    );

            // DOCUMENT OPTIMIZATION
            if (isDocumentLike) {

                if (ultraCompression) {
                    resized = convertToGrayScale(resized);
                }

                resized =
                        applyLightSharpen(
                                resized,
                                format.equals("png")
                        );
            }

            // ULTRA PHOTO BLUR
            if (ultraCompression
                    && targetSizeKB <= 20
                    && !isDocumentLike) {

               // resized = applySoftBlur(resized);
            }

            for (float quality : qualities) {

                File tempFile =
                        File.createTempFile(
                                "cmp_",
                                "." + format
                        );

                tempFile.deleteOnExit();

                if (format.equals("png")) {

                    writeOptimizedPng(
                            resized,
                            tempFile,
                            ultraCompression
                    );

                } else {

                    writeJpeg(
                            resized,
                            tempFile,
                            quality

                    );
                }

                long size =
                        tempFile.length();

                long diff =
                        Math.abs(size - targetBytes);

                if (diff < bestDiff) {

                    bestDiff = diff;

                    if (bestFile != null
                            && bestFile.exists()) {

                        bestFile.delete();
                    }

                    bestFile = tempFile;
                }

                if (size <= targetBytes) {

                    copyFile(tempFile, outputFile);

                    return;
                }
            }
            resized.flush();
        }

        if (bestFile != null && bestFile.exists()) {

            copyFile(bestFile, outputFile);

            bestFile.delete();
        }
    }

    // =====================================================
    // JPEG WRITER
    // =====================================================
    private void writeJpeg(
            BufferedImage image,
            File outputFile,
            float quality
    ) throws Exception {

        Iterator<ImageWriter> writers =
                ImageIO.getImageWritersByFormatName("jpg");

        if (!writers.hasNext()) {
            throw new RuntimeException("No JPG writer found");
        }

        ImageWriter writer =
                writers.next();

        ImageWriteParam param =
                writer.getDefaultWriteParam();

        if (param.canWriteCompressed()) {

            param.setCompressionMode(
                    ImageWriteParam.MODE_EXPLICIT
            );

            param.setCompressionQuality(
                    Math.max(0.15f,
                            Math.min(quality, 0.98f))
            );
        }

        try (ImageOutputStream ios =
                     ImageIO.createImageOutputStream(outputFile)) {

            writer.setOutput(ios);

            BufferedImage rgbImage = convertToRGB(image);

            writer.write(
                    null,
                    new IIOImage(
                            rgbImage,
                            null,
                            null
                    ),
                    param
            );

            rgbImage.flush();

        } finally {

            writer.dispose();
        }
    }

    // =====================================================
    // PNG WRITER
    // =====================================================
    private void forcePassportCompression(
            BufferedImage inputImage,
            File outputFile
    ) throws Exception {

        int originalWidth = inputImage.getWidth();
        int originalHeight = inputImage.getHeight();

        // =========================================
        // SMART CENTER CROP
        // =========================================

        int cropWidth =
                (int) (originalWidth * 0.70);

        int cropHeight =
                (int) (originalHeight * 0.70);

        int x =
                (originalWidth - cropWidth) / 2;

        int y =
                (originalHeight - cropHeight) / 4;

        if (y < 0) {
            y = 0;
        }

        if (x < 0) {
            x = 0;
        }

        if (x + cropWidth > originalWidth) {
            cropWidth = originalWidth - x;
        }

        if (y + cropHeight > originalHeight) {
            cropHeight = originalHeight - y;
        }

        BufferedImage cropped =
                inputImage.getSubimage(
                        x,
                        y,
                        cropWidth,
                        cropHeight
                );

        // =========================================
        // SMALLER FINAL SIZE
        // =========================================

        int targetWidth = 240;

        int targetHeight =
                (int) (
                        cropped.getHeight()
                                * (targetWidth * 1.0
                                / cropped.getWidth())
                );

        BufferedImage resized =
                resizeImage(
                        cropped,
                        targetWidth,
                        targetHeight,
                        false
                );

        // =========================================
        // LIGHT BLUR
        // =========================================

        resized = applySoftBlur(resized);

        // =========================================
        // FINAL JPEG
        // =========================================

        writeJpeg(
                resized,
                outputFile,
                0.16f
        );
    }
    private void writeOptimizedPng(
            BufferedImage image,
            File outputFile,
            boolean aggressive
    ) throws Exception {

        BufferedImage pngImage;

        if (aggressive) {

            pngImage = new BufferedImage(
                    image.getWidth(),
                    image.getHeight(),
                    BufferedImage.TYPE_BYTE_INDEXED
            );

            Graphics2D g =
                    pngImage.createGraphics();

            g.setColor(Color.WHITE);

            g.fillRect(
                    0,
                    0,
                    image.getWidth(),
                    image.getHeight()
            );

            g.drawImage(image, 0, 0, null);

            g.dispose();

        } else {

            pngImage = ensureARGB(image);
        }

        ImageIO.write(
                pngImage,
                "png",
                outputFile
        );
    }

    // =====================================================
    // RESIZE
    // =====================================================

    private BufferedImage resizeImage(
            BufferedImage original,
            int width,
            int height,
            boolean alpha
    ) {

        int type =
                alpha
                        ? BufferedImage.TYPE_INT_ARGB
                        : BufferedImage.TYPE_INT_RGB;

        BufferedImage resized =
                new BufferedImage(width, height, type);

        Graphics2D g =
                resized.createGraphics();

        g.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BICUBIC
        );

        g.setRenderingHint(
                RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY
        );

        g.drawImage(
                original,
                0,
                0,
                width,
                height,
                null
        );

        g.dispose();

        return resized;
    }

    // =====================================================
    // SOFT BLUR
    // =====================================================

    private BufferedImage applySoftBlur(
            BufferedImage image
    ) {

        int width = image.getWidth();

        int height = image.getHeight();

        BufferedImage output =
                new BufferedImage(
                        width,
                        height,
                        BufferedImage.TYPE_INT_RGB
                );

        for (int y = 1; y < height - 1; y++) {

            for (int x = 1; x < width - 1; x++) {

                Color c1 =
                        new Color(image.getRGB(x, y));

                Color c2 =
                        new Color(image.getRGB(x - 1, y));

                Color c3 =
                        new Color(image.getRGB(x + 1, y));

                Color c4 =
                        new Color(image.getRGB(x, y - 1));

                Color c5 =
                        new Color(image.getRGB(x, y + 1));

                int r =
                        (
                                c1.getRed()
                                        + c2.getRed()
                                        + c3.getRed()
                                        + c4.getRed()
                                        + c5.getRed()
                        ) / 5;

                int g =
                        (
                                c1.getGreen()
                                        + c2.getGreen()
                                        + c3.getGreen()
                                        + c4.getGreen()
                                        + c5.getGreen()
                        ) / 5;

                int b =
                        (
                                c1.getBlue()
                                        + c2.getBlue()
                                        + c3.getBlue()
                                        + c4.getBlue()
                                        + c5.getBlue()
                        ) / 5;

                output.setRGB(
                        x,
                        y,
                        new Color(r, g, b).getRGB()
                );
            }
        }

        return output;
    }

    // =====================================================
    // GRAYSCALE
    // =====================================================

    private BufferedImage convertToGrayScale(
            BufferedImage image
    ) {

        BufferedImage gray =
                new BufferedImage(
                        image.getWidth(),
                        image.getHeight(),
                        BufferedImage.TYPE_BYTE_GRAY
                );

        ColorConvertOp op =
                new ColorConvertOp(
                        ColorSpace.getInstance(ColorSpace.CS_GRAY),
                        null
                );

        op.filter(image, gray);

        return gray;
    }

    // =====================================================
    // RGB
    // =====================================================

    private BufferedImage convertToRGB(
            BufferedImage image
    ) {

        BufferedImage rgb =
                new BufferedImage(
                        image.getWidth(),
                        image.getHeight(),
                        BufferedImage.TYPE_INT_RGB
                );

        Graphics2D g =
                rgb.createGraphics();

        g.setColor(Color.WHITE);

        g.fillRect(
                0,
                0,
                image.getWidth(),
                image.getHeight()
        );

        g.drawImage(image, 0, 0, null);

        g.dispose();

        return rgb;
    }

    private BufferedImage ensureARGB(
            BufferedImage image
    ) {

        BufferedImage argb =
                new BufferedImage(
                        image.getWidth(),
                        image.getHeight(),
                        BufferedImage.TYPE_INT_ARGB
                );

        Graphics2D g =
                argb.createGraphics();

        g.drawImage(image, 0, 0, null);

        g.dispose();

        return argb;
    }

    // =====================================================
    // SHARPEN
    // =====================================================

    private BufferedImage applyLightSharpen(
            BufferedImage input,
            boolean alpha
    ) {

        int width = input.getWidth();

        int height = input.getHeight();

        int type =
                alpha
                        ? BufferedImage.TYPE_INT_ARGB
                        : BufferedImage.TYPE_INT_RGB;

        BufferedImage output =
                new BufferedImage(width, height, type);

        for (int y = 1; y < height - 1; y++) {

            for (int x = 1; x < width - 1; x++) {

                Color center =
                        new Color(input.getRGB(x, y), alpha);

                Color top =
                        new Color(input.getRGB(x, y - 1), alpha);

                Color bottom =
                        new Color(input.getRGB(x, y + 1), alpha);

                Color left =
                        new Color(input.getRGB(x - 1, y), alpha);

                Color right =
                        new Color(input.getRGB(x + 1, y), alpha);

                int r =
                        clamp(
                                (5 * center.getRed())
                                        - top.getRed()
                                        - bottom.getRed()
                                        - left.getRed()
                                        - right.getRed()
                        );

                int g =
                        clamp(
                                (5 * center.getGreen())
                                        - top.getGreen()
                                        - bottom.getGreen()
                                        - left.getGreen()
                                        - right.getGreen()
                        );

                int b =
                        clamp(
                                (5 * center.getBlue())
                                        - top.getBlue()
                                        - bottom.getBlue()
                                        - left.getBlue()
                                        - right.getBlue()
                        );

                int a =
                        alpha
                                ? center.getAlpha()
                                : 255;

                output.setRGB(
                        x,
                        y,
                        new Color(r, g, b, a).getRGB()
                );
            }
        }

        return output;
    }

    private int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    // =====================================================
    // DOCUMENT DETECTION
    // =====================================================

    private boolean isDocumentImage(
            BufferedImage image
    ) {

        int width = image.getWidth();

        int height = image.getHeight();

        long whitePixels = 0;

        long colorfulPixels = 0;

        long sampled = 0;

        int stepX =
                Math.max(1, width / 250);

        int stepY =
                Math.max(1, height / 250);

        for (int y = 0; y < height; y += stepY) {

            for (int x = 0; x < width; x += stepX) {

                int rgb =
                        image.getRGB(x, y);

                int r =
                        (rgb >> 16) & 0xff;

                int g =
                        (rgb >> 8) & 0xff;

                int b =
                        rgb & 0xff;

                int brightness =
                        (r + g + b) / 3;

                int max =
                        Math.max(r,
                                Math.max(g, b));

                int min =
                        Math.min(r,
                                Math.min(g, b));

                int saturation =
                        max - min;

                if (brightness >= 235) {
                    whitePixels++;
                }

                if (saturation >= 45) {
                    colorfulPixels++;
                }

                sampled++;
            }
        }

        double whiteRatio =
                whitePixels * 1.0 / sampled;

        double colorfulRatio =
                colorfulPixels * 1.0 / sampled;

        return whiteRatio >= 0.45
                && colorfulRatio <= 0.30;
    }

    // =====================================================
    // ROTATION FIX
    // =====================================================

    private BufferedImage fixOrientation(
            File file,
            BufferedImage image
    ) {

        try {

            Metadata metadata =
                    ImageMetadataReader.readMetadata(file);

            ExifIFD0Directory directory =
                    metadata.getFirstDirectoryOfType(
                            ExifIFD0Directory.class
                    );

            if (directory == null
                    || !directory.containsTag(
                    ExifIFD0Directory.TAG_ORIENTATION
            )) {

                return image;
            }

            int orientation =
                    directory.getInt(
                            ExifIFD0Directory.TAG_ORIENTATION
                    );

            switch (orientation) {

                case 3:
                    return rotate(image, 180);

                case 6:
                    return rotate(image, 90);

                case 8:
                    return rotate(image, 270);

                default:
                    return image;
            }

        } catch (Exception e) {
            return image;
        }
    }

    private BufferedImage rotate(
            BufferedImage img,
            int angle
    ) {

        int w = img.getWidth();

        int h = img.getHeight();

        BufferedImage rotated;

        if (angle == 90 || angle == 270) {

            rotated =
                    new BufferedImage(
                            h,
                            w,
                            BufferedImage.TYPE_INT_RGB
                    );

        } else {

            rotated =
                    new BufferedImage(
                            w,
                            h,
                            BufferedImage.TYPE_INT_RGB
                    );
        }

        Graphics2D g =
                rotated.createGraphics();

        if (angle == 90) {

            g.translate(h, 0);

        } else if (angle == 180) {

            g.translate(w, h);

        } else if (angle == 270) {

            g.translate(0, w);
        }

        g.rotate(Math.toRadians(angle));

        g.drawImage(img, 0, 0, null);

        g.dispose();

        return rotated;
    }

    // =====================================================
    // HELPERS
    // =====================================================

    private void copyFile(
            File source,
            File destination
    ) throws Exception {

        Files.copy(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING
        );
    }

    private String normalizeFormat(
            String format
    ) {

        if (format == null) {
            return "jpg";
        }

        format =
                format.toLowerCase().trim();

        if (format.equals("png")) {
            return "png";
        }

        return "jpg";
    }

    private String getExtension(
            String name
    ) {

        if (name == null || !name.contains(".")) {
            return "jpg";
        }

        return name.substring(
                name.lastIndexOf(".") + 1
        ).toLowerCase();
    }

    private double roundTo2(double val) {
        return Math.round(val * 100.0) / 100.0;
    }

    // =====================================================
    // HISTORY
    // =====================================================

    public List<ImageEntity> getUserHistory(
            Long userId
    ) {

        return imageRepository
                .findByUserIdOrderByIdDesc(userId);
    }

    // =====================================================
    // DELETE
    // =====================================================

    public boolean deleteImage(Long id) {

        try {

            ImageEntity img =
                    imageRepository.findById(id)
                            .orElse(null);

            if (img == null) {
                return false;
            }

            File f =
                    new File(img.getCompressedFilePath());

            if (f.exists()) {
                f.delete();
            }

            imageRepository.deleteById(id);

            return true;

        } catch (Exception e) {

            return false;
        }
    }
    private void forceTinyCompression(
            BufferedImage inputImage,
            File outputFile,
            boolean isDocumentLike
    ) throws Exception {

        int width;

        // SMART RESOLUTION
        if (isDocumentLike) {

            // better readability for IDs/documents
            width = 600;

        } else {

            // good balance for photos
            width = 420;
        }

        int height =
                (int) (
                        inputImage.getHeight()
                                * (width * 1.0 / inputImage.getWidth())
                );

        BufferedImage resized =
                resizeImage(
                        inputImage,
                        width,
                        height,
                        false
                );

        // =====================================================
        // DOCUMENT OPTIMIZATION
        // =====================================================

        if (isDocumentLike) {

            // grayscale reduces huge file weight
            resized = convertToGrayScale(resized);

            // sharpen text after grayscale
            resized = applyLightSharpen(
                    resized,
                    false
            );

        } else {

            // only soft blur for photos
            resized = applySoftBlur(resized);
        }

        // =====================================================
        // BETTER QUALITY BALANCE
        // =====================================================

        float quality;

        if (isDocumentLike) {

            // cleaner text
            quality = 0.30f;

        } else {

            // preserve face quality
            quality = 0.26f;
        }

        // =====================================================
        // FINAL JPEG WRITE
        // =====================================================

        writeJpeg(
                resized,
                outputFile,
                quality
        );
    }
    // =====================================================
    // DOWNLOAD
    // =====================================================

    public File getCompressedFile(
            Long id
    ) {

        ImageEntity img =
                imageRepository.findById(id)
                        .orElseThrow(() ->
                                new RuntimeException("Image not found"));

        File f =
                new File(img.getCompressedFilePath());

        if (!f.exists()) {
            throw new RuntimeException("File not found");
        }

        return f;
    }
}