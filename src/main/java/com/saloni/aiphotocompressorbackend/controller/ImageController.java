package com.saloni.aiphotocompressorbackend.controller;

import com.saloni.aiphotocompressorbackend.entity.ImageEntity;
import com.saloni.aiphotocompressorbackend.service.ImageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/images")
@CrossOrigin(origins = "*")
public class ImageController {

    @Autowired
    private ImageService imageService;

    // ===============================
    // COMPRESS IMAGE
    // ===============================
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadImage(
            @RequestParam("file") MultipartFile file,
            @RequestParam("targetSizeKB") Long targetSizeKB,
            @RequestParam("compressionMode") String compressionMode,
            @RequestParam("outputFormat") String outputFormat,
            @RequestParam("userId") Long userId
    ) {
        try {
            ImageEntity savedImage = imageService.compressAndSaveImage(
                    file,
                    targetSizeKB,
                    compressionMode,
                    outputFormat,
                    userId
            );
            return ResponseEntity.ok(savedImage);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Compression failed",
                    "error", e.getMessage()
            ));
        }
    }

    // ===============================
    // GET HISTORY
    // ===============================
    @GetMapping("/history/{userId}")
    public ResponseEntity<List<ImageEntity>> getUserHistory(@PathVariable Long userId) {
        return ResponseEntity.ok(imageService.getUserHistory(userId));
    }

    // ===============================
    // DELETE IMAGE
    // ===============================
    @DeleteMapping("/delete/{imageId}")
    public ResponseEntity<Map<String, String>> deleteImage(@PathVariable Long imageId) {
        boolean deleted = imageService.deleteImage(imageId);

        Map<String, String> response = new HashMap<>();

        if (deleted) {
            response.put("message", "Deleted successfully");
            return ResponseEntity.ok(response);
        } else {
            response.put("error", "Deletion failed");
            return ResponseEntity.badRequest().body(response);
        }
    }

    // ===============================
    // DOWNLOAD IMAGE
    // ===============================
    @GetMapping("/download/{imageId}")
    public ResponseEntity<?> downloadImage(@PathVariable Long imageId) {
        try {
            File file = imageService.getCompressedFile(imageId);

            if (file == null || !file.exists()) {
                return ResponseEntity.notFound().build();
            }

            InputStreamResource resource = new InputStreamResource(new FileInputStream(file));

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + file.getName())
                    .contentLength(file.length())
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(resource);

        } catch (IOException e) {
            return ResponseEntity.badRequest().body(Map.of("message", "Download failed"));
        }
    }
}