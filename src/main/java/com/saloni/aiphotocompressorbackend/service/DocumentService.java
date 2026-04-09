package com.saloni.aiphotocompressorbackend.service;

import com.saloni.aiphotocompressorbackend.entity.DocumentEntity;
import com.saloni.aiphotocompressorbackend.repository.DocumentRepository;
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
import java.io.*;
import java.nio.file.*;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.zip.*;

@Service
public class DocumentService {

    @Autowired
    private DocumentRepository documentRepository;

    @Value("${app.upload.dir}")
    private String uploadDir;

    @Value("${ghostscript.path}")
    private String ghostscriptPath;

    // ==========================================
    // UPLOAD + COMPRESS DOCUMENT
    // ==========================================
    public DocumentEntity compressAndSaveDocument(
            MultipartFile file,
            Long targetSizeKB,
            Long userId
    ) throws Exception {

        if (file == null || file.isEmpty()) {
            throw new RuntimeException("No document uploaded");
        }

        if (targetSizeKB == null || targetSizeKB <= 0) {
            throw new RuntimeException("Invalid target size");
        }

        String originalFileName = file.getOriginalFilename();
        if (originalFileName == null || originalFileName.trim().isEmpty()) {
            originalFileName = "document_file";
        }

        String extension = getFileExtension(originalFileName).toLowerCase();
        String fileType = detectFileType(extension);

        if (fileType.equals("UNSUPPORTED")) {
            throw new RuntimeException("Unsupported file type. Only PDF, DOCX, PPTX, XLSX allowed.");
        }

        Path documentDir = Paths.get(uploadDir, "documents");
        Files.createDirectories(documentDir);

        File tempInputFile = File.createTempFile("doc_input_", extension);
        Files.copy(file.getInputStream(), tempInputFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

        String uniqueName = UUID.randomUUID().toString();
        String compressedFileName = "compressed_" + uniqueName + extension;
        Path compressedPath = documentDir.resolve(compressedFileName);

        try {
            smartDocumentCompression(tempInputFile, compressedPath.toFile(), fileType, targetSizeKB);

            // fallback if compressed is bad / missing / larger
            if (!compressedPath.toFile().exists()
                    || compressedPath.toFile().length() == 0
                    || compressedPath.toFile().length() >= tempInputFile.length()) {
                copyFile(tempInputFile, compressedPath.toFile());
            }

            double originalSizeKB = tempInputFile.length() / 1024.0;
            double compressedSizeKB = Files.size(compressedPath) / 1024.0;
            double savings = calculateSavings(originalSizeKB, compressedSizeKB);

            DocumentEntity entity = new DocumentEntity();
            entity.setUserId(userId);
            entity.setTargetSizeKB(targetSizeKB);
            entity.setOriginalFileName(originalFileName);
            entity.setCompressedFileName(compressedFileName);
            entity.setCompressedFilePath(compressedPath.toString());
            entity.setOriginalSize(round(originalSizeKB));
            entity.setCompressedSize(round(compressedSizeKB));
            entity.setSavings(round(savings));
            entity.setFileType(fileType);

            // If you added status column, keep this.
            // If not, remove this one line only.
            entity.setStatus(determineDocumentStatus(originalSizeKB, compressedSizeKB, targetSizeKB));

            return documentRepository.save(entity);

        } finally {
            if (tempInputFile.exists()) {
                tempInputFile.delete();
            }
        }
    }

    // ==========================================
    // SMART DOCUMENT COMPRESSION
    // ==========================================
    private void smartDocumentCompression(File inputFile, File outputFile, String fileType, Long targetSizeKB) throws Exception {
        switch (fileType) {
            case "PDF":
                compressPdfWithGhostscript(inputFile, outputFile, targetSizeKB);
                break;

            case "WORD":
            case "PPT":
            case "EXCEL":
                compressOfficeFileWithImageOptimization(inputFile, outputFile);
                break;

            default:
                copyFile(inputFile, outputFile);
                break;
        }
    }

    // ==========================================
    // PDF COMPRESSION (QUALITY FIRST)
    // ==========================================
    private void compressPdfWithGhostscript(File inputFile, File outputFile, Long targetSizeKB) throws Exception {
        if (targetSizeKB == null || targetSizeKB <= 0) {
            runGhostscript(inputFile, outputFile, "/printer");
            return;
        }

        long targetBytes = targetSizeKB * 1024;
        long originalBytes = inputFile.length();

        // ✅ QUALITY-FIRST presets only
        // Removed /screen because it destroys quality badly
        String[] presets = {"/prepress", "/printer", "/ebook"};

        File bestFile = null;
        long bestScore = Long.MAX_VALUE;

        for (String preset : presets) {
            File tempOutput = File.createTempFile("pdf_compressed_", ".pdf");
            tempOutput.deleteOnExit();

            try {
                runGhostscript(inputFile, tempOutput, preset);

                if (!tempOutput.exists() || tempOutput.length() == 0) {
                    tempOutput.delete();
                    continue;
                }

                long currentSize = tempOutput.length();

                // ❌ Skip if larger than original
                if (currentSize >= originalBytes) {
                    tempOutput.delete();
                    continue;
                }

                long diff = Math.abs(currentSize - targetBytes);

                // ✅ Quality-aware scoring
                long qualityPenalty = 0;

                if (preset.equals("/ebook")) {
                    qualityPenalty += 250000; // slightly penalize lower quality
                }

                if (preset.equals("/printer")) {
                    qualityPenalty += 50000;
                }

                // prefer reasonable size reduction without ruining quality
                long score = diff + qualityPenalty;

                if (score < bestScore) {
                    if (bestFile != null && bestFile.exists()) {
                        bestFile.delete();
                    }

                    bestScore = score;
                    bestFile = tempOutput;
                } else {
                    tempOutput.delete();
                }

            } catch (Exception e) {
                tempOutput.delete();
            }
        }

        if (bestFile != null && bestFile.exists()) {
            copyFile(bestFile, outputFile);
            bestFile.delete();
        } else {
            // fallback: safest quality
            runGhostscript(inputFile, outputFile, "/printer");
        }
    }

    // ==========================================
    // RUN GHOSTSCRIPT
    // ==========================================
    private void runGhostscript(File inputFile, File outputFile, String qualityPreset) throws Exception {
        ProcessBuilder processBuilder = new ProcessBuilder(
                ghostscriptPath,
                "-sDEVICE=pdfwrite",
                "-dCompatibilityLevel=1.4",
                "-dNOPAUSE",
                "-dQUIET",
                "-dBATCH",
                "-dPDFSETTINGS=" + qualityPreset,
                "-sOutputFile=" + outputFile.getAbsolutePath(),
                inputFile.getAbsolutePath()
        );

        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();

        StringBuilder outputLog = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                outputLog.append(line).append("\n");
            }
        }

        int exitCode = process.waitFor();

        if (exitCode != 0 || !outputFile.exists() || outputFile.length() == 0) {
            throw new RuntimeException("Ghostscript failed (" + qualityPreset + "):\n" + outputLog);
        }
    }

    // ==========================================
    // DOCX / PPTX / XLSX compression
    // ==========================================
    private void compressOfficeFileWithImageOptimization(File inputFile, File outputFile) throws Exception {
        File tempZipFile = File.createTempFile("office_compressed_", ".tmp");

        try (
                ZipFile zipFile = new ZipFile(inputFile);
                FileOutputStream fos = new FileOutputStream(tempZipFile);
                BufferedOutputStream bos = new BufferedOutputStream(fos);
                ZipOutputStream zos = new ZipOutputStream(bos)
        ) {
            zos.setLevel(Deflater.BEST_COMPRESSION);

            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            byte[] buffer = new byte[8192];

            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String entryName = entry.getName();

                if (entryName.startsWith("__MACOSX") || entryName.contains(".DS_Store")) {
                    continue;
                }

                ZipEntry newEntry = new ZipEntry(entryName);
                newEntry.setTime(entry.getTime());
                newEntry.setMethod(ZipEntry.DEFLATED);
                zos.putNextEntry(newEntry);

                try (InputStream is = zipFile.getInputStream(entry)) {

                    if (isOfficeMediaImage(entryName)) {
                        byte[] optimizedImage = tryCompressEmbeddedImage(is, entryName);

                        if (optimizedImage != null) {
                            zos.write(optimizedImage);
                        } else {
                            int len;
                            while ((len = is.read(buffer)) > 0) {
                                zos.write(buffer, 0, len);
                            }
                        }

                    } else {
                        int len;
                        while ((len = is.read(buffer)) > 0) {
                            zos.write(buffer, 0, len);
                        }
                    }
                }

                zos.closeEntry();
            }

        } catch (Exception e) {
            copyFile(inputFile, outputFile);
            if (tempZipFile.exists()) tempZipFile.delete();
            return;
        }

        if (tempZipFile.exists() && tempZipFile.length() < inputFile.length()) {
            Files.move(tempZipFile.toPath(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } else {
            copyFile(inputFile, outputFile);
            tempZipFile.delete();
        }
    }

    // ==========================================
    // CHECK OFFICE MEDIA IMAGE
    // ==========================================
    private boolean isOfficeMediaImage(String entryName) {
        String lower = entryName.toLowerCase();

        return (lower.startsWith("word/media/")
                || lower.startsWith("ppt/media/")
                || lower.startsWith("xl/media/"))
                &&
                (lower.endsWith(".jpg")
                        || lower.endsWith(".jpeg")
                        || lower.endsWith(".png"));
    }

    // ==========================================
    // COMPRESS EMBEDDED IMAGE
    // ==========================================
    private byte[] tryCompressEmbeddedImage(InputStream is, String fileName) {
        try {
            byte[] originalBytes = readAllBytes(is);

            if (originalBytes.length < 150 * 1024) {
                return null;
            }

            BufferedImage originalImage = ImageIO.read(new ByteArrayInputStream(originalBytes));
            if (originalImage == null) {
                return null;
            }

            String lower = fileName.toLowerCase();

            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
                return compressJpegBytes(originalImage, originalBytes.length);
            }

            if (lower.endsWith(".png")) {
                return compressPngBytes(originalImage, originalBytes.length);
            }

            return null;

        } catch (Exception e) {
            return null;
        }
    }

    // ==========================================
    // JPEG IMAGE COMPRESSION
    // ==========================================
    private byte[] compressJpegBytes(BufferedImage image, int originalSize) throws Exception {
        BufferedImage resized = resizeIfNeeded(image, 1800);

        float[] qualities = {0.88f, 0.82f, 0.76f};

        byte[] best = null;

        for (float q : qualities) {
            byte[] compressed = writeJpegToBytes(resized, q);

            if (compressed.length < originalSize) {
                best = compressed;
                break;
            }
        }

        return best;
    }

    // ==========================================
    // PNG IMAGE COMPRESSION
    // ==========================================
    private byte[] compressPngBytes(BufferedImage image, int originalSize) throws Exception {
        BufferedImage resized = resizeIfNeeded(image, 1800);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        boolean written = ImageIO.write(resized, "png", baos);

        if (!written) return null;

        byte[] compressed = baos.toByteArray();

        if (compressed.length < originalSize) {
            return compressed;
        }

        return null;
    }

    // ==========================================
    // RESIZE LARGE IMAGES ONLY
    // ==========================================
    private BufferedImage resizeIfNeeded(BufferedImage original, int maxDimension) {
        int width = original.getWidth();
        int height = original.getHeight();

        if (width <= maxDimension && height <= maxDimension) {
            return original;
        }

        double scale = Math.min((double) maxDimension / width, (double) maxDimension / height);

        int newWidth = Math.max((int) (width * scale), 1);
        int newHeight = Math.max((int) (height * scale), 1);

        BufferedImage resized = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resized.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        g.setColor(Color.WHITE);
        g.fillRect(0, 0, newWidth, newHeight);
        g.drawImage(original, 0, 0, newWidth, newHeight, null);
        g.dispose();

        return resized;
    }

    // ==========================================
    // WRITE JPEG TO BYTES
    // ==========================================
    private byte[] writeJpegToBytes(BufferedImage image, float quality) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            throw new RuntimeException("No JPEG writer found");
        }

        ImageWriter writer = writers.next();
        ImageWriteParam param = writer.getDefaultWriteParam();

        if (param.canWriteCompressed()) {
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
        }

        try (ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }

        return baos.toByteArray();
    }

    // ==========================================
    // FILE COPY
    // ==========================================
    private void copyFile(File inputFile, File outputFile) throws Exception {
        Files.copy(inputFile.toPath(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    // ==========================================
    // GET FILE FOR DOWNLOAD
    // ==========================================
    public File getCompressedFile(Long documentId) {
        DocumentEntity document = documentRepository.findById(documentId)
                .orElseThrow(() -> new RuntimeException("Document not found"));

        File file = new File(document.getCompressedFilePath());

        if (!file.exists()) {
            throw new RuntimeException("Compressed file not found");
        }

        return file;
    }

    // ==========================================
    // GET HISTORY
    // ==========================================
    public List<DocumentEntity> getUserDocumentHistory(Long userId) {
        return documentRepository.findByUserIdOrderByIdDesc(userId);
    }

    // ==========================================
    // DELETE DOCUMENT
    // ==========================================
    public boolean deleteDocument(Long documentId) {
        try {
            DocumentEntity document = documentRepository.findById(documentId)
                    .orElseThrow(() -> new RuntimeException("Document not found"));

            File file = new File(document.getCompressedFilePath());
            if (file.exists()) {
                file.delete();
            }

            documentRepository.deleteById(documentId);
            return true;

        } catch (Exception e) {
            return false;
        }
    }

    // ==========================================
    // STATUS
    // ==========================================
    private String determineDocumentStatus(double originalSizeKB, double compressedSizeKB, Long targetSizeKB) {
        double savings = calculateSavings(originalSizeKB, compressedSizeKB);

        if (savings < 1.0) {
            return "Already Optimized";
        }

        if (savings >= 1.0 && savings < 5.0) {
            return "Light Compression Applied";
        }

        if (targetSizeKB != null && compressedSizeKB > targetSizeKB * 1.20) {
            return "Quality Protected";
        }

        return "Compressed Successfully";
    }

    // ==========================================
    // HELPERS
    // ==========================================
    private String getFileExtension(String fileName) {
        int lastDot = fileName.lastIndexOf(".");
        return (lastDot != -1) ? fileName.substring(lastDot) : "";
    }

    private String detectFileType(String extension) {
        switch (extension) {
            case ".pdf":
                return "PDF";
            case ".docx":
                return "WORD";
            case ".pptx":
                return "PPT";
            case ".xlsx":
                return "EXCEL";
            default:
                return "UNSUPPORTED";
        }
    }

    private double calculateSavings(double original, double compressed) {
        if (original <= 0) return 0.0;
        double result = ((original - compressed) / original) * 100.0;
        return Math.max(result, 0.0);
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] data = new byte[8192];
        int nRead;

        while ((nRead = is.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }

        return buffer.toByteArray();
    }
}