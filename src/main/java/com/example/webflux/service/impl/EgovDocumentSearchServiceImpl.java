package com.example.webflux.service.impl;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.ai.document.Document;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.webflux.model.DocumentMetadata;
import com.example.webflux.repository.DocumentMetadataRepository;
import com.example.webflux.service.EgovDocumentSearchService;
import com.example.webflux.util.EgovRrfFusion;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
@Slf4j
@RequiredArgsConstructor
public class EgovDocumentSearchServiceImpl extends EgovAbstractServiceImpl implements EgovDocumentSearchService {

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;
    private final PlatformTransactionManager transactionManager;
    private final DocumentMetadataRepository documentMetadataRepository;

    @Value("${app.rag.similarity-threshold:0.30}")
    private double similarityThreshold;

    @Value("${app.rag.top-k:5}")
    private int topK;

    @Value("${app.rag.max-content-length:2000}")
    private int maxContentLength;

    @Value("${app.rag.hybrid.enabled:false}")
    private boolean hybridEnabled;

    @Value("${app.rag.hybrid.weight.dense:1.0}")
    private double hybridDenseWeight;

    @Value("${app.rag.hybrid.weight.lexical:1.0}")
    private double hybridLexicalWeight;

    @Value("${spring.ai.vectorstore.pgvector.table-name:vector_store}")
    private String vectorStoreTable;

    // pg_trgm.word_similarity_threshold 기본값 0.6은 너무 엄격하다. 트랜잭션 범위로 0.30 으로 설정한다
    @Value("${app.rag.hybrid.lexical.word-similarity-threshold:0.30}")
    private double wordSimilarityThreshold;

    /**
     * 벡터 유사도 기반 문서 검색. 이 서버의 RAG 진입점이다.
     *
     * 설명에는 도구가 '무엇을 하는지'만 적는다. '언제 부를지'는 모델의 판단에 맡긴다.
     * 도구 설명은 이 서버를 쓰는 모든 클라이언트의 모든 요청 프롬프트에 실리므로, 
     * 호출 시점은 그 클라이언트가 정한다. 도구 설명에는 행동 지시를 넣지 않는다.
     *
     * app.rag.hybrid.enabled 에 따라 dense 단독과 dense+lexical RRF 융합으로 갈린다.
     */
    @Override
    @McpTool(name = "searchDocuments",
          description = "이 서버에 인덱싱된 문서에서 질의와 관련된 내용을 벡터 유사도로 검색해 반환합니다. "
                  + "인덱싱된 문서의 내용에 근거한 답이 필요할 때 사용합니다. "
                  + "결과가 비어 있으면 describeKnowledgeBase로 어떤 문서가 인덱싱돼 있는지 확인할 수 있습니다.",
          annotations = @McpTool.McpAnnotations(
                  readOnlyHint = true,      // 부작용 없는 조회. 클라이언트의 위험 작업 판별 대상에서 빠진다
                  destructiveHint = false,
                  openWorldHint = false))    // 서버 내부 벡터 DB만 조회, 외부 시스템 미접촉
    public Mono<String> searchDocuments(
            @McpToolParam(description = "검색 질의문. 구체적일수록 정확도가 높아집니다.") String query
    ) {
        log.info("문서 검색 ({}) - 질의: {}", hybridEnabled ? "하이브리드" : "dense", query);

        return Mono.fromCallable(() -> {
            List<Document> results = hybridEnabled ? hybridSearch(query) : denseSearch(query);

            if (results == null || results.isEmpty()) {
                log.info("검색 결과 없음 - 질의: {}", query);
                return "관련 문서를 찾을 수 없습니다. "
                        + "다른 키워드로 재시도하거나 describeKnowledgeBase로 인덱싱 현황을 확인하세요.";
            }

            log.info("검색 결과 {}건 - 질의: {}", results.size(), query);
            if (log.isDebugEnabled()) {
                for (int i = 0; i < results.size(); i++) {
                    Document d = results.get(i);
                    log.debug("  [{}] 출처: {} (유사도: {})",
                            i + 1,
                            d.getMetadata().getOrDefault("source", "unknown"),
                            d.getScore() != null ? String.format("%.3f", d.getScore()) : "-");
                }
            }

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("검색 결과 %d건 (모드: %s)\n\n",
                    results.size(), hybridEnabled ? "하이브리드(dense+lexical RRF)" : "dense"));

            for (int i = 0; i < results.size(); i++) {
                Document doc = results.get(i);
                String content = doc.getText();
                if (content != null && content.length() > maxContentLength) {
                    content = content.substring(0, maxContentLength) + "...";
                }
                String source = (String) doc.getMetadata().getOrDefault("source", "unknown");
                double score = doc.getScore() != null ? doc.getScore() : 0.0;

                sb.append(String.format("[%d] 출처: %s (유사도: %.3f)\n%s\n\n", i + 1, source, score, content));
            }

            return sb.toString();

        }).subscribeOn(Schedulers.boundedElastic())
          .onErrorReturn("문서 검색 중 오류가 발생했습니다.");
    }

    /**
     * 지식 베이스 현황 조회.
     */
    @Override
    @McpTool(name = "describeKnowledgeBase",
          description = "이 서버에 인덱싱된 문서의 목록과 각 문서의 청크 수를 반환합니다.",
          annotations = @McpTool.McpAnnotations(
                  readOnlyHint = true,
                  destructiveHint = false,
                  openWorldHint = false))
    public Mono<String> describeKnowledgeBase() {
        log.info("지식 베이스 현황 조회");

        return Mono.fromCallable(() -> {
            // 실제 검색 대상은 벡터 테이블이므로 집계도 벡터 테이블 기준으로 한다.
            // document_metadata는 변경 감지용 해시를 원본 문서/PDF 페이지 단위로 보관하므로 청크 수와 다르다.
            Map<String, Long> fileChunks = countChunksByFile();
            long totalChunks = fileChunks.values().stream().mapToLong(Long::longValue).sum();

            if (totalChunks == 0) {
                return "인덱싱된 문서가 없습니다. 먼저 문서를 지식 베이스에 적재하세요.";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("=== RAG 지식 베이스 현황 ===\n");
            sb.append(String.format("총 파일 수: %d개 | 총 청크 수: %d개\n\n", fileChunks.size(), totalChunks));
            sb.append("파일 목록 (파일명 - 청크 수):\n");
            fileChunks.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .forEach(e -> sb.append(String.format("  - %s (%d 청크)\n", e.getKey(), e.getValue())));
            sb.append("\nsearchDocuments 도구로 이 파일들의 내용을 검색할 수 있습니다.");

            return sb.toString();

        }).subscribeOn(Schedulers.boundedElastic())
          .onErrorReturn("지식 베이스 조회 중 오류가 발생했습니다.");
    }

    /**
     * 파일 하나의 개요를 반환한다. URI 템플릿 리소스 resource://documents/{filename} 전용이다.
     *
     * 원본 파일을 주지 않는 이유: MCP ingestDocument 로 적재한 문서는 서버 디스크에 원본이
     * 남지 않는다(의도된 설계). 그래서 두 적재 경로 모두에 존재하는 것만 모아 보여 준다.
     * document_metadata 의 요약과 vector_store 의 청크 수다.
     *
     * 요약은 개요만 담는다. 전문이 필요하면 searchDocuments 로 질의한다.
     * 상한은 DocumentMetadata.summary 의 @Column(length = 2000).
     *
     * 요약이 null 인 것은 REST 재인덱싱으로 들어온 문서다. 그 경로는 Sampling 을 거치지
     * 않는다(EgovDocumentManagementServiceImpl)
     * MCP 적재 경로는 실패해도 null 을 쓰지 않고 '(Sampling 미지원)' 같은 괄호 문구를 남긴다.
     *
     * 요약은 파일 단위 값인데 document_metadata 가 청크 행마다 같은 값을 복사해 두므로 첫 행만 쓴다.
     */
    @Override
    public Mono<String> describeDocument(String filename) {
        return Mono.fromCallable(() -> {
            String resolved = resolveFilename(filename);
            if (resolved == null) {
                log.info("문서 개요 조회 - 대상 없음");
                return "해당 문서가 인덱싱돼 있지 않습니다. "
                        + "describeKnowledgeBase 도구 또는 resource://documents/index 로 목록을 확인하세요.";
            }

            List<DocumentMetadata> rows = documentMetadataRepository.findByFilename(resolved);
            DocumentMetadata head = rows.get(0);
            long chunkCount = countChunksByFile().getOrDefault(resolved, 0L);

            log.info("문서 개요 조회 - {} ({}개 청크)", resolved, chunkCount);

            String origin = head.getSourceClient() != null
                    ? "MCP ingestDocument (" + head.getSourceClient() + ")"
                    : "서버 REST 재인덱싱";
            String summary = head.getSummary() != null
                    ? head.getSummary()
                    : "(요약 없음 - 서버 REST 재인덱싱은 요약을 만들지 않습니다)";

            // 개행은 %n 으로만 쓴다. 이 메서드의 다른 포맷과 일관되고 OS 줄바꿈을 따른다.
            return String.format(
                    "=== %s ===%n"
                    + "청크 수: %d개 | 원본 단위: %d개 | 적재 시각: %s%n"
                    + "적재 경로: %s%n%n"
                    + "요약:%n%s%n%n"
                    + "전문이 필요하면 searchDocuments 도구로 질의하세요. 이 리소스는 개요만 제공합니다.",
                    resolved, chunkCount, rows.size(), head.getIndexedAt(), origin, summary);

        }).subscribeOn(Schedulers.boundedElastic())
          .onErrorReturn("문서 개요 조회 중 오류가 발생했습니다.");
    }

    @Override
    public Mono<List<String>> listIndexedFilenames() {
        return Mono.fromCallable(() -> List.copyOf(countChunksByFile().keySet()))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorReturn(List.of());
    }

    // 이하 내부 보조 메서드

    /**
     * URI 에서 꺼낸 파일명으로 실제 적재 파일명을 찾는다. 없으면 null.
     *
     * 받은 값 그대로와 퍼센트 디코딩한 값을 둘 다 시도한다. 클라이언트가 URI 를
     * 만드는 방식이 제각각이기 때문이다. 공백은 URI 에서 유효한 문자가 아니라
     * 인코딩하는 클라이언트가 있고, 그대로 보내는 클라이언트도 있다.
     * 이 프로젝트의 문서는 한글·공백·괄호가 섞인 이름이 많아(예: 01.실행환경_교육교재(Spring AI).pdf)
     * 한쪽만 보면 조회가 어긋난다.
     */
    private String resolveFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return null;
        }
        if (!documentMetadataRepository.findByFilename(filename).isEmpty()) {
            return filename;
        }
        String decoded = urlDecode(filename);
        if (!decoded.equals(filename) && !documentMetadataRepository.findByFilename(decoded).isEmpty()) {
            return decoded;
        }
        return null;
    }

    /** 디코딩에 실패하면(잘못된 % 시퀀스 등) 원본을 그대로 돌려준다. */
    private String urlDecode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        }
        catch (IllegalArgumentException e) {
            return value;
        }
    }


    /**
     * 벡터 테이블에서 파일별 실제 청크 수를 집계한다.
     * 
     * 한 페이지가 여러 청크로 나뉘거나 너무 짧아 임베딩에서 제외되는 경우가 있어 실제 벡터 청크 수와 다르다.
     *
     * 집계 실패 시 빈 맵을 반환한다(도구 호출 자체를 실패시키지 않는다)
     */
    private Map<String, Long> countChunksByFile() {
        String sql = "SELECT metadata->>'source' AS filename, COUNT(*) AS chunk_count FROM "
                + vectorStoreTable + " GROUP BY 1";
        try {
            Map<String, Long> result = new LinkedHashMap<>();
            jdbcTemplate.query(sql, rs -> {
                String filename = rs.getString("filename");
                result.put(filename != null ? filename : "(출처 미상)", rs.getLong("chunk_count"));
            });
            return result;
        }
        catch (Exception e) {
            log.warn("파일별 청크 수 집계 실패 - 빈 목록으로 응답: {}", e.getMessage());
            return Map.of();
        }
    }

    /**
     * dense 채널. 어드바이저를 쓰지 않는다.
     *
     * Spring AI 의 QuestionAnswerAdvisor·RetrievalAugmentationAdvisor 는 ChatModel 호출을
     * 감싸는 ChatClient 장치다. 이 서버에는 둘 다 없다. 검색 결과를 프롬프트에 넣고 LLM 을
     * 부르는 일은 클라이언트가 한다. 서버는 청크만 돌려주므로 감쌀 호출이 없다.
     *
     * 그래서 어드바이저가 해 주던 단계 중 이 서버가 담당하는 것은 검색과 결과 융합이다
     * (융합은 EgovRrfFusion 으로 직접 한다). 질의 변환·확장·후처리(재랭킹)는 없다.
     * 넣으려면 어드바이저를 찾지 말고 이 클래스의 메서드로 끼워야 한다.
     */
    private List<Document> denseSearch(String query) {
        return vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(query)
                        .topK(topK)
                        .similarityThreshold(similarityThreshold)
                        .build()
        );
    }

    /**
     * dense + lexical 채널을 RRF로 융합한 하이브리드 검색.
     *
     * lexical 채널 실패 시 dense 결과만 반환한다(graceful degrade).
     * 
     * 실패 원인에 따른 영향은 다음과 같다.
     * pg_trgm 확장 미설치: `%` 연산자 없음 → SQL 예외 → dense 검색 결과만 사용
     * GIN 인덱스 없음: 순차 스캔으로 동작한다. 기능은 정상이고 성능만 떨어진다
     */
    private List<Document> hybridSearch(String query) {
        // 1) dense 채널
        List<Document> denseResults = denseSearch(query);

        // 2) lexical 채널. SQL 예외가 나면 dense 검색 결과만 사용한다
        List<String> lexicalRanking;
        try {
            lexicalRanking = lexicalSearch(query);
        } catch (Exception e) {
            log.warn("lexical 검색 실패 - dense 결과만 사용합니다. 원인: {}", e.getMessage());
            return denseResults;
        }

        // 3) dense 순위 및 키→Document 매핑 구성
        Map<String, Document> byKey = new LinkedHashMap<>();
        List<String> denseRanking = new ArrayList<>(denseResults.size());
        for (Document doc : denseResults) {
            String key = doc.getId();
            denseRanking.add(key);
            byKey.putIfAbsent(key, doc);
        }

        // 4) RRF 융합
        List<String> fusedKeys = EgovRrfFusion.fuse(
                denseRanking, lexicalRanking,
                hybridDenseWeight, hybridLexicalWeight,
                EgovRrfFusion.DEFAULT_K, topK);

        // lexical 단독 문서(dense에 없는 키)는 Document 본문이 없으므로 제외하고,
        // 융합 점수로 재정렬된 dense 문서만 반환한다. lexical 신호는 순위 가중치로 반영된다.
        List<Document> result = new ArrayList<>(fusedKeys.size());
        for (String key : fusedKeys) {
            Document doc = byKey.get(key);
            if (doc != null) {
                result.add(doc);
            }
        }

        log.debug("하이브리드 검색 완료 - dense: {}, lexical: {}, 융합: {}",
                denseResults.size(), lexicalRanking.size(), result.size());
        return result;
    }

    /**
     * pg_trgm %> (word_similarity) 연산자를 이용한 lexical 검색.
     *
     * similarity(%) 연산자는 질문·문서 trigram 합집합으로 나눠 4000자 청크에서
     * 유사도가 0.006~0.058로 폭락한다. %>(word_similarity)는 문서 내에서 질문과
     * 가장 잘 맞는 구간만 비교하므로 문서 길이에 강건하다 (측정 recall@3 = 0.80, 임계값 0.30).
     *
     * pg_trgm.word_similarity_threshold 기본값(0.6)은 너무 엄격하므로
     * set_config(is_local=true)로 트랜잭션 범위에서만 0.30으로 조정한다.
     * 트랜잭션 종료 시 자동 복원되어 커넥션 풀 누수가 없다.
     *
     * 연산자 방향: content %> ? = word_similarity(query, content) > threshold
     * ORDER BY: word_similarity(?, content) 로, 질문이 앞이고 문서가 뒤다
     *
     * @return 융합 키 역할을 하는 문서 UUID 문자열 리스트 (유사도 내림차순)
     */
    private List<String> lexicalSearch(String query) {
        List<String> ids = new TransactionTemplate(transactionManager).execute(status -> {
            // is_local=true: 이 트랜잭션 안에서만 임계값 적용, 커밋 시 자동 복원
            jdbcTemplate.queryForObject(
                    "SELECT set_config('pg_trgm.word_similarity_threshold', ?, true)",
                    String.class,
                    Double.toString(wordSimilarityThreshold));
            String sql = "SELECT id::text FROM " + vectorStoreTable
                    + " WHERE content %> ? ORDER BY word_similarity(?, content) DESC LIMIT ?";
            return jdbcTemplate.queryForList(sql, String.class, query, query, topK);
        });
        return ids != null ? ids : List.of();
    }
}
