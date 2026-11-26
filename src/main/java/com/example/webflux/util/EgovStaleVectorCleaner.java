package com.example.webflux.util;

import java.util.List;

import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * vector_store 테이블에서 구버전 벡터를 삭제하는 유틸리티.
 *
 * PgVectorStore.add()는 INSERT만 수행하므로, 
 * 파일 내용이 바뀐 후 재인덱싱 시 기존 벡터를 먼저 제거하지 않으면 바뀐 내용과 이전 내용의 청크가 함께 검색된다.
 * add() 직전에 이 클래스의 메서드를 호출하여 내용이 바뀌기 전에 만든 벡터를 먼저 삭제한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EgovStaleVectorCleaner {

    private final JdbcTemplate jdbcTemplate;
    private final PgVectorStore pgVectorStore;

    @Value("${spring.ai.vectorstore.pgvector.table-name:vector_store}")
    private String vectorStoreTable;

    /**
     * 파일명 기준으로 해당 파일의 벡터 전체를 삭제한다.
     * 마크다운 파일 갱신, MCP 적재 경로(파일 전체 재전송) 시 사용.
     */
    public int deleteBySource(String filename) {
        List<String> ids = jdbcTemplate.queryForList(
                "SELECT id::text FROM " + vectorStoreTable + " WHERE metadata->>'source' = ?",
                String.class, filename);
        return deleteAndLog(ids, filename, null);
    }

    /**
     * PDF 페이지 단위로 해당 페이지의 벡터만 삭제한다.
     * REST reindex 경로에서 변경된 PDF 페이지만 재처리할 때 사용.
     */
    public int deleteBySourceAndPage(String filename, int pageNumber) {
        List<String> ids = jdbcTemplate.queryForList(
                "SELECT id::text FROM " + vectorStoreTable
                        + " WHERE metadata->>'source' = ? AND (metadata->>'page_number')::int = ?",
                String.class, filename, pageNumber);
        return deleteAndLog(ids, filename, pageNumber);
    }

    /**
     * PDF 의 lastPageNumber 보다 뒤 페이지 벡터를 삭제한다.
     *
     * 갱신된 PDF 의 페이지 수가 줄었을 때 사용한다. 변경 감지와 페이지 단위 삭제는 지금 읽힌 페이지만
     * 다루므로, 예전 파일에만 있던 뒤쪽 페이지의 벡터는 이 메서드로 지우지 않으면 검색 결과에 계속 나온다.
     */
    public int deletePagesAfter(String filename, int lastPageNumber) {
        List<String> ids = jdbcTemplate.queryForList(
                "SELECT id::text FROM " + vectorStoreTable
                        + " WHERE metadata->>'source' = ? AND (metadata->>'page_number')::int > ?",
                String.class, filename, lastPageNumber);
        if (!ids.isEmpty()) {
            pgVectorStore.delete(ids);
            log.info("[구버전 벡터 삭제] {} - {}페이지 뒤의 벡터 {}개 삭제 (페이지 수 감소)",
                    filename, lastPageNumber, ids.size());
        }
        return ids.size();
    }

    private int deleteAndLog(List<String> ids, String filename, Integer pageNumber) {
        if (ids.isEmpty()) {
            return 0;
        }
        pgVectorStore.delete(ids);
        if (pageNumber != null) {
            log.info("[구버전 벡터 삭제] {} 페이지 {} - {}개 삭제", filename, pageNumber, ids.size());
        } else {
            log.info("[구버전 벡터 삭제] {} - {}개 삭제", filename, ids.size());
        }
        return ids.size();
    }
}
