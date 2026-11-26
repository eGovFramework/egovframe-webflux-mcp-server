package com.example.webflux.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springaicommunity.mcp.annotation.McpArg;
import org.springaicommunity.mcp.annotation.McpComplete;
import org.springaicommunity.mcp.annotation.McpPrompt;
import org.springaicommunity.mcp.annotation.McpResource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import com.example.webflux.service.EgovDocumentSearchService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * MCP Resource / Prompt / Completion 등록 컴포넌트
 *
 * MCP 서버가 도구(Tool) 외에 제공하는 것들이다.
 *
 * McpResource: 읽기 전용 데이터 소스
 *   · 고정 URI   resource://documents/index        인덱싱된 문서 목록
 *   · URI 템플릿 resource://documents/{filename}   문서 하나의 개요
 * McpPrompt: 재사용 가능한 프롬프트 템플릿 (rag_assistant)
 * McpComplete: 자동완성 후보. 프롬프트 인자(strict)와 URI 템플릿 변수(filename) 두 가지
 *
 * 여기 등록한 것들은 우리 코드가 직접 호출하지 않는다. MCP 프로토콜을 통해
 * 접속한 클라이언트가 가져간다
 *
 * 전용 클라이언트(egovframe-webflux-mcp-client)는 rag_assistant 를
 * prompts/get 으로 받아 RAG 지시문으로 쓴다. 
 * 
 * 지시문을 서버에서 받는 이유는 지시문을 클라이언트에 넣게 되면 서버 없이 클라이언트 단독 기동시에는 도구가 없는데
 * "검색 결과를 근거로 답하라"는 지시만 남게 되고 모델은 무조건 "문서에서 찾을 수 없습니다"로 답하게 되기 때문이다.
 * 범용 클라이언트(VS Code·Claude Desktop 등)에서는 사용자가 명시적으로 불러야 한다.
 * 도구처럼 LLM 이 자율 호출하지 않는다.
 *
 * resource://documents/index 는 describeKnowledgeBase 도구와 같은 메서드에 위임한다.
 * 같은 내용을 Tool·Resource 두 곳에서 처리했는데, Resource 는 LLM 이 자율 호출하지 않으므로
 * 실질 활용도는 도구 쪽이 높다. 리소스·템플릿·자동완성 세 가지를 한자리에서 보여주는
 * 예시로서 남겨 둔다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EgovMcpResourcePromptConfig {

    /**
     * strict 인자가 받는 값. @McpComplete 자동완성 후보이자 입력 검증 기준이다.
     *
     * 모드 분기 자체는 buildRagPrompt 가 "true" 와 직접 비교한다.
     * 여기에 값을 추가하면 분기도 함께 고쳐야 한다.
     */
    private static final List<String> STRICT_VALUES = List.of("false", "true");

    private final EgovDocumentSearchService documentSearchService;

    /**
     * 프롬프트 원문은 클래스에 하드코딩하지 않고 리소스 파일로 관리한다.
     * 프롬프트 리소스 경로는 src/main/resources/prompts/ 에 위치한다.
     */
    @Value("classpath:/prompts/rag-assistant-default.st")
    private Resource defaultPrompt;

    @Value("classpath:/prompts/rag-assistant-strict.st")
    private Resource strictPrompt;

    /**
     * [Resource] 인덱싱된 문서 목록
     *
     * 도구(Tool)와의 차이: 도구는 실행/검색, 리소스는 정적 데이터 노출.
     * describeKnowledgeBase 도구와 동일 로직이므로 서비스에 위임한다.
     *
     * 반환형 규칙은 buildRagPrompt 의 주석과 같다. ASYNC 서버이므로
     * Mono<String> 이어야 한다.
     *
     * 고정 URI 라 '리소스'로 분류된다. 변수 자리가 있는 getDocumentSummary 는 '템플릿'이다.
     */
    @McpResource(
            uri = "resource://documents/index",
            name = "인덱싱된 문서 목록",
            description = "RAG 지식 베이스에 등록된 검색 가능한 문서 목록. "
                    + "searchDocuments 호출 전 어떤 파일이 있는지 확인할 수 있습니다.",
            mimeType = "text/plain"
    )
    public Mono<String> getDocumentIndex() {
        return documentSearchService.describeKnowledgeBase();
    }

    /**
     * [Resource/Template] 파일 하나의 개요
     *
     * URI 에 변수 자리({filename})가 있어 '리소스'가 아니라 'URI 템플릿'으로 분류된다.
     * 선언 하나가 적재된 파일 수만큼의 리소스를 대표한다. 클라이언트는
     * resources/templates/list 로 이 템플릿을 받고, 변수를 채운 실제 URI 로 resources/read 한다.
     *
     * 고정 리소스(resource://documents/index)와 같은 계층에 있어도 충돌하지 않는다.
     * 서버가 고정 리소스를 먼저 찾고 없을 때만 템플릿으로 넘어간다
     * (mcp-core McpAsyncServer.resourcesReadRequestHandler). 그래서 .../index 는 항상 목록이다.
     *
     * URI 변수는 메서드 파라미터로 받는다. 변수 개수와 파라미터 개수가 다르면 기동이 실패한다
     * ("Method must have parameters for all URI variables").
     *
     * 개요만 주는 이유와 파일명 해석(퍼센트 인코딩)은 describeDocument 주석 참조.
     */
    @McpResource(
            uri = "resource://documents/{filename}",
            name = "문서 개요",
            description = "인덱싱된 문서 하나의 요약·청크 수·적재 정보. "
                    + "파일명은 resource://documents/index 목록에서 고릅니다. 전문 검색은 searchDocuments 도구를 쓰세요.",
            mimeType = "text/plain"
    )
    public Mono<String> getDocumentSummary(String filename) {
        return documentSearchService.describeDocument(filename);
    }

    /**
     * [Completion] 위 템플릿의 filename 자동완성.
     *
     * @McpComplete 의 속성은 prompt(프롬프트 인자용)와 uri(URI 템플릿 변수용)이 있으며
     * 이쪽은 uri 를 쓰는 경우다.
     *
     * 템플릿만 만들고 McpComplete 를 만들지 않으면 클라이언트가 변수 자동완성을 시도할 때
     * -32602 AsyncCompletionSpecification not found 가 난다.
     */
    @McpComplete(uri = "resource://documents/{filename}")
    public Mono<List<String>> completeFilename(String prefix) {
        String p = prefix == null ? "" : prefix.trim().toLowerCase();
        return documentSearchService.listIndexedFilenames()
                .map(names -> names.stream().filter(n -> n.toLowerCase().startsWith(p)).toList());
    }

    /**
     * [Prompt] RAG 어시스턴트 지시문
     * 도구(Tool)와의 차이: 도구는 기능 실행, 프롬프트는 LLM 행동 지침 제공.
     *
     * strict=false (기본): 기술 질문에만 검색을 요구
     * strict=true: 모든 질문에 검색 강제, 결과 없으면 답변 거부
     *
     * 그 밖의 값(오타 · 1 · yes 등)과 미전달은 모두 기본 모드다.
     *
     * 반환형은 반드시 Mono<String> 이어야 한다.
     * 이 서버는 spring.ai.mcp.server.type: ASYNC 라 다음 경고만 발생하고 프롬프트 전체가 누락된 것을 확인하였다
     * (ASYNC Providers don't support imperative (non-reactive) return types)
     *
     * 전달되는 role 은 assistant 로 고정된다. mcp-annotations 가
     * String 반환을 PromptMessage(Role.ASSISTANT, ...) 로 감싸기 때문에
     * 서버가 고를 수 없다(Inspector로 확인)
     *
     * 이 지시문의 효과는 모델에 따라 크게 갈린다. 그러므로
     * prompts/rag-assistant-*.st 를 고치면 재측정이 필요하다.
     */
    @McpPrompt(
            name = "rag_assistant",
            description = "RAG 기반 문서 검색을 위한 지시문. "
                    + "클라이언트가 이 텍스트를 사용자 질문과 함께 전달하면 LLM이 searchDocuments를 먼저 호출합니다. "
                    + "소형 로컬 모델에서는 지시문을 별도 메시지로 두면 무시되므로, 질문과 한 메시지로 합쳐 보내세요."
    )
    public Mono<String> buildRagPrompt(
            @McpArg(
                    name = "strict",
                    description = "엄격 모드 여부 (true/false). "
                            + "true이면 모든 질문에 검색 강제, 검색 결과가 없으면 답변 거부.",
                    required = false
            ) String strict
    ) {
        if (strict != null && !strict.isBlank()
                && !STRICT_VALUES.contains(strict.trim().toLowerCase())) {
            log.warn("[프롬프트] rag_assistant 의 strict 인자에 알 수 없는 값 - 기본 모드로 처리합니다. 허용: {}",
                    STRICT_VALUES);
        }
        return Mono.just(read("true".equalsIgnoreCase(strict) ? strictPrompt : defaultPrompt));
    }

    /**
     * [Completion] rag_assistant 의 strict 인자 자동완성.
     *
     * Spring AI 자동 구성은 spring.ai.mcp.server.capabilities.completion 이
     * 기본 true 라 completions 능력을 지원한다고 나온다. 그런데 @McpComplete 메서드가 하나도 없으면
     * 클라이언트가 자동완성을 시도할 때 서버가 거부한다. Inspector에서 실제로 다음 경고를 확인하였다.
     * 32602 AsyncCompletionSpecification not found
     */
    @McpComplete(prompt = "rag_assistant")
    public Mono<List<String>> completeStrict(String prefix) {
        String p = prefix == null ? "" : prefix.trim().toLowerCase();
        return Mono.just(STRICT_VALUES.stream().filter(v -> v.startsWith(p)).toList());
    }

    /**
     * 프롬프트 리소스를 읽는다. 개행을 LF 로 정규화한다.
     *
     * 정규화하지 않으면 프롬프트 본문이 체크아웃 환경에 따라 달라진다.
     */
    private static String read(Resource resource) {
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8)
                    .replace("\r\n", "\n")
                    .replace("\r", "\n");
        } catch (IOException e) {
            throw new UncheckedIOException("프롬프트 리소스를 읽을 수 없습니다: " + resource.getDescription(), e);
        }
    }
}
