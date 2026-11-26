package com.example.webflux.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import com.example.webflux.config.SecurityProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpSchema;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * 엔드포인트 접근 통제 필터.
 *
 * 규칙 A - localhost 전용 경로 (서버 로컬에서만 호출):
 *   POST /api/documents/reindex, GET /api/documents/status
 *   → RemoteAddr이 127.0.0.1 또는 ::1이 아니면 403
 *
 * 규칙 B - MCP 엔드포인트:
 *   유효한 X-MCP-API-Key 헤더가 없으면 403
 *
 * 그 외 경로(Swagger 등)는 관여하지 않고 다음 필터로 통과.
 *
 * MCP 도구 안에서 검사하는 방법도 있지만 도구 안에서 검사하려면 HTTP 헤더를 도구까지 전달하는 경로(McpTransportContext)가 필요하고,
 * 자동 구성된 전송 계층 빈을 빌더로 다시 만들어야 하는 번거로움이 있다.
 * 자동 구성에는 contextExtractor 를 끼워 넣을 설정 수단이 없고, 생성자가 private 이라 상속 불가, setter 도 없어서 만든 뒤에 바꿀 수도 없는 문제를 확인
 * 수정 범위가 너무 커져서 검사 범위를 HTTP 입구로 올렸다(2026-10-12).
 *
 * 검색·조회도 키를 요구하게 되었다.
 *
 * 키가 없으면 initialize 부터 막히므로 클라이언트에는 '연결 실패'로 보인다. 
 * MCP Inspector 등으로 점검할 때도 헤더를 먼저 등록해야 한다.
 *
 * 이 클래스를 고칠 때 주의 사항
 *
 * 1. 모든 분기가 Mono 를 반환해야 한다.
 *
 * WebFilter 는 서블릿 Filter 와 달리 호출 즉시 실행되지 않는다. filter() 가 돌려주는
 * Mono 를 WebFlux 가 구독할 때 실제 처리가 일어난다.
 *   - 차단할 때는 setComplete() 또는 writeWith(...) 가 만든 Mono 를 반환해야 하고
 *   - 통과시킬 때는 chain.filter(exchange) 의 결과를 반환해야 한다.
 * 반환이 없으면 응답이 완료되지 않아 호출자가 타임아웃까지 대기하게 된다.
 *
 * 2. 이 코드는 이벤트 루프 스레드에서 돈다.
 *
 * subscribeOn 이 없으므로 구독한 스레드, 즉 Netty 이벤트 루프(reactor-http-nio-N)에서 실행된다.
 * 지금은 문자열 비교와 주소 변환 로직밖에 없으므로 상관 없지만
 * 키를 app.security.api-keys 목록 대신 DB 나 외부 인증 서버에서 조회하는 블로킹 호출이 걸려 버리면 문제가 생기므로
 * 그때는 Mono 로 비동기화하거나 subscribeOn(Schedulers.boundedElastic()) 으로 빼야 한다.
 */
@Slf4j
@Component
@Order(-100)
public class EgovApiSecurityFilter implements WebFilter {

    private static final String REINDEX_PATH = "/api/documents/reindex";
    private static final String STATUS_PATH  = "/api/documents/status";

    private static final String HEADER_API_KEY = "X-MCP-API-Key";

    /**
     * 403 본문에 넣는 JSON-RPC 오류 코드.
     *
     * MCP 에는 인증용 코드가 없다. JSON-RPC 가 구현별로 쓰라고 비워 둔 범위
     * (-32000 ~ -32099)에서 고른 값이다. -32002 는 SDK 가 RESOURCE_NOT_FOUND 로 쓴다.
     *
     * 이 값은 전용 클라이언트(egovframe-webflux-mcp-client)에서 사용된다. egovframe-webflux-mcp-client 의
     * EgovMcpServerRegistry.ERROR_CODE_UNAUTHORIZED 가 같은 값을 보고
     * '키 문제' 와 '서버가 꺼짐' 을 구분해 화면 안내를 가른다. 바꾸려면 양쪽을 함께 고쳐야 한다.
     */
    private static final int ERROR_CODE_UNAUTHORIZED = -32001;

    private final SecurityProperties securityProperties;
    private final ObjectMapper objectMapper;

    /** 자동 구성이 전송 계층에 쓰는 것과 같은 프로퍼티. 엔드포인트를 바꿔도 규칙 B가 따라간다. */
    private final String mcpEndpoint;

    public EgovApiSecurityFilter(
            SecurityProperties securityProperties,
            ObjectMapper objectMapper,
            @Value("${spring.ai.mcp.server.streamable-http.mcp-endpoint:/mcp}") String mcpEndpoint) {
        this.securityProperties = securityProperties;
        this.objectMapper = objectMapper;
        this.mcpEndpoint = mcpEndpoint;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path   = exchange.getRequest().getPath().value();
        String method = exchange.getRequest().getMethod().name();

        // 규칙 A: localhost 전용
        if (isLocalhostOnly(path, method)) {
            String remoteAddr = resolveRemoteAddr(exchange);
            if (!isLocalhost(remoteAddr)) {
                log.warn("[보안] 외부 IP에서 localhost 전용 경로 접근 차단 - path: {}, addr: {}",
                        path, remoteAddr);
                exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                return exchange.getResponse().setComplete();
            }
        }

        // 규칙 B: MCP 엔드포인트는 API 키 필요
        if (isMcpPath(path)) {
            String apiKey = exchange.getRequest().getHeaders().getFirst(HEADER_API_KEY);
            if (!securityProperties.isValidKey(apiKey)) {
                AuthFailure failure = classifyAuthFailure(apiKey);
                // 키 값은 남기지 않는다. 사유 이름만으로 어느 쪽을 고쳐야 하는지 판별된다.
                log.warn("[보안] MCP 요청 인증 실패 - path: {}, 사유: {}", path, failure.name());
                return unauthorized(exchange, failure);
            }
        }

        return chain.filter(exchange);
    }

    private boolean isLocalhostOnly(String path, String method) {
        return (REINDEX_PATH.equals(path) && "POST".equals(method))
                || (STATUS_PATH.equals(path) && "GET".equals(method));
    }

    /** POST(메시지) · GET(SSE) · DELETE(세션 종료)가 모두 이 경로로 온다. */
    private boolean isMcpPath(String path) {
        return path.equals(mcpEndpoint) || path.startsWith(mcpEndpoint + "/");
    }

    /**
     * 403 과 함께 JSON-RPC 오류를 본문에 담는다.
     *
     * 401 이 아니라 403 인 이유는 401은 인증 정보가 부족하다는 의미이기 때문이다.
     * MCP 인증 규격에서 401 은 'OAuth 를 시작하라' 는 신호이므로 규격을 따르는 범용 클라이언트가 엉뚱하게 해석할 여지가 있다.
     *
     * 본문을 JSON-RPC 오류로 보내는 이유는 상세한 사유를 전달하기 위해서이다. (예 : '키 문제', '서버가 꺼짐')
     *
     */
    private Mono<Void> unauthorized(ServerWebExchange exchange, AuthFailure failure) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.FORBIDDEN);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return response.writeWith(Mono.fromCallable(
                () -> response.bufferFactory().wrap(errorBody(failure))));
    }

    private byte[] errorBody(AuthFailure failure) {
        var error = new McpSchema.JSONRPCResponse.JSONRPCError(
                ERROR_CODE_UNAUTHORIZED, failure.cause + " " + failure.remedy, null);
        var body = new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, null, null, error);
        try {
            return objectMapper.writeValueAsBytes(body);
        } catch (Exception e) {
            // 직렬화가 실패해도 403 은 내보낸다.
            log.warn("[보안] 403 본문 직렬화 실패 - 빈 본문으로 응답합니다: {}", e.getMessage());
            return new byte[0];
        }
    }

    /**
     * 규칙 A 판정에 쓰는 원격 주소. 연결을 맺은 상대 주소만 본다.
     *
     * X-Forwarded-For 는 클라이언트가 임의로 넣을 수 있는 헤더라서 보지 않는다.
     * 
     * 리버스 프록시를 두게 되면 프록시 주소를 프로퍼티로 받아, 그 주소에서 온
     * 요청에 대해서만 헤더를 인정하도록 다시 열 것. 어느 주소에서 오든 인정하도록 열어 두지 않는다.
     * 그때 쓸 값은 프록시가 덮어쓴 마지막 칸이다 - 앞 칸은 클라이언트가 보낸 값이 남아 있을 수 있다.
     */
    private String resolveRemoteAddr(ServerWebExchange exchange) {
        var addr = exchange.getRequest().getRemoteAddress();
        return addr != null ? addr.getAddress().getHostAddress() : "";
    }

    private boolean isLocalhost(String addr) {
        return "127.0.0.1".equals(addr) || "::1".equals(addr) || "0:0:0:0:0:0:0:1".equals(addr);
    }

    /**
     * 인증 실패 사유. 사유마다 고쳐야 할 사람이 다르다.
     */
    private enum AuthFailure {

        /** 서버에 등록된 키가 없다 - 어떤 클라이언트도 통과할 수 없다. */
        SERVER_NOT_CONFIGURED(
                "서버에 API 키가 등록되지 않아 모든 MCP 요청이 차단된 상태입니다.",
                "서버 관리자가 app.security.api-keys(환경변수 MCP_API_KEY)를 설정해야 합니다. "
                        + "클라이언트 설정으로는 해결할 수 없습니다."),

        /** 헤더가 아예 오지 않았다 - 클라이언트가 보내지 않는다. */
        HEADER_MISSING(
                "요청에 X-MCP-API-Key 헤더가 없습니다.",
                "클라이언트의 MCP 서버 연결 설정에 이 헤더를 추가하세요. 서버 설정은 정상입니다. "
                        + "키 값은 대화에 노출하지 마세요."),

        /** 헤더는 왔는데 값이 다르다. */
        KEY_MISMATCH(
                "X-MCP-API-Key 헤더 값이 서버에 등록된 키와 일치하지 않습니다.",
                "클라이언트 설정의 헤더 값을 확인하세요. 서버 설정은 정상이므로 서버를 고칠 필요는 없습니다. "
                        + "키 값은 대화에 노출하지 마세요.");

        private final String cause;
        private final String remedy;

        AuthFailure(String cause, String remedy) {
            this.cause = cause;
            this.remedy = remedy;
        }
    }

    private AuthFailure classifyAuthFailure(String apiKey) {
        if (!securityProperties.hasConfiguredKey()) {
            return AuthFailure.SERVER_NOT_CONFIGURED;
        }
        return (apiKey == null || apiKey.isBlank())
                ? AuthFailure.HEADER_MISSING
                : AuthFailure.KEY_MISMATCH;
    }
}
