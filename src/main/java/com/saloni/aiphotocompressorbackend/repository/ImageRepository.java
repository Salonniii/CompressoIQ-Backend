package com.saloni.aiphotocompressorbackend.repository;

import com.saloni.aiphotocompressorbackend.entity.ImageEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ImageRepository extends JpaRepository<ImageEntity, Long> {
    List<ImageEntity> findByUserIdOrderByIdDesc(Long userId);
}