package com.saloni.aiphotocompressorbackend.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "documents")
public class DocumentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;
    private Long targetSizeKB;

    private String originalFileName;
    private String compressedFileName;
    private String compressedFilePath;

    private Double originalSize;
    private Double compressedSize;
    private Double savings;

    private String fileType;

    // ✅ NEW FIELD
    private String status;

    public DocumentEntity() {
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getTargetSizeKB() {
        return targetSizeKB;
    }

    public void setTargetSizeKB(Long targetSizeKB) {
        this.targetSizeKB = targetSizeKB;
    }

    public String getOriginalFileName() {
        return originalFileName;
    }

    public void setOriginalFileName(String originalFileName) {
        this.originalFileName = originalFileName;
    }

    public String getCompressedFileName() {
        return compressedFileName;
    }

    public void setCompressedFileName(String compressedFileName) {
        this.compressedFileName = compressedFileName;
    }

    public String getCompressedFilePath() {
        return compressedFilePath;
    }

    public void setCompressedFilePath(String compressedFilePath) {
        this.compressedFilePath = compressedFilePath;
    }

    public Double getOriginalSize() {
        return originalSize;
    }

    public void setOriginalSize(Double originalSize) {
        this.originalSize = originalSize;
    }

    public Double getCompressedSize() {
        return compressedSize;
    }

    public void setCompressedSize(Double compressedSize) {
        this.compressedSize = compressedSize;
    }

    public Double getSavings() {
        return savings;
    }

    public void setSavings(Double savings) {
        this.savings = savings;
    }

    public String getFileType() {
        return fileType;
    }

    public void setFileType(String fileType) {
        this.fileType = fileType;
    }

    // ✅ NEW GETTER / SETTER
    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}