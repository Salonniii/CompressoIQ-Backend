package com.saloni.aiphotocompressorbackend.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "images")
public class ImageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;
    private Long targetSizeKB;

    private String compressedFileName;
    private String compressedFilePath;

    private Double originalSize;
    private Double compressedSize;

    private String compressionMode;
    private String outputFormat;

    private Double savings;
    private String score;

    public ImageEntity() {
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

    public String getCompressionMode() {
        return compressionMode;
    }

    public void setCompressionMode(String compressionMode) {
        this.compressionMode = compressionMode;
    }

    public String getOutputFormat() {
        return outputFormat;
    }

    public void setOutputFormat(String outputFormat) {
        this.outputFormat = outputFormat;
    }

    public Double getSavings() {
        return savings;
    }

    public void setSavings(Double savings) {
        this.savings = savings;
    }

    public String getScore() {
        return score;
    }

    public void setScore(String score) {
        this.score = score;
    }
}