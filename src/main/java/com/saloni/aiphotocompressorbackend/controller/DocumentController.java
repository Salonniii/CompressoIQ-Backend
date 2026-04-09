package com.saloni.aiphotocompressorbackend.controller;

import com.saloni.aiphotocompressorbackend.entity.DocumentEntity;
import com.saloni.aiphotocompressorbackend.service.DocumentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileInputStream;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/documents")
@CrossOrigin(origins = "*")
public class DocumentController {

    @Autowired
    private DocumentService documentService;

    // =====================================
    // UPLOAD / COMPRESS DOCUMENT
    // =====================================
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadDocument(
            @RequestParam("file") MultipartFile file,
            @RequestParam("targetSizeKB") Long targetSizeKB,
            @RequestParam("userId") Long userId
    ) {
        try {
            DocumentEntity savedDocument = documentService.compressAndSaveDocument(
                    file,
                    targetSizeKB,
                    userId
            );

            return ResponseEntity.ok(savedDocument);

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Document compression failed",
                    "error", e.getMessage()
            ));
        }
    }

    // =====================================
    // DOWNLOAD DOCUMENT
    // =====================================
    @GetMapping("/download/{id}")
    public ResponseEntity<?> downloadDocument(@PathVariable Long id) {
        try {
            File file = documentService.getCompressedFile(id);

            InputStreamResource resource = new InputStreamResource(new FileInputStream(file));

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + file.getName())
                    .contentLength(file.length())
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(resource);

        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Download failed",
                    "error", e.getMessage()
            ));
        }
    }

    // =====================================
    // HISTORY
    // =====================================
    @GetMapping("/history/{userId}")
    public ResponseEntity<List<DocumentEntity>> getUserDocumentHistory(@PathVariable Long userId) {
        return ResponseEntity.ok(documentService.getUserDocumentHistory(userId));
    }

    // =====================================
    // DELETE
    // =====================================
    @DeleteMapping("/delete/{id}")
    public ResponseEntity<?> deleteDocument(@PathVariable Long id) {
        boolean deleted = documentService.deleteDocument(id);

        if (deleted) {
            return ResponseEntity.ok(Map.of("message", "Deleted successfully"));
        } else {
            return ResponseEntity.badRequest().body(Map.of("message", "Deletion failed"));
        }
    }
}