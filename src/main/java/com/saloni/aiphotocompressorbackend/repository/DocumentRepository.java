package com.saloni.aiphotocompressorbackend.repository;

import com.saloni.aiphotocompressorbackend.entity.DocumentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DocumentRepository extends JpaRepository<DocumentEntity, Long> {
    List<DocumentEntity> findByUserIdOrderByIdDesc(Long userId);
}