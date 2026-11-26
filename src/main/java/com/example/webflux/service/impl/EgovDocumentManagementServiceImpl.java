package com.example.webflux.service.impl;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import com.example.webflux.dto.DocumentStatusResponse;
import com.example.webflux.etl.readers.EgovDocxDocumentReader;
import com.example.webflux.etl.readers.EgovHwpDocumentReader;
import com.example.webflux.etl.readers.EgovHwpxDocumentReader;
import com.example.webflux.etl.readers.EgovMarkdownDocumentReader;
import com.example.webflux.etl.readers.EgovPdfDocumentReader;
import com.example.webflux.etl.readers.EgovTxtDocumentReader;
import com.example.webflux.etl.transformers.EgovContentFormatTransformer;
import com.example.webflux.etl.transformers.EgovDocumentChunkTransformer;
import com.example.webflux.etl.transformers.EgovPiiMaskingTransformer;
import com.example.webflux.etl.writers.EgovVectorStoreDocumentWriter;
import com.example.webflux.model.DocumentMetadata;
import com.example.webflux.repository.DocumentMetadataRepository;
import com.example.webflux.service.EgovDocumentManagementService;
import com.example.webflux.util.DocumentHashUtil;
import com.example.webflux.util.EgovStaleVectorCleaner;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class EgovDocumentManagementServiceImpl extends EgovAbstractServiceImpl implements EgovDocumentManagementService {

    private final EgovMarkdownDocumentReader markdownReader;
    private final EgovPdfDocumentReader pdfReader;
    private final EgovTxtDocumentReader txtReader;
    private final EgovDocxDocumentReader docxReader;
    private final EgovHwpDocumentReader hwpReader;
    private final EgovHwpxDocumentReader hwpxReader;
    private final EgovContentFormatTransformer contentFormatTransformer;
    private final EgovPiiMaskingTransformer piiMaskingTransformer;
    private final EgovDocumentChunkTransformer documentChunkTransformer;
    private final EgovVectorStoreDocumentWriter vectorStoreWriter;
    private final DocumentMetadataRepository metadataRepository;
    private final EgovStaleVectorCleaner staleVectorCleaner;
    private final Executor documentProcessingExecutor;

    private final AtomicBoolean isProcessing = new AtomicBoolean(false);
    private final AtomicInteger processedCount = new AtomicInteger(0);
    private final AtomicInteger totalCount = new AtomicInteger(0);
    private final AtomicInteger changedCount = new AtomicInteger(0);

    @Override
    public boolean isProcessing() {
        return isProcessing.get();
    }

    @Override
    public DocumentStatusResponse getStatusResponse() {
        return new DocumentStatusResponse(
                isProcessing.get(),
                processedCount.get(),
                totalCount.get(),
                changedCount.get());
    }

    /**
     * 기동 시 호출되는 진입점. 이미 진행 중이면 경고만 남기고 건너뛴다.
     *
     * 선점에 compareAndSet 을 쓰는 이유는 '확인' 과 '선점' 이 한 연산이어야 하기 때문이다.
     * isProcessing.get() 으로 확인한 뒤 따로 선점하면 그 사이에 다른 호출자가 먼저 선점할 수 있다.
     */
    @Override
    public CompletableFuture<Integer> loadDocumentsAsync() {
        log.info("ETL 파이프라인으로 문서 처리 시작");
        if (!isProcessing.compareAndSet(false, true)) {
            log.warn("이미 문서 처리가 진행 중입니다.");
            return CompletableFuture.completedFuture(0);
        }
        return startPipeline();
    }

    /**
     * ETL 파이프라인을 별도 스레드에서 시작한다.
     *
     * isProcessing 을 이미 선점한 호출자만 부른다. 선점 판정은 호출자에 둔다.
     * 거절했을 때 할 일이 경로마다 다르기 때문이다. 기동 경로는 건너뛰면 되고
     * REST 경로는 409 를 돌려줘야 한다.
     *
     * 플래그 해제는 아래 finally 한 곳이다.
     *
     *
     * 파이프라인의 구성 요소를 순서대로 이어 붙이는 일은 이 메서드가 한다.
     *
     * 리더 6개(md·pdf·txt·docx·hwp·hwpx)로 읽고, 해시 비교로 변경분만 고르고,
     * 포맷 정리와 청킹을 거쳐 라이터로 저장한다. 구성 요소는 모두 Component 라서
     * 주입으로 들어온다.
     */
    private CompletableFuture<Integer> startPipeline() {
        processedCount.set(0);
        totalCount.set(0);
        changedCount.set(0);

        return CompletableFuture.supplyAsync(() -> {
            try {
                // 1단계: 마크다운, PDF, 텍스트, DOCX, HWP, HWPX 문서 읽기
                List<Document> markdownDocuments = markdownReader.get();
                List<Document> pdfDocuments = pdfReader.get();
                List<Document> txtDocuments = txtReader.get();
                List<Document> docxDocuments = docxReader.get();
                List<Document> hwpDocuments = hwpReader.get();
                List<Document> hwpxDocuments = hwpxReader.get();

                List<Document> allDocuments = new ArrayList<>();
                allDocuments.addAll(markdownDocuments);
                allDocuments.addAll(pdfDocuments);
                allDocuments.addAll(txtDocuments);
                allDocuments.addAll(docxDocuments);
                allDocuments.addAll(hwpDocuments);
                allDocuments.addAll(hwpxDocuments);

                totalCount.set(allDocuments.size());
                log.info("총 {}개의 문서를 로드했습니다. (마크다운: {}개, PDF: {}개, 텍스트: {}개, DOCX: {}개, HWP: {}개, HWPX: {}개)",
                        allDocuments.size(), markdownDocuments.size(), pdfDocuments.size(), txtDocuments.size(),
                        docxDocuments.size(), hwpDocuments.size(), hwpxDocuments.size());

                // 디스크에서 삭제·이름이 변경된 파일의 벡터·메타데이터 정리
                // 현재 디스크에 존재하는 파일명 집합과 DB에 기록된 파일명 집합의 차집합을 구해 삭제
                cleanupOrphanDocuments(allDocuments);

                // 페이지 수가 줄어든 PDF 의 사라진 페이지 정리. 남은 페이지가 모두 '변경 없음'이어도
                // 해야 하므로 아래 변경 감지보다 먼저 한다.
                removeDroppedPdfPages(allDocuments);

                // 2단계: 변경된 문서만 거르기
                List<Document> changedDocuments = filterChangedDocuments(allDocuments);
                changedCount.set(changedDocuments.size());
                log.info("총 {}개의 문서 중 {}개의 변경된 문서를 처리합니다.",
                        allDocuments.size(), changedDocuments.size());

                if (changedDocuments.isEmpty()) {
                    log.info("변경된 문서가 없습니다. 인덱싱 작업을 건너뜁니다.");
                    return 0;
                }

                // 2-1단계: 새 청크를 저장하기 전에 변경된 문서의 구버전 벡터를 삭제
                // PDF는 페이지 단위(page_number), MD는 파일 전체(source)로 삭제 범위를 한정
                for (Document doc : changedDocuments) {
                    String src = (String) doc.getMetadata().get("source");
                    Object pageNum = doc.getMetadata().get("page_number");
                    if (pageNum instanceof Integer p) {
                        staleVectorCleaner.deleteBySourceAndPage(src, p);
                    } else {
                        staleVectorCleaner.deleteBySource(src);
                    }
                }

                // 3단계: 문서 형식 정규화
                log.info("문서 형식 정규화 시작");
                List<Document> normalizedDocuments = contentFormatTransformer.apply(changedDocuments);
                log.info("문서 형식 정규화 완료: {}개 문서", normalizedDocuments.size());

                // 3.5단계: 민감정보(PII) 마스킹 (청크 분할 전 전체 문서 단위로 적용, 기본 비활성)
                List<Document> maskedDocuments = piiMaskingTransformer.apply(normalizedDocuments);

                // 4단계: 문서 청크 분할
                log.info("문서 청크 분할 시작");
                List<Document> transformedDocuments = documentChunkTransformer.apply(maskedDocuments);
                log.info("문서 청크 분할 완료: {}개 청크 생성", transformedDocuments.size());

                // 5단계: 벡터 저장소에 저장
                log.info("벡터 저장소 저장 시작");
                vectorStoreWriter.accept(transformedDocuments);
                log.info("벡터 저장소 저장 완료");

                // 6단계: 처리된 문서 해시 저장 (Document 개별)
                for (Document document : changedDocuments) {
                    saveDocumentHash(document);
                }

                processedCount.set(transformedDocuments.size());
                log.info("문서 처리 완료: 원본 {}개 → 청크 {}개",
                    changedDocuments.size(), transformedDocuments.size());

                return transformedDocuments.size();

            } catch (Exception e) {
                log.error("문서 처리 중 오류 발생", e);
                throw new RuntimeException("문서 처리 중 오류 발생", e);
            } finally {
                isProcessing.set(false);
            }
        }, documentProcessingExecutor);
    }
    
    /**
     * REST 재인덱싱 진입점. 작업을 제출하고 완료를 기다리지 않는다.
     *
     * 선점에 compareAndSet 을 쓴다. isProcessing.get() 으로 확인만 하면 거의 동시에 들어온 두 요청이
     * 모두 체크를 통과할 가능성이 있다.
     *
     * 던지는 예외는 컨트롤러가 409 Conflict 로 바꾼다.
     */
    @Override
    public String reindexDocuments() {
        log.info("문서 재인덱싱 요청 수신");
        if (!isProcessing.compareAndSet(false, true)) {
            throw new IllegalStateException(
                    "이미 문서 인덱싱이 진행 중입니다. GET /api/documents/status 로 진행 상황을 확인하세요.");
        }

        startPipeline()
                .thenAccept(count -> log.info("재인덱싱 완료: {}개 청크 처리됨", count))
              .exceptionally(throwable -> {
                  log.error("재인덱싱 중 오류 발생", throwable);
                  return null;
              });

        return "문서 재인덱싱이 처리되었습니다.";
    }

    /**
     * 변경된 문서만 걸러낸다.
     * (filename, chunkIndex) 복합 키로 페이지 단위 변경을 감지한다.
     * - PDF: (report.pdf, 0), (report.pdf, 1), ... (report.pdf, 159)
     * - 마크다운: (guide.md, 0)
     */
    private List<Document> filterChangedDocuments(List<Document> documents) {
        return documents.stream()
                .filter(this::isDocumentChanged)
                .toList();
    }

    /**
     * 문서가 변경되었는지 확인한다.
     * filename = source 메타데이터 (원본 파일명)
     * chunkIndex = page_number - 1 (PDF), 0 (마크다운)
     */
    private boolean isDocumentChanged(Document document) {
        String content = document.getText();

        if (content == null || content.trim().isEmpty()) {
            return false;
        }

        String filename = (String) document.getMetadata().get("source");
        int chunkIndex = getChunkIndex(document);

        String newHash = DocumentHashUtil.calculateHash(content);

        try {
            Optional<DocumentMetadata> metadataOpt = metadataRepository.findByFilenameAndChunkIndex(filename, chunkIndex);

            if (metadataOpt.isPresent()) {
                String oldHash = metadataOpt.get().getContentHash();
                if (oldHash != null && oldHash.equals(newHash)) {
                    log.debug("문서 '{}' [{}] 변경 없음 (해시: {})", filename, chunkIndex, newHash);
                    return false;
                }
                log.info("문서 '{}' [{}] 해시 변경 감지 (기존: {} → 신규: {})", filename, chunkIndex, oldHash, newHash);
            } else {
                log.info("문서 '{}' [{}] 신규 문서 (저장된 해시 없음)", filename, chunkIndex);
            }
        } catch (Exception e) {
            log.error("문서 '{}' [{}] 해시 조회 중 오류 발생 - 변경으로 간주", filename, chunkIndex, e);
        }

        return true;
    }

    /**
     * 문서 처리 완료 후 해시값을 저장한다.
     * (filename, chunkIndex) 복합 키로 페이지마다 독립된 레코드에 저장한다.
     */
    private void saveDocumentHash(Document document) {
        String content = document.getText();

        if (content == null || content.trim().isEmpty()) {
            return;
        }

        String filename = (String) document.getMetadata().get("source");
        int chunkIndex = getChunkIndex(document);

        String newHash = DocumentHashUtil.calculateHash(content);

        try {
            Optional<DocumentMetadata> existing = metadataRepository.findByFilenameAndChunkIndex(filename, chunkIndex);

            DocumentMetadata metadata;
            if (existing.isPresent()) {
                metadata = existing.get();
                metadata.setContentHash(newHash);
                metadata.setIndexedAt(LocalDateTime.now());
            } else {
                // REST 경로(서버 파일시스템 인덱싱)는 클라이언트 출처와 요약이 없어 null 로 둔다
                metadata = new DocumentMetadata(null, filename, chunkIndex, newHash, LocalDateTime.now(), null, null);
            }

            metadataRepository.save(metadata);
            log.debug("문서 '{}' [{}] 해시 저장 완료: {}", filename, chunkIndex, newHash);
        } catch (Exception e) {
            log.error("문서 '{}' [{}] 해시 저장 실패", filename, chunkIndex, e);
        }
    }

    /**
     * 디스크에서 삭제·이름이 변경된 파일의 벡터·메타데이터를 일괄 정리
     *
     * 디스크에 없는데 DB에는 기록이 남아 있는 파일(orphan)을 찾아
     * document_metadata와 vector_store에서 해당 파일 레코드를 모두 삭제한다.
     * 서버 기동 시 자동 적재와 REST 재인덱싱이 같은 파이프라인이라 둘 다 이 정리를 거친다.
     * MCP 적재 경로는 명시적 파일명을 받으므로 이 정리를 거치지 않는다.
     */
    private void cleanupOrphanDocuments(List<Document> diskDocuments) {
        try {
            // 현재 디스크에 존재하는 파일명 집합
            Set<String> diskFilenames = diskDocuments.stream()
                    .map(d -> (String) d.getMetadata().get("source"))
                    .filter(source -> source != null)
                    .collect(Collectors.toSet());

            // DB에 기록된 파일명 집합
            List<String> dbFilenames = metadataRepository.findAllDistinctFilenames();

            // DB에는 있지만 디스크에는 없는 파일명
            List<String> orphans = dbFilenames.stream()
                    .filter(filename -> !diskFilenames.contains(filename))
                    .toList();

            if (orphans.isEmpty()) {
                log.debug("[정리] 디스크에서 사라진 문서가 없습니다.");
                return;
            }

            log.info("[정리] 디스크에 없는 파일 {}개의 벡터·메타데이터를 삭제합니다: {}", orphans.size(), orphans);
            for (String filename : orphans) {
                try {
                    staleVectorCleaner.deleteBySource(filename);
                    metadataRepository.deleteByFilename(filename);
                    log.info("[정리] {} 삭제 완료", filename);
                } catch (Exception e) {
                    log.error("[정리] {} 삭제 실패: {}", filename, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("[정리] 정리 중 오류가 발생했습니다. 이 오류는 무시하고 인덱싱을 계속합니다: {}", e.getMessage());
        }
    }

    /**
     * 갱신된 PDF 의 페이지 수가 이전보다 줄었으면, 사라진 뒤쪽 페이지의 벡터와 해시 행을 지운다.
     *
     * 변경 감지와 2-1단계 삭제는 지금 읽힌 페이지만 다루므로, 예전 파일에만 있던 페이지는
     * 여기서 지우지 않으면 검색 결과에 계속 나온다. page_number 가 없는 형식은 해당이 없다.
     */
    private void removeDroppedPdfPages(List<Document> diskDocuments) {
        Map<String, Integer> lastPageBySource = diskDocuments.stream()
                .filter(d -> d.getMetadata().get("source") instanceof String
                        && d.getMetadata().get("page_number") instanceof Integer)
                .collect(Collectors.toMap(
                        d -> (String) d.getMetadata().get("source"),
                        d -> (Integer) d.getMetadata().get("page_number"),
                        Math::max));

        for (Map.Entry<String, Integer> entry : lastPageBySource.entrySet()) {
            try {
                int removed = staleVectorCleaner.deletePagesAfter(entry.getKey(), entry.getValue());
                metadataRepository.deleteByFilenameAndChunkIndexGreaterThanEqual(entry.getKey(), entry.getValue());
                if (removed > 0) {
                    log.info("[정리] {} - 페이지 수 감소, {}페이지 뒤 데이터 삭제", entry.getKey(), entry.getValue());
                }
            } catch (Exception e) {
                log.error("[정리] {} 페이지 정리 실패: {}", entry.getKey(), e.getMessage());
            }
        }
    }

    /**
     * Document의 chunk index를 결정한다.
     * PDF: page_number 메타데이터(1-based) → 0-based로 변환
     * 마크다운: page_number 없음 → 0
     */
    private int getChunkIndex(Document document) {
        Object pageNumber = document.getMetadata().get("page_number");
        if (pageNumber instanceof Integer) {
            return (Integer) pageNumber - 1;
        }
        return 0;
    }

}
