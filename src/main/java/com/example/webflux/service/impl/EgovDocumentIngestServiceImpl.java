package com.example.webflux.service.impl;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springaicommunity.mcp.context.McpAsyncRequestContext;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;

import com.example.webflux.etl.readers.EgovDocxTextExtractor;
import com.example.webflux.etl.readers.EgovHwpStructuredExtractor;
import com.example.webflux.etl.readers.EgovHwpxTextExtractor;
import com.example.webflux.etl.transformers.EgovContentFormatTransformer;
import com.example.webflux.etl.transformers.EgovDocumentChunkTransformer;
import com.example.webflux.etl.transformers.EgovPiiMaskingTransformer;
import com.example.webflux.model.DocumentMetadata;
import com.example.webflux.repository.DocumentMetadataRepository;
import com.example.webflux.service.EgovDocumentIngestService;
import com.example.webflux.util.DocumentHashUtil;
import com.example.webflux.util.DocumentIngestFailedException;
import com.example.webflux.util.DocumentOverwriteForbiddenException;
import com.example.webflux.util.EgovFileSignatureValidator;
import com.example.webflux.util.FileSignatureMismatchException;
import com.example.webflux.util.EgovStaleVectorCleaner;
import com.example.webflux.util.PromptInjectionDetectedException;
import com.example.webflux.util.EgovPromptInjectionDetector;
import com.example.webflux.util.UnsupportedFileTypeException;

import io.modelcontextprotocol.spec.McpSchema;
import kr.dogfoot.hwplib.reader.HWPReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 클라이언트 로컬 문서를 수신하여 서버 벡터 DB에 임베딩하는 MCP 도구 서비스
 *
 * MCP 기능 활용:
 *   - Progress: 파이프라인 각 단계(읽기 → 요약 → 청킹 → 임베딩)를 클라이언트에 알림.
 *     단, 클라이언트가 요청 _meta에 progressToken을 담은 경우에만 실제로 전송된다.
 *     토큰이 없으면 ctx.progress(...)는 알림을 보내지 않고 빈 Mono 를 반환한다. 예외는 없고
 *     WARN 로그 "Progress notification not supported by the client!" 만 남는다
 *     (mcp-annotations 0.9.0 DefaultMcpAsyncRequestContext: progressToken 이 null 이면 경고 후 Mono.empty()).
 *     문구와 달리 클라이언트의 지원 여부가 아니라 요청에 토큰이 없다는 뜻이다.
 *     전용 클라이언트는 /ingest 화면이 만든 jobId 를 progressToken 으로 보내고
 *     (EgovDocumentIngestController), 받은 알림을 jobId 별 SSE 로 화면에 중계한다.
 *     토큰을 보내지 않는 범용 클라이언트에서는 진행률만 생략되고 적재는 정상 동작한다.
 *   - Sampling: 문서 요약 생성을 클라이언트의 LLM 에 위임.
 *     클라이언트가 자기 ChatModel 로 처리한다(전용 클라이언트 기본값은 Ollama, 설정으로
 *     OpenRouter 등으로 바뀐다). 클라이언트가 Sampling 을 지원하지 않으면 요약 없이 적재된다.
 *
 * 지원 형식: PDF (.pdf), DOCX (.docx), HWP (.hwp), HWPX (.hwpx), 마크다운 (.md), 텍스트 (.txt)
 *
 * 서버의 REST 재인덱싱 경로(POST /api/documents/reindex)와 지원 형식이 같다.
 * (EgovDocumentManagementServiceImpl 이 전용 리더 6종을 사용)
 *
 * 바이너리(PDF · DOCX · HWP · HWPX)는 Base64 로 실어 이 경로로 적재된다.
 * 전용 클라이언트의 `/ingest`가 파일을 바이트로 읽기 때문이다.
 * 도착한 바이트는 `EgovFileSignatureValidator` 가 확장자와 맞는지 검사한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EgovDocumentIngestServiceImpl extends EgovAbstractServiceImpl implements EgovDocumentIngestService {

    private final EgovContentFormatTransformer contentFormatTransformer;
    private final EgovPiiMaskingTransformer piiMaskingTransformer;
    private final EgovDocumentChunkTransformer documentChunkTransformer;
    private final PgVectorStore pgVectorStore;
    private final DocumentMetadataRepository metadataRepository;
    private final EgovStaleVectorCleaner staleVectorCleaner;

    private static final int PROGRESS_TOTAL_STEPS = 4;

    // MCP 적재 경로가 받는 확장자. REST 재인덱싱 경로와 같은 6종이다.
    //
    // 나중에 파일 종류가 추가될 경우이 목록만 고치면 안 되고 extractDocuments() 의 분기에도 넣어야 한다.
    private static final Set<String> SUPPORTED_EXTENSIONS =
            Set.of(".pdf", ".md", ".txt", ".docx", ".hwpx", ".hwp");

    // 파이프라인 내 rawDocuments + chunks + summary를 함께 전달하기 위한 내부 레코드
    private record IngestContext(List<Document> rawDocuments, List<Document> chunks, String summary) {}

    /**
     * 클라이언트 로컬 문서를 base64로 수신하여 서버 벡터 DB에 임베딩한다.
     *
     * 처리 흐름:
     *   0. 타 클라이언트 덮어쓰기 차단 / 해시 변경 감지 (미변경 페이지 스킵)
     *   1. 콘텐츠 수신 → 텍스트 추출 (PDF는 base64 디코딩, MD/TXT는 UTF-8 직접 처리)   [Progress 1/4]
     *   1.5 민감정보(PII) 마스킹 (기본 비활성. Sampling 으로 원문이 나가기 전에 적용)
     *   2. Sampling → 클라이언트 Ollama로 문서 요약 요청   [Progress 2/4]
     *   3. 콘텐츠 정규화 + 마스킹 재적용 + 청킹   [Progress 3/4]
     *   4. 변경 페이지의 구버전 벡터 삭제 후 임베딩 저장 + 메타데이터 저장   [Progress 4/4]
     *
     * @param ctx          MCP 컨텍스트 (Progress/Sampling 전송용)
     * @param jobId        작업 추적용 고유 ID (클라이언트가 SSE 구독에 사용)
     * @param filename     파일명 (예: document.pdf, guide.md)
     * @param content      파일 내용 (.md·.txt는 UTF-8 텍스트 그대로, .pdf·.docx·.hwp·.hwpx는 Base64 인코딩)
     * @param mimeType     MIME 타입 (참고값. 형식은 filename 확장자로 먼저 판정한다)
     */
    @Override
    @McpTool(
        name = "ingestDocument",
        description = "클라이언트 로컬 문서(PDF/DOCX/HWP/HWPX/MD/TXT)를 서버 RAG 벡터 DB에 임베딩합니다. "
                + "content 전달 방식이 형식에 따라 다릅니다: 텍스트(text/markdown·text/plain)는 UTF-8 그대로, 바이너리(PDF·DOCX·HWP·HWPX)는 Base64 인코딩 문자열. "
                + "VS Code·Claude Desktop 등 범용 MCP 클라이언트에서 모델이 파일을 읽어 content 를 채우는 경우에는 텍스트 형식(.md, .txt)만 사용하세요. "
                + "PDF·DOCX·HWP·HWPX의 Base64 문자열을 모델이 다시 써서 보내면 바이트가 원본과 달라지기 쉽고, 서버는 파일 시그니처가 확장자와 맞지 않으면 적재를 거부합니다. "
                + "대안은 두 가지입니다: (1) 서버 REST 재인덱싱. 파일을 서버의 app.document.base-path 디렉터리에 두고 POST /api/documents/reindex 호출. 이 경로는 PDF 외에 .docx·.hwp·.hwpx도 처리합니다. (2) 전용 클라이언트(egovframe-webflux-mcp-client)의 /ingest 화면 업로드(PDF/DOCX/HWP/HWPX/MD/TXT). "
                + "주의: 이 도구는 파일이 아니라 content 문자열을 받습니다. 그 문자열을 누가 만드는지에 따라 원본 충실성이 갈립니다. "
                + "(가) 코드가 파일 바이트를 읽어 채우는 경우입니다. 위 대안 두 가지가 여기에 해당하며 원본 그대로 전달됩니다. "
                + "(나) 모델이 파일을 읽어 출력으로 채우는 경우입니다. 모델이 내용을 다시 쓰므로 원본과 달라질 수 있습니다. 전용 클라이언트의 대화 경로도 여기에 해당하므로, 클라이언트 종류가 아니라 호출 경로의 문제입니다. "
                + "(나)에서는 문자 수가 원본과 정확히 일치해도 내용이 다를 수 있어(측정 결과: 길이 동일, 내용 해시 불일치) 글자 수를 세어 확인할 수 없습니다. 원본 그대로 적재해야 하면 위 두 대안을 사용하세요. "
                + "임베딩 진행률은 클라이언트가 요청에 progressToken을 포함한 경우에만 Progress 알림으로 전송됩니다. "
                + "jobId는 로그 추적용 식별자입니다.",
        annotations = @McpTool.McpAnnotations(
                readOnlyHint = false,
                destructiveHint = true,    // 변경된 페이지의 기존 벡터를 삭제한 뒤 다시 삽입
                openWorldHint = true)      // 서버 벡터 DB(PgVector)에 영향을 미치는 쓰기 작업
    )
    public Mono<String> ingestDocument(
            McpAsyncRequestContext ctx,
            @McpToolParam(description = "작업 추적용 고유 ID (UUID 권장)") String jobId,
            @McpToolParam(description = "파일명 (예: document.pdf, guide.md)") String filename,
            @McpToolParam(description = "파일 내용. .md·.txt는 UTF-8 텍스트 그대로, .pdf·.docx·.hwp·.hwpx는 Base64 인코딩 문자열") String content,
            @McpToolParam(description = "MIME 타입 (예: text/markdown, text/plain, application/pdf). 형식은 filename 의 확장자로 먼저 판정합니다") String mimeType
    ) {
        String clientId = ctx.clientInfo() != null ? ctx.clientInfo().name() : "unknown";
        log.info("[적재] 요청 수신 - jobId: {}, filename: {}, mimeType: {}, clientId: {}",
                jobId, filename, mimeType, clientId);

        // 수신한 content 의 길이. 모든 형식 · 모든 호출 경로에 걸린다.
        log.info("[적재][{}] content 수신 - {}자 ({})", jobId, content.length(),
                isTextMimeType(mimeType, filename) ? "텍스트" : "Base64");

        return Mono.fromCallable(() -> {
            // 0단계 전: 지원 형식 검증. 미지원 확장자는 조기 거부
            validateFileType(filename, jobId);

            // 0단계: 타 클라이언트(또는 REST 경로)가 적재한 문서의 덮어쓰기 차단
            checkOverwriteOwnership(filename, clientId, jobId);

            // 1단계: mimeType에 따라 content 처리 (텍스트: UTF-8 변환, 바이너리: base64 디코딩)
            byte[] fileBytes = isTextMimeType(mimeType, filename)
                    ? content.getBytes(StandardCharsets.UTF_8)
                    : decodeBase64(content, jobId, filename);

            // 1단계 후: 바이트가 확장자에 맞는 형식인지 검사.
            //
            // Base64 디코딩은 문법만 본다. 범용 MCP 클라이언트에서는 LLM 이 content 를 채우므로
            // LLM이 만들어낸 실제로는 틀린 문자열도 디코딩에 성공한다. 검사가 없으면 그 바이트가
            // 파서로 넘어가고, 운이 나쁘면 예외 없이 색인되어 버린다.
            EgovFileSignatureValidator.validate(fileBytes, filename, jobId);

            List<Document> rawDocuments = extractDocuments(fileBytes, filename, mimeType, clientId);
            log.info("[적재][{}] 텍스트 추출 완료 - {}페이지", jobId, rawDocuments.size());

            // 자연어 프롬프트 인젝션 의심 패턴 검사
            checkPromptInjection(rawDocuments, jobId, filename);

            // PDF 페이지 수가 줄었으면 사라진 뒤쪽 페이지를 정리한다. 아래 변경 감지와 4단계 삭제는
            // 지금 읽힌 페이지만 다루므로, 남은 페이지가 모두 '변경 없음'이어도 여기서 지워야 한다.
            removeDroppedPdfPages(filename, rawDocuments, jobId);

            // SHA-256 해시 비교로 변경된 페이지만 선별. 변경되지 않은 페이지는 다시 임베딩하지 않는다
            List<Document> changedDocuments = filterChangedDocuments(rawDocuments, filename);
            log.info("[적재][{}] 변경 감지 - 전체: {}페이지, 변경: {}페이지",
                    jobId, rawDocuments.size(), changedDocuments.size());
            return changedDocuments;
        })
        .subscribeOn(Schedulers.boundedElastic())
        .flatMap(changedDocuments -> {
            if (changedDocuments.isEmpty()) {
                log.info("[적재][{}] 변경된 페이지 없음 - 임베딩 생략", jobId);
                return Mono.just(String.format(
                        "[%s] %s - 변경된 내용이 없어 임베딩을 생략합니다.", jobId, filename));
            }
            return runPipeline(ctx, changedDocuments, filename, clientId, jobId);
        })
        .doOnError(e -> log.error("[적재][{}] 처리 중 오류 발생 - {}", jobId, e.getMessage(), e))
        // 거부·실패는 반드시 예외로 끝낸다. Mono.just 로 사유 문자열을 돌려주면
        // MCP 응답의 isError 가 켜지지 않아 클라이언트가 성공으로 표시한다.
        .onErrorMap(e -> {
            String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            // 사유 메시지가 이미 [jobId] 로 시작하면(시그니처 검증 등) 접두를 겹치지 않는다.
            String message = reason.startsWith("[" + jobId + "]")
                    ? reason
                    : String.format("[%s] %s %s - %s", jobId, filename,
                            isRejection(e) ? "적재가 거부되었습니다" : "적재 중 오류가 발생했습니다", reason);
            return new DocumentIngestFailedException(message, e);
        });
    }

    /** 정책에 따른 거부인가(=서버가 의도적으로 막은 것), 아니면 예기치 못한 실패인가. */
    private boolean isRejection(Throwable e) {
        return e instanceof UnsupportedFileTypeException
                || e instanceof PromptInjectionDetectedException
                || e instanceof DocumentOverwriteForbiddenException
                || e instanceof FileSignatureMismatchException
                || e instanceof IllegalArgumentException;   // Base64 디코딩 실패
    }

    // 적재 파이프라인

    /**
     * 변경된 문서에 대해 Progress 1~4 파이프라인을 실행.
     * 호출자가 changedDocuments.isEmpty() 를 먼저 확인하므로 여기서는 비어 있지 않다.
     *
     * `ctx.progress(...).message(...)` 는 클라이언트 화면에 그려지는 문자열이다.
     * 이 클래스의 `log.info("[적재][{}] ... 완료")` 와는 별개이다.
     *
     * progress message의 발신 지점은 모두 다음 단계에 진입하기 직전이다.
     *
     * 클라이언트 화면은 이 문자열을 그대로 그린다.
     * 따라서 여기 문구를 고치면 전용 클라이언트 화면에 바로 반영된다.
     */
    private Mono<String> runPipeline(McpAsyncRequestContext ctx, List<Document> changedDocuments,
            String filename, String clientId, String jobId) {

        // 1.5단계: 정규화 후 민감정보(PII) 마스킹 (마스킹은 기본 비활성)
        // Sampling 이 문서 내용을 클라이언트 LLM 에 보내므로 그보다 먼저 적용한다.
        // 클라이언트 설정에 따라 그 LLM 은 OpenRouter 같은 외부 서비스일 수 있다.
        //
        // 정규화를 먼저 하는 이유: 마스킹 패턴은 숫자 사이 구분자로 ASCII 공백과 하이픈만 받는다.
        // HWP·PDF 추출 텍스트에 섞인 전각 공백(U+3000)·비줄바꿈 공백(U+00A0)·제로폭 문자로 끊긴
        // 주민번호와 카드번호는 정규화가 그 문자를 ASCII 공백으로 바꾼 뒤에야 걸린다.
        // 마스킹을 정규화보다 앞에 두면 그런 번호가 Sampling 요청에 원문으로 실린다.
        // 여기서 만든 문서를 Sampling 입력과 저장용 청킹에 함께 쓴다.
        //
        // 해시 저장과 구버전 벡터 삭제는 원본(changedDocuments)을 기준으로 해야 하므로 원본은 따로 둔다.
        // 마스킹본으로 해시를 남기면 다음 적재 때 원본 해시와 달라 매번 '변경'으로 판정된다.
        // 정규화는 새 Document 를 만들어 돌려주므로 changedDocuments 의 본문은 바뀌지 않는다.
        List<Document> maskedDocuments = piiMaskingTransformer.apply(
                contentFormatTransformer.apply(changedDocuments));

        return ctx.progress(spec -> spec.progress(1).total(PROGRESS_TOTAL_STEPS)
                        .message("텍스트 추출 완료 (" + changedDocuments.size() + "페이지) - 요약 중"))
                .thenReturn(maskedDocuments)
                .flatMap(docs ->
                    // 2단계: Sampling 으로 클라이언트 Ollama에 문서 요약 요청
                    requestSummaryViaSampling(ctx, docs, filename)
                        .flatMap(summary -> {
                            log.info("[적재][{}] 요약 완료 - {}자", jobId, summary.length());
                            return ctx.progress(spec -> spec.progress(2).total(PROGRESS_TOTAL_STEPS)
                                    .message("요약 완료 - 청킹 중"))
                                      .thenReturn(Map.entry(docs, summary));
                        })
                )
                .flatMap(entry -> Mono.fromCallable(() -> {
                    // 3단계: 청킹. 정규화와 마스킹은 1.5단계에서 끝난 문서를 그대로 쓴다.
                    List<Document> chunks = documentChunkTransformer.apply(entry.getKey());
                    log.info("[적재][{}] 청킹 완료 - {}개 청크", jobId, chunks.size());
                    return new IngestContext(changedDocuments, chunks, entry.getValue());
                }).subscribeOn(Schedulers.boundedElastic()))
                .flatMap(ctx3 ->
                    ctx.progress(spec -> spec.progress(3).total(PROGRESS_TOTAL_STEPS)
                            .message("청킹 완료 (" + ctx3.chunks().size() + "개 청크) - 임베딩 중"))
                       .thenReturn(ctx3)
                )
                .flatMap(ctx3 -> Mono.fromCallable(() -> {
                    // 4단계: 변경 페이지의 구버전 벡터를 삭제한 뒤 새 청크 임베딩
                    // 전체 파일 삭제(deleteBySource) 대신 변경된 페이지만 삭제
                    for (Document doc : ctx3.rawDocuments()) {
                        Object pageNum = doc.getMetadata().get("page_number");
                        if (pageNum instanceof Integer p) {
                            staleVectorCleaner.deleteBySourceAndPage(filename, p);
                        } else {
                            staleVectorCleaner.deleteBySource(filename);
                        }
                    }
                    if (ctx3.chunks().isEmpty()) {
                        // 청크가 하나도 생성되지 않은 경우(예: 본문이 min-chunk-length-to-embed 미만)
                        // 메타데이터(해시)를 저장하면 이후 재적재 시 '변경 없음'으로 영구 스킵되어
                        // 문서가 끝내 인덱싱되지 않는다. 따라서 해시를 남기지 않는다.
                        log.warn("[적재][{}] {} - 생성된 청크가 없어 벡터·메타데이터를 저장하지 않습니다. "
                                + "본문이 너무 짧거나(min-chunk-length-to-embed 미만) 추출된 텍스트가 없습니다.",
                                jobId, filename);
                        return ctx3;
                    }

                    pgVectorStore.add(ctx3.chunks());
                    saveMetadata(filename, ctx3.rawDocuments(), ctx3.summary(), clientId);
                    log.info("[적재][{}] 임베딩 완료 - {}개 청크 저장", jobId, ctx3.chunks().size());
                    return ctx3;
                }).subscribeOn(Schedulers.boundedElastic()))
                .flatMap(ctx3 ->
                    ctx.progress(spec -> spec.progress(4).total(PROGRESS_TOTAL_STEPS)
                            .message("임베딩 완료 (" + ctx3.chunks().size() + "개 청크 저장)"))
                       .thenReturn(buildReturnMessage(jobId, filename, ctx3.chunks().size(), ctx3.summary()))
                );
    }

    // 이하 내부 보조 메서드

    /**
     * 갱신된 PDF 의 페이지 수가 이전보다 줄었으면, 사라진 뒤쪽 페이지의 벡터와 해시 행을 지운다.
     * page_number 가 없는 형식(DOCX·HWP·HWPX·MD·TXT)은 파일 하나가 한 단위라 해당이 없다.
     */
    private void removeDroppedPdfPages(String filename, List<Document> rawDocuments, String jobId) {
        int lastPage = rawDocuments.stream()
                .map(doc -> doc.getMetadata().get("page_number"))
                .filter(Integer.class::isInstance)
                .mapToInt(page -> (Integer) page)
                .max()
                .orElse(0);
        if (lastPage == 0) {
            return;
        }
        int removed = staleVectorCleaner.deletePagesAfter(filename, lastPage);
        metadataRepository.deleteByFilenameAndChunkIndexGreaterThanEqual(filename, lastPage);
        if (removed > 0) {
            log.info("[적재][{}] {} - 페이지 수 감소, {}페이지 뒤 데이터 정리", jobId, filename, lastPage);
        }
    }

    /**
     * SHA-256 해시 비교로 변경된 문서(페이지)만 반환한다.
     * 기존 document_metadata와 해시가 동일한 페이지는 결과에서 제외한다.
     * 신규 파일(메타데이터 없음) 또는 해시 조회 실패 시 변경으로 간주한다.
     */
    private List<Document> filterChangedDocuments(List<Document> documents, String filename) {
        List<Document> changed = new ArrayList<>();
        for (Document doc : documents) {
            String content = doc.getText();
            if (content == null || content.isBlank()) continue;
            Object pageNumber = doc.getMetadata().get("page_number");
            int chunkIndex = (pageNumber instanceof Integer p) ? p - 1 : 0;
            String newHash = DocumentHashUtil.calculateHash(content);
            try {
                Optional<DocumentMetadata> existing =
                        metadataRepository.findByFilenameAndChunkIndex(filename, chunkIndex);
                if (existing.isPresent() && newHash.equals(existing.get().getContentHash())) {
                    log.debug("[적재] {} [{}] - 변경 없음", filename, chunkIndex);
                } else {
                    changed.add(doc);
                }
            } catch (Exception e) {
                log.warn("[적재] {} [{}] 해시 조회 실패 - 변경으로 간주: {}", filename, chunkIndex, e.getMessage());
                changed.add(doc);
            }
        }
        return changed;
    }

    /**
     * 임베딩 완료 반환 메시지에 요약을 포함한다.
     * Sampling 미지원/실패 등 오류 문자열("("로 시작)은 제외한다.
     */
    private String buildReturnMessage(String jobId, String filename, int chunkCount, String summary) {
        if (chunkCount == 0) {
            // 성공 메시지를 돌려주면 LLM·사용자가 적재된 것으로 오인한다. 실제로는 검색 대상이 되지 않는다.
            return String.format(
                    "[%s] %s 적재되지 않았습니다 - 임베딩 가능한 본문이 없습니다. "
                            + "문서가 너무 짧거나(최소 길이 미만) 텍스트 추출 결과가 비어 있습니다. "
                            + "내용을 보강한 뒤 다시 적재하세요.",
                    jobId, filename);
        }

        String base = String.format("[%s] %s 임베딩 완료 - %d개 청크가 RAG 지식 베이스에 추가되었습니다.",
                jobId, filename, chunkCount);
        if (summary != null && !summary.isBlank() && !summary.startsWith("(")) {
            return base + "\n\n[문서 요약]\n" + summary;
        }
        return base;
    }

    /**
     * 동일 파일명으로 이미 적재된 문서가 있고, 출처(sourceClient)가 다르면 덮어쓰기를 차단한다.
     * 신규 파일(기존 메타데이터 없음)은 제약 없이 통과한다.
     *
     * 거부 메시지에 조치 방법을 함께 담는다.
     *
     * existingOwner 가 null 이면 REST 재인덱싱이 넣은 문서다.
     */
    private void checkOverwriteOwnership(String filename, String clientId, String jobId) {
        List<DocumentMetadata> existing = metadataRepository.findByFilename(filename);
        for (DocumentMetadata meta : existing) {
            String existingOwner = meta.getSourceClient();
            if (existingOwner == null || !existingOwner.equals(clientId)) {
                log.warn("[적재][{}] 타 클라이언트 문서 덮어쓰기 시도 차단 - {} (기존 출처: {}, 요청: {})",
                        jobId, filename, existingOwner, clientId);
                throw new DocumentOverwriteForbiddenException(String.format(
                        "이미 다른 클라이언트가 적재한 문서입니다. 덮어쓸 수 없습니다: %s (기존 출처: %s). "
                        + "조치는 세 가지입니다. (1) 파일명을 바꿔 적재하면 별도 문서로 등록됩니다. "
                        + "(2) 기존 출처와 같은 클라이언트에서 적재하면 갱신됩니다. "
                        + "(3) 파일을 서버의 app.document.base-path 에 두고 POST /api/documents/reindex 를 "
                        + "호출하면 출처와 무관하게 갱신됩니다. "
                        + "다른 클라이언트의 /ingest 화면을 써도 같은 도구를 호출하므로 똑같이 거부됩니다.",
                        filename,
                        existingOwner == null ? "서버 REST 재인덱싱" : existingOwner));
            }
        }
    }

    private void checkPromptInjection(List<Document> rawDocuments, String jobId, String filename) {
        for (Document doc : rawDocuments) {
            Optional<String> matched = EgovPromptInjectionDetector.detect(doc.getText());
            if (matched.isPresent()) {
                log.warn("[적재][{}] 프롬프트 인젝션 의심 패턴 탐지 - {} (패턴: {})", jobId, filename, matched.get());
                throw new PromptInjectionDetectedException(
                        "문서 내용에서 지시 탈취(prompt injection)로 의심되는 패턴이 발견되어 적재를 거부했습니다.");
            }
        }
    }

    private void validateFileType(String filename, String jobId) {
        String lower = filename.toLowerCase();
        boolean supported = SUPPORTED_EXTENSIONS.stream().anyMatch(lower::endsWith);
        if (!supported) {
            int dotIdx = lower.lastIndexOf('.');
            String ext = dotIdx >= 0 ? lower.substring(dotIdx) : "(확장자 없음)";
            log.warn("[적재][{}] 지원하지 않는 파일 형식 거부 - {}", jobId, ext);
            throw new UnsupportedFileTypeException(
                    "지원하지 않는 파일 형식입니다: " + ext +
                    ". 지원 형식: " + String.join(", ", SUPPORTED_EXTENSIONS));
        }
    }

    /**
     * Base64 디코딩. 실패 시 원인과 대안을 함께 알린다.
     *
     * 이 메서드는 바이너리 확장자(PDF · DOCX · HWP · HWPX)일 때만 호출된다.
     * (텍스트는 isTextMimeType 에서 갈라진다). 따라서 디코딩 실패는 사실상
     * "범용 MCP 클라이언트가 원본 바이트를 전달하지 못했다" 는 것을 뜻한다
     * LLM 이 파일을 읽어 content 를 채우면서 깨진 문자열이 온 것이다.
     */
    private byte[] decodeBase64(String base64Content, String jobId, String filename) {
        try {
            return Base64.getDecoder().decode(base64Content.trim());
        } catch (IllegalArgumentException e) {
            log.error("[적재][{}] Base64 디코딩 실패 - {} ({})", jobId, filename, e.getMessage());
            throw new IllegalArgumentException(String.format(
                    "[%s] %s - Base64 디코딩에 실패했습니다(받은 내용 앞부분: %s). %s",
                    jobId, filename, preview(base64Content),
                    EgovFileSignatureValidator.BINARY_TRANSFER_GUIDANCE), e);
        }
    }

    /** 진단용. 실제로 무엇이 왔는지 앞부분만 보여준다. 긴 내용을 그대로 싣지 않는다. */
    private static String preview(String content) {
        if (content == null) {
            return "(null)";
        }
        String trimmed = content.strip();
        if (trimmed.isEmpty()) {
            return "(빈 문자열)";
        }
        return trimmed.length() <= 40 ? trimmed : trimmed.substring(0, 40) + "...";
    }

    private List<Document> extractDocuments(byte[] fileBytes, String filename, String mimeType, String clientId) {
        String lower = filename.toLowerCase();
        boolean isPdf  = "application/pdf".equalsIgnoreCase(mimeType) || lower.endsWith(".pdf");
        boolean isDocx = lower.endsWith(".docx");
        boolean isHwpx = lower.endsWith(".hwpx");
        boolean isHwp  = lower.endsWith(".hwp");
        boolean isTxt  = "text/plain".equalsIgnoreCase(mimeType) || lower.endsWith(".txt");

        if (isPdf)  return extractPdf(fileBytes, filename, clientId);
        if (isDocx) return extractDocx(fileBytes, filename, clientId);
        if (isHwpx) return extractHwpx(fileBytes, filename, clientId);
        if (isHwp)  return extractHwp(fileBytes, filename, clientId);
        if (isTxt)  return extractText(fileBytes, filename, clientId, "text");
        return extractText(fileBytes, filename, clientId, "markdown");
    }

    /**
     * 텍스트로 취급할지 판정한다. 확장자가 mimeType 보다 우선한다.
     *
     * 확장자로 바이너리임이 분명하면 mimeType 이 무엇이든 바이너리로 처리하고,
     * 그 뒤 EgovFileSignatureValidator 가 실제 바이트를 검사한다.
     * mimeType 은 호출자가 채우는 값이라 신뢰할 수 없다.
     */
    private boolean isTextMimeType(String mimeType, String filename) {
        String lower = filename == null ? "" : filename.toLowerCase();

        // 확장자가 바이너리 형식이면 mimeType 을 무시한다.
        if (lower.endsWith(".pdf") || lower.endsWith(".docx")
                || lower.endsWith(".hwp") || lower.endsWith(".hwpx")) {
            return false;
        }
        if (lower.endsWith(".md") || lower.endsWith(".txt")) {
            return true;
        }
        return "text/markdown".equalsIgnoreCase(mimeType) || "text/plain".equalsIgnoreCase(mimeType);
    }

    private List<Document> extractPdf(byte[] pdfBytes, String filename, String clientId) {
        try {
            ByteArrayResource resource = new ByteArrayResource(pdfBytes) {
                @Override
                public String getFilename() { return filename; }
            };

            PagePdfDocumentReader reader = new PagePdfDocumentReader(
                    resource,
                    PdfDocumentReaderConfig.builder()
                            .withPageTopMargin(0)
                            .withPagesPerDocument(1)
                            .build()
            );

            List<Document> pages = reader.read();
            List<Document> result = new ArrayList<>();

            for (int i = 0; i < pages.size(); i++) {
                Document page = pages.get(i);
                String content = page.getText();
                if (content == null || content.isBlank()) continue;

                Map<String, Object> meta = new HashMap<>(page.getMetadata());
                meta.put("source", filename);
                meta.put("file_name", filename);
                meta.put("type", "pdf");
                meta.put("page_number", i + 1);
                meta.put("upload_source", clientId);
                result.add(new Document(content, meta));
            }

            log.info("[PDF 추출] {} - {}페이지", filename, result.size());
            return result;

        } catch (Exception e) {
            log.error("[PDF 추출] {} 처리 중 오류: {}", filename, e.getMessage());
            throw new RuntimeException("PDF 추출 실패: " + e.getMessage(), e);
        }
    }

    /**
     * DOCX 본문을 추출해 Document 하나로 만든다.
     *
     * PDF 와 달리 페이지 단위로 쪼개지 않는다. DOCX 에는 고정된 페이지 개념이 없고
     * (렌더링 시점에 결정된다) POI 로도 페이지 경계를 알 수 없다. (텍스트 파일과 같은 취급)
     *
     * 그래서 변경 감지도 파일 단위로 일어난다. 큰 문서에서 한 글자만 바뀌어도 전체가
     * 재임베딩된다. (텍스트 파일과 동일한 특성)
     *
     * 메타데이터는 업로드 경로 규약을 따른다(source = 파일명,
     * upload_source = clientId). 디렉터리 스캔용 EgovDocxTextExtractor 의
     * 호출자인 리더와는 메타 구조가 다르므로 텍스트 추출만 공유한다.
     */
    private List<Document> extractDocx(byte[] docxBytes, String filename, String clientId) {
        try {
            String textContent = EgovDocxTextExtractor.extract(docxBytes);
            if (textContent == null || textContent.isBlank()) {
                log.warn("[DOCX 추출] {} - 내용이 비어 있습니다.", filename);
                return List.of();
            }

            Map<String, Object> meta = new HashMap<>();
            meta.put("source", filename);
            meta.put("file_name", filename);
            meta.put("type", "docx");
            meta.put("upload_source", clientId);

            log.info("[DOCX 추출] {} - {}자", filename, textContent.length());
            return List.of(new Document(textContent, meta));

        } catch (Exception e) {
            log.error("[DOCX 추출] {} 처리 중 오류: {}", filename, e.getMessage());
            throw new RuntimeException("DOCX 추출 실패: " + e.getMessage(), e);
        }
    }

    /**
     * HWPX 본문을 추출해 Document 하나로 만든다.
     *
     * DOCX 와 마찬가지로 페이지 단위로 쪼개지 않는다. 변경 감지도 파일 단위다.
     *
     * 추출은 임시 파일을 거친다. hwpxlib 에 스트림 입력이 없어서다
     * (EgovHwpxTextExtractor 참조). DOCX 는 메모리에서 바로 읽는다.
     */
    private List<Document> extractHwpx(byte[] hwpxBytes, String filename, String clientId) {
        try {
            String textContent = EgovHwpxTextExtractor.extract(hwpxBytes);
            if (textContent == null || textContent.isBlank()) {
                log.warn("[HWPX 추출] {} - 내용이 비어 있습니다.", filename);
                return List.of();
            }

            Map<String, Object> meta = new HashMap<>();
            meta.put("source", filename);
            meta.put("file_name", filename);
            meta.put("type", "hwpx");
            meta.put("upload_source", clientId);

            log.info("[HWPX 추출] {} - {}자", filename, textContent.length());
            return List.of(new Document(textContent, meta));

        } catch (Exception e) {
            log.error("[HWPX 추출] {} 처리 중 오류: {}", filename, e.getMessage());
            throw new RuntimeException("HWPX 추출 실패: " + e.getMessage(), e);
        }
    }

    /**
     * HWP 본문을 추출해 Document 하나로 만든다.
     *
     * hwplib 에 fromInputStream 이 있어 메모리에서 바로 읽고
     * (HWPX 처럼 임시 파일이 필요 없다), 구조 추출도 EgovHwpStructuredExtractor 가
     * 이미 HWPFile 을 받는 public static 이라 그대로 공유된다.
     *
     * DOCX · HWPX 와 마찬가지로 페이지 단위로 쪼개지 않는다. 변경 감지도 파일 단위다.
     */
    private List<Document> extractHwp(byte[] hwpBytes, String filename, String clientId) {
        try {
            String textContent;
            try (ByteArrayInputStream is = new ByteArrayInputStream(hwpBytes)) {
                // 문단·표 구조를 남겨 추출한다. hwplib 기본 추출은 표의 행·셀 구분자를 넣지 않아
                // 어느 값이 어느 행에 속하는지 복원할 수 없다.
                textContent = EgovHwpStructuredExtractor.extract(HWPReader.fromInputStream(is));
            }

            if (textContent == null || textContent.isBlank()) {
                log.warn("[HWP 추출] {} - 내용이 비어 있습니다.", filename);
                return List.of();
            }

            Map<String, Object> meta = new HashMap<>();
            meta.put("source", filename);
            meta.put("file_name", filename);
            meta.put("type", "hwp");
            meta.put("upload_source", clientId);

            log.info("[HWP 추출] {} - {}자", filename, textContent.length());
            return List.of(new Document(textContent, meta));

        } catch (Exception e) {
            log.error("[HWP 추출] {} 처리 중 오류: {}", filename, e.getMessage());
            throw new RuntimeException("HWP 추출 실패: " + e.getMessage(), e);
        }
    }

    private List<Document> extractText(byte[] textBytes, String filename, String clientId, String type) {
        try {
            String textContent = new String(textBytes, StandardCharsets.UTF_8);
            if (textContent.isBlank()) {
                log.warn("[텍스트 추출] {} - 내용이 비어 있습니다.", filename);
                return List.of();
            }

            Map<String, Object> meta = new HashMap<>();
            meta.put("source", filename);
            meta.put("file_name", filename);
            meta.put("type", type);
            meta.put("upload_source", clientId);

            log.info("[텍스트 추출][{}] {} - {}자", type, filename, textContent.length());
            return List.of(new Document(textContent, meta));

        } catch (Exception e) {
            log.error("[텍스트 추출] {} 처리 중 오류: {}", filename, e.getMessage());
            throw new RuntimeException("텍스트 추출 실패: " + e.getMessage(), e);
        }
    }

    /**
     * 문서 요약을 클라이언트의 LLM 에 요청 (MCP Sampling).
     *
     * 요약 대상은 앞 2개 문서에서 각 1500자씩이다.
     * 요약은 document_metadata.summary 에 들어가는 부가 정보이고 검색에는 쓰이지 않으므로 전체를 보내지 않는다.
     *
     * 실패해도 적재는 계속된다. 이 경우 사유를 괄호 문구로 남긴다.
     * 그 값이 그대로 document_metadata.summary 에 저장된다.
     */
    private Mono<String> requestSummaryViaSampling(McpAsyncRequestContext ctx,
                                                    List<Document> documents, String filename) {
        StringBuilder excerpt = new StringBuilder();
        int pageLimit = Math.min(documents.size(), 2);
        for (int i = 0; i < pageLimit; i++) {
            String text = documents.get(i).getText();
            if (text != null) {
                int end = Math.min(text.length(), 1500);
                excerpt.append(text, 0, end);
                if (i < pageLimit - 1) excerpt.append("\n\n---\n\n");
            }
        }

        if (excerpt.isEmpty()) {
            return Mono.just("(내용 없음)");
        }

        String prompt = String.format(
                "다음은 '%s' 파일의 내용입니다. 핵심 주제와 내용을 한국어로 2~3문장으로 요약해 주세요.\n\n%s",
                filename, excerpt);

        return ctx.sampleEnabled()
                .flatMap(enabled -> {
                    if (!enabled) {
                        log.info("[Sampling] 클라이언트 Sampling 미지원 - 요약 생략");
                        return Mono.just("(Sampling 미지원)");
                    }
                    return ctx.sample(spec -> spec.message(prompt).maxTokens(300))
                            .map(result -> {
                                if (result.content() instanceof McpSchema.TextContent tc) {
                                    return tc.text() != null ? tc.text() : "(빈 응답)";
                                }
                                return "(비텍스트 응답)";
                            })
                            .onErrorResume(e -> {
                                log.warn("[Sampling] 요약 요청 실패 - {}", e.getMessage());
                                return Mono.just("(요약 실패)");
                            });
                });
    }

    /**
     * 원본 문서(rawDocuments) 기준으로 메타데이터를 저장.
     * summary를 document_metadata 테이블에 함께 저장.
     */
    private void saveMetadata(String filename, List<Document> rawDocuments, String summary, String clientId) {
        for (Document doc : rawDocuments) {
            String content = doc.getText();
            if (content == null || content.isBlank()) continue;

            Object pageNumber = doc.getMetadata().get("page_number");
            int chunkIndex = (pageNumber instanceof Integer p) ? p - 1 : 0;

            String hash = DocumentHashUtil.calculateHash(content);

            try {
                Optional<DocumentMetadata> existing =
                        metadataRepository.findByFilenameAndChunkIndex(filename, chunkIndex);

                DocumentMetadata meta;
                if (existing.isPresent()) {
                    meta = existing.get();
                    meta.setContentHash(hash);
                    meta.setIndexedAt(LocalDateTime.now());
                    meta.setSourceClient(clientId);
                    meta.setSummary(summary);
                } else {
                    meta = new DocumentMetadata(null, filename, chunkIndex, hash,
                            LocalDateTime.now(), clientId, summary);
                }

                metadataRepository.save(meta);
            } catch (Exception e) {
                log.error("[메타데이터 저장] {} [{}] 실패: {}", filename, chunkIndex, e.getMessage());
            }
        }

        log.info("[메타데이터 저장] {} - {}개 페이지/문서, 요약: {}자, clientId: {}",
                filename, rawDocuments.size(), summary != null ? summary.length() : 0, clientId);
    }
}
