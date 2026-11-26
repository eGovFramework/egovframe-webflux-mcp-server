package com.example.webflux.etl.writers;

import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentWriter;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 변환이 끝난 청크를 PgVector 에 저장한다. Spring AI ETL 의 쓰기 단계다.
 *
 * DocumentWriter 는 Consumer<List<Document>> 를 상속하므로 호출 지점은 accept() 다.
 *
 *
 * 주의 - 저장 경로가 둘이다
 *
 * 이 라이터는 REST 재인덱싱 경로에서만 호출된다
 * (EgovDocumentManagementServiceImpl.startPipeline).
 * MCP 적재(ingestDocument)는 PgVectorStore.add() 를 직접 호출한다
 * (EgovDocumentIngestServiceImpl). 저장 직전에 공통으로 걸어야 하는 처리가 생기면
 * 여기에만 넣어서는 MCP 경로에 적용되지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EgovVectorStoreDocumentWriter implements DocumentWriter {

    private final PgVectorStore pgVectorStore;

    @Override
    public void accept(List<Document> documents) {
        log.info("벡터 저장소에 {}개 문서 저장 시작", documents.size());

        if (documents.isEmpty()) {
            log.warn("저장할 문서가 없습니다.");
            return;
        }

        try {
            pgVectorStore.add(documents);
            log.info("벡터 저장소에 {}개 문서 저장 완료", documents.size());
        } catch (Exception e) {
            log.error("벡터 저장소 저장 중 오류 발생", e);
            throw new RuntimeException("벡터 저장소 저장 중 오류 발생", e);
        }
    }
}
