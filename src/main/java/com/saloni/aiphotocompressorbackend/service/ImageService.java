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
import java.awt.image.BufferedImage;
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

        // Stable upload folder
        File uploadFolder = new File(uploadDir).getAbsoluteFile();
        if (!uploadFolder.exists()) {
            boolean created = uploadFolder.mkdirs();
            if (!created) {
                throw new RuntimeException("Failed to create upload directory: " + uploadFolder.getAbsolutePath());
            }
        }

        if (!uploadFolder.isDirectory()) {
            throw new RuntimeException("Upload path is not a directory: " + uploadFolder.getAbsolutePath());
        }

        // Save original
        String originalExt = getExtension(file.getOriginalFilename());
        String originalName = UUID.randomUUID() + "_original." + originalExt;
        File originalFile = new File(uploadFolder, originalName);

        Files.copy(file.getInputStream(), originalFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

        long originalSizeBytes = originalFile.length();
        double originalSizeKB = roundTo2(originalSizeBytes / 1024.0);

        BufferedImage inputImage = ImageIO.read(originalFile);
        if (inputImage == null) {
            throw new RuntimeException("Unsupported image file");
        }

        // ✅ FIX CAMERA ROTATION ONLY
        inputImage = fixOrientation(originalFile, inputImage);

        BufferedImage workingImage;
        if (safeFormat.equals("png")) {
            workingImage = ensureARGB(inputImage);
        } else {
            workingImage = convertToRGB(inputImage);
        }

        String compressedName = UUID.randomUUID() + "_compressed." + safeFormat;
        File compressedFile = new File(uploadFolder, compressedName);

        boolean isDocumentLike = isDocumentImage(inputImage);

        compressSmartly(workingImage, compressedFile, safeFormat, targetSizeKB, isDocumentLike, originalSizeBytes);

        // Safety fallback if compressed becomes larger than original
        if (!compressedFile.exists() || compressedFile.length() == 0 || compressedFile.length() >= originalSizeBytes) {
            BufferedImage fallback = prepareFallbackImage(workingImage, safeFormat, isDocumentLike);

            if (safeFormat.equals("png")) {
                writeOptimizedPng(fallback, compressedFile, !isDocumentLike);
            } else {
                writeJpeg(fallback, compressedFile, isDocumentLike ? 0.84f : 0.72f);
            }
        }

        long compressedSizeBytes = compressedFile.length();
        double compressedSizeKB = roundTo2(compressedSizeBytes / 1024.0);

        double savings = 0.0;
        if (originalSizeKB > 0) {
            savings = roundTo2(((originalSizeKB - compressedSizeKB) / originalSizeKB) * 100.0);
            if (savings < 0) savings = 0.0;
        }

        String score = generateScore(savings, compressedSizeKB, targetSizeKB, isDocumentLike);

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
        image.setScore(score);

        return imageRepository.save(image);
    }

    private void compressSmartly(BufferedImage inputImage,
                                 File outputFile,
                                 String format,
                                 Long targetSizeKB,
                                 boolean isDocumentLike,
                                 long originalSizeBytes) throws Exception {

        long targetBytes = targetSizeKB * 1024;

        long minAllowedBytes;
        long maxAllowedBytes;

        if (isDocumentLike) {
            minAllowedBytes = (long) (targetBytes * 0.85);
            maxAllowedBytes = (long) (targetBytes * 1.30);
        } else {
            minAllowedBytes = (long) (targetBytes * 0.70);
            maxAllowedBytes = (long) (targetBytes * 1.20);
        }

        File bestTemp = null;
        long bestScore = Long.MAX_VALUE;

        double[] scales;
        float[] qualities;

        if (format.equals("png")) {
            scales = isDocumentLike
                    ? new double[]{1.0, 0.98, 0.96, 0.94, 0.92, 0.90, 0.88}
                    : new double[]{1.0, 0.92, 0.85, 0.78, 0.70, 0.62, 0.55};
            qualities = new float[]{1.0f};
        } else {
            if (isDocumentLike) {
                scales = new double[]{1.0, 0.98, 0.96, 0.94, 0.92, 0.90, 0.88};
                qualities = new float[]{0.95f, 0.92f, 0.89f, 0.86f, 0.83f, 0.80f};
            } else {
                scales = new double[]{1.0, 0.95, 0.90, 0.85, 0.80, 0.72, 0.65};
                qualities = new float[]{0.90f, 0.84f, 0.78f, 0.72f, 0.66f, 0.60f};
            }
        }

        for (double scale : scales) {

            int minDim = isDocumentLike ? 800 : 250;

            int width = Math.max((int) (inputImage.getWidth() * scale), minDim);
            int height = Math.max((int) (inputImage.getHeight() * scale), minDim);

            BufferedImage resized = resizeImage(inputImage, width, height, format.equals("png"));

            if (isDocumentLike) {
                resized = applyLightSharpen(resized, format.equals("png"));
            }

            for (float quality : qualities) {

                File tempFile = File.createTempFile("img_", "." + format);
                tempFile.deleteOnExit();

                if (format.equals("png")) {
                    writeOptimizedPng(resized, tempFile, false);
                } else {
                    writeJpeg(resized, tempFile, quality);
                }

                long size = tempFile.length();

                if (size >= minAllowedBytes && size <= maxAllowedBytes) {
                    copyFile(tempFile, outputFile);
                    return;
                }

                long diff = Math.abs(size - targetBytes);

                long penalty = 0;

                if (isDocumentLike) {
                    if (quality < 0.80f) penalty += 500000;
                    if (width < 800 || height < 800) penalty += 500000;
                }

                long score = diff + penalty;

                if (score < bestScore) {
                    bestScore = score;

                    if (bestTemp != null && bestTemp.exists()) {
                        bestTemp.delete();
                    }

                    bestTemp = tempFile;
                } else {
                    tempFile.delete();
                }
            }
        }

        if (bestTemp != null && bestTemp.exists()) {
            copyFile(bestTemp, outputFile);
            bestTemp.delete();
        } else {
            BufferedImage fallback = prepareFallbackImage(inputImage, format, isDocumentLike);

            if (format.equals("png")) {
                writeOptimizedPng(fallback, outputFile, !isDocumentLike);
            } else {
                writeJpeg(fallback, outputFile, isDocumentLike ? 0.85f : 0.65f);
            }
        }
    }

    private void writeJpeg(BufferedImage image, File outputFile, float quality) throws Exception {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            throw new RuntimeException("No JPG writer found");
        }

        ImageWriter writer = writers.next();
        ImageWriteParam param = writer.getDefaultWriteParam();

        if (param.canWriteCompressed()) {
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(Math.max(0.45f, Math.min(quality, 0.98f)));
        }

        try (ImageOutputStream ios = ImageIO.createImageOutputStream(outputFile)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(convertToRGB(image), null, null), param);
        } finally {
            writer.dispose();
        }
    }

    private void writeOptimizedPng(BufferedImage image, File outputFile, boolean aggressive) throws Exception {
        BufferedImage pngImage;

        if (aggressive) {
            pngImage = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_BYTE_INDEXED);
            Graphics2D g = pngImage.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, image.getWidth(), image.getHeight());
            g.drawImage(image, 0, 0, null);
            g.dispose();
        } else {
            pngImage = ensureARGB(image);
        }

        boolean written = ImageIO.write(pngImage, "png", outputFile);
        if (!written) {
            throw new RuntimeException("Failed to write PNG");
        }
    }

    private BufferedImage resizeImage(BufferedImage original, int width, int height, boolean alpha) {
        int type = alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage resized = new BufferedImage(width, height, type);

        Graphics2D g = resized.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        if (!alpha) {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
        }

        g.drawImage(original, 0, 0, width, height, null);
        g.dispose();

        return resized;
    }

    private BufferedImage convertToRGB(BufferedImage image) {
        BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
        g.drawImage(image, 0, 0, null);
        g.dispose();
        return rgb;
    }

    private BufferedImage ensureARGB(BufferedImage image) {
        BufferedImage argb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = argb.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();
        return argb;
    }

    private BufferedImage prepareFallbackImage(BufferedImage inputImage, String format, boolean isDocumentLike) {
        int width;
        int height;

        if (isDocumentLike) {
            width = Math.max((int) (inputImage.getWidth() * 0.90), 1000);
            height = Math.max((int) (inputImage.getHeight() * 0.90), 1000);
        } else {
            width = Math.max(inputImage.getWidth() / 2, 300);
            height = Math.max(inputImage.getHeight() / 2, 300);
        }

        BufferedImage fallback = resizeImage(inputImage, width, height, format.equals("png"));

        if (isDocumentLike) {
            fallback = applyLightSharpen(fallback, format.equals("png"));
        }

        if (!format.equals("png")) {
            fallback = convertToRGB(fallback);
        }

        return fallback;
    }

    private void copyFile(File source, File destination) throws Exception {
        Files.copy(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private String normalizeFormat(String format) {
        if (format == null) return "jpg";
        format = format.toLowerCase().trim();

        if (format.equals("png")) return "png";
        return "jpg";
    }

    private String getExtension(String name) {
        if (name == null || !name.contains(".")) return "jpg";
        return name.substring(name.lastIndexOf(".") + 1).toLowerCase();
    }

    private double roundTo2(double val) {
        return Math.round(val * 100.0) / 100.0;
    }

    private String generateScore(double savings, double compressed, Long target, boolean isDocumentLike) {
        if (isDocumentLike) {
            if (compressed <= target && savings >= 40) return "Excellent";
            if (compressed <= target && savings >= 25) return "Very Good";
            if (compressed <= target) return "Good";
            if (compressed <= target * 1.5) return "Readable";
            return "Protected Quality";
        } else {
            if (compressed <= target && savings >= 60) return "Excellent";
            if (compressed <= target && savings >= 40) return "Very Good";
            if (compressed <= target && savings >= 20) return "Good";
            if (compressed <= target) return "Acceptable";
            return "Needs Improvement";
        }
    }

    public List<ImageEntity> getUserHistory(Long userId) {
        return imageRepository.findByUserIdOrderByIdDesc(userId);
    }

    public boolean deleteImage(Long id) {
        try {
            ImageEntity img = imageRepository.findById(id).orElse(null);
            if (img == null) return false;

            File f = new File(img.getCompressedFilePath());
            if (f.exists()) f.delete();

            imageRepository.deleteById(id);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public File getCompressedFile(Long id) {
        ImageEntity img = imageRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Image not found"));

        File f = new File(img.getCompressedFilePath());
        if (!f.exists()) throw new RuntimeException("File not found");

        return f;
    }

    // =====================================================
    // SMART DOCUMENT IMAGE DETECTION
    // =====================================================
    private boolean isDocumentImage(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();

        long totalPixels = (long) width * height;
        if (totalPixels == 0) return false;

        int sampleStepX = Math.max(1, width / 250);
        int sampleStepY = Math.max(1, height / 250);

        long whitePixels = 0;
        long grayPixels = 0;
        long darkPixels = 0;
        long colorfulPixels = 0;
        long sampled = 0;

        for (int y = 0; y < height; y += sampleStepY) {
            for (int x = 0; x < width; x += sampleStepX) {
                int rgb = image.getRGB(x, y);

                int r = (rgb >> 16) & 0xff;
                int g = (rgb >> 8) & 0xff;
                int b = rgb & 0xff;

                int brightness = (r + g + b) / 3;
                int max = Math.max(r, Math.max(g, b));
                int min = Math.min(r, Math.min(g, b));
                int saturation = max - min;

                if (brightness >= 235) whitePixels++;
                if (brightness >= 170 && brightness < 235) grayPixels++;
                if (brightness <= 70) darkPixels++;
                if (saturation >= 45) colorfulPixels++;

                sampled++;
            }
        }

        if (sampled == 0) return false;

        double whiteRatio = whitePixels * 1.0 / sampled;
        double grayRatio = grayPixels * 1.0 / sampled;
        double darkRatio = darkPixels * 1.0 / sampled;
        double colorfulRatio = colorfulPixels * 1.0 / sampled;

        return (whiteRatio >= 0.40 && darkRatio >= 0.03 && colorfulRatio <= 0.28)
                || (whiteRatio + grayRatio >= 0.72 && colorfulRatio <= 0.30);
    }

    // =====================================================
    // LIGHT SHARPEN FOR TEXT IMAGES
    // =====================================================
    private BufferedImage applyLightSharpen(BufferedImage input, boolean alpha) {
        int width = input.getWidth();
        int height = input.getHeight();

        int type = alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage output = new BufferedImage(width, height, type);

        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                Color center = new Color(input.getRGB(x, y), alpha);
                Color top = new Color(input.getRGB(x, y - 1), alpha);
                Color bottom = new Color(input.getRGB(x, y + 1), alpha);
                Color left = new Color(input.getRGB(x - 1, y), alpha);
                Color right = new Color(input.getRGB(x + 1, y), alpha);

                int r = clamp((5 * center.getRed()) - top.getRed() - bottom.getRed() - left.getRed() - right.getRed());
                int g = clamp((5 * center.getGreen()) - top.getGreen() - bottom.getGreen() - left.getGreen() - right.getGreen());
                int b = clamp((5 * center.getBlue()) - top.getBlue() - bottom.getBlue() - left.getBlue() - right.getBlue());

                int a = alpha ? center.getAlpha() : 255;
                Color sharpened = new Color(r, g, b, a);
                output.setRGB(x, y, sharpened.getRGB());
            }
        }

        for (int x = 0; x < width; x++) {
            output.setRGB(x, 0, input.getRGB(x, 0));
            output.setRGB(x, height - 1, input.getRGB(x, height - 1));
        }
        for (int y = 0; y < height; y++) {
            output.setRGB(0, y, input.getRGB(0, y));
            output.setRGB(width - 1, y, input.getRGB(width - 1, y));
        }

        return output;
    }

    private int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    // =====================================================
    // CAMERA IMAGE ROTATION FIX
    // =====================================================
    private BufferedImage fixOrientation(File file, BufferedImage image) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(file);
            ExifIFD0Directory directory = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);

            if (directory == null || !directory.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                return image;
            }

            int orientation = directory.getInt(ExifIFD0Directory.TAG_ORIENTATION);

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

    private BufferedImage rotate(BufferedImage img, int angle) {
        int w = img.getWidth();
        int h = img.getHeight();

        int type = img.getType();
        if (type == BufferedImage.TYPE_CUSTOM || type == 0) {
            type = BufferedImage.TYPE_INT_RGB;
        }

        BufferedImage newImage;

        if (angle == 90 || angle == 270) {
            newImage = new BufferedImage(h, w, type);
        } else {
            newImage = new BufferedImage(w, h, type);
        }

        Graphics2D g2 = newImage.createGraphics();

        if (type != BufferedImage.TYPE_INT_ARGB) {
            g2.setColor(Color.WHITE);
            g2.fillRect(0, 0, newImage.getWidth(), newImage.getHeight());
        }

        if (angle == 90) {
            g2.translate(h, 0);
            g2.rotate(Math.toRadians(90));
        } else if (angle == 180) {
            g2.translate(w, h);
            g2.rotate(Math.toRadians(180));
        } else if (angle == 270) {
            g2.translate(0, w);
            g2.rotate(Math.toRadians(270));
        }

        g2.drawImage(img, 0, 0, null);
        g2.dispose();

        return newImage;
    }
}