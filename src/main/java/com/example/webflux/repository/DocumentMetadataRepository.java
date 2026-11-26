package com.example.webflux.repository;

import com.example.webflux.model.DocumentMetadata;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Optional;

public interface DocumentMetadataRepository extends JpaRepository<DocumentMetadata, Long> {
    Optional<DocumentMetadata> findByFilenameAndChunkIndex(String filename, int chunkIndex);
    List<DocumentMetadata> findByFilename(String filename);

    /** 디스크 스캔(REST reindex) 경로 문서만 반환한다. MCP 적재 문서는 sourceClient != null 이므로 정리 대상에서 제외된다. */
    @Query("SELECT DISTINCT m.filename FROM DocumentMetadata m WHERE m.sourceClient IS NULL")
    List<String> findAllDistinctFilenames();

    @Transactional
    void deleteByFilename(String filename);

    /** 페이지 수가 줄어든 PDF 에서 사라진 페이지의 해시 행을 지운다. chunkIndex 는 page_number - 1 이다. */
    @Transactional
    void deleteByFilenameAndChunkIndexGreaterThanEqual(String filename, int chunkIndex);
}
