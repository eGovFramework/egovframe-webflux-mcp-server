package com.example.webflux.security;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;

import com.example.webflux.config.SecurityProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EgovApiSecurityFilter 단위 테스트.
 *
 * 규칙 B(MCP 엔드포인트 인증)가 익명 요청을 실제로 막는지, 그리고 403 본문이
 * '어느 쪽을 고쳐야 하는지'를 구분해 알려주는지 확인한다. 전에는 이 검사가 적재 도구
 * 안에 있어서 검색·조회가 익명으로 열려 있었다.
 */
class EgovApiSecurityFilterTest {

    private static final String KEY = "secret-key-1";

    @Test
    @DisplayName("유효한 키가 있으면 MCP 요청이 통과한다")
    void validKeyPasses() {
        AtomicBoolean passed = new AtomicBoolean(false);
        MockServerWebExchange exchange = exchangeFor(
                MockServerHttpRequest.post("/mcp").header("X-MCP-API-Key", KEY));

        filter(KEY).filter(exchange, recordingChain(passed)).block();

        assertThat(passed).isTrue();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("헤더가 없으면 403 - 클라이언트 설정을 고치라고 안내한다")
    void missingHeaderIsRejected() {
        AtomicBoolean passed = new AtomicBoolean(false);
        MockServerWebExchange exchange = exchangeFor(MockServerHttpRequest.post("/mcp"));

        filter(KEY).filter(exchange, recordingChain(passed)).block();

        assertThat(passed).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(bodyOf(exchange)).contains("헤더가 없습니다")
                .contains("클라이언트의 MCP 서버 연결 설정");
    }

    @Test
    @DisplayName("키 값이 다르면 403 - 서버를 고칠 필요가 없다고 알린다")
    void wrongKeyIsRejected() {
        MockServerWebExchange exchange = exchangeFor(
                MockServerHttpRequest.post("/mcp").header("X-MCP-API-Key", "wrong-key"));

        filter(KEY).filter(exchange, failingChain()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(bodyOf(exchange)).contains("일치하지 않습니다")
                .contains("서버를 고칠 필요는 없습니다");
    }

    @Test
    @DisplayName("서버에 등록된 키가 없으면 403 - 서버 관리자가 고쳐야 한다고 알린다")
    void serverWithoutKeyRejectsEveryone() {
        MockServerWebExchange exchange = exchangeFor(
                MockServerHttpRequest.post("/mcp").header("X-MCP-API-Key", KEY));

        filter("").filter(exchange, failingChain()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(bodyOf(exchange)).contains("MCP_API_KEY")
                .contains("클라이언트 설정으로는 해결할 수 없습니다");
    }

    @Test
    @DisplayName("403 본문에 키 값이 들어가지 않는다")
    void responseNeverEchoesTheKey() {
        MockServerWebExchange exchange = exchangeFor(
                MockServerHttpRequest.post("/mcp").header("X-MCP-API-Key", "wrong-key"));

        filter(KEY).filter(exchange, failingChain()).block();

        assertThat(bodyOf(exchange)).doesNotContain("wrong-key").doesNotContain(KEY);
    }

    @Test
    @DisplayName("403 본문이 JSON-RPC 오류라서 SDK 클라이언트가 사유를 읽을 수 있다")
    void rejectionBodyIsJsonRpcError() {
        MockServerWebExchange exchange = exchangeFor(MockServerHttpRequest.post("/mcp"));

        filter(KEY).filter(exchange, failingChain()).block();

        assertThat(exchange.getResponse().getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_JSON);
        // 평문으로 보내면 SDK 가 본문을 버리고 "403 Forbidden from POST ..." 만 남긴다.
        // 그러면 전용 클라이언트 화면의 lastError 에서 사유 구분이 사라진다.
        assertThat(jsonRpcErrorOf(exchange).code()).isEqualTo(-32001);
        assertThat(jsonRpcErrorOf(exchange).message()).contains("X-MCP-API-Key");
    }

    @Test
    @DisplayName("MCP 엔드포인트가 아닌 경로는 키 없이도 통과한다")
    void nonMcpPathIsUntouched() {
        AtomicBoolean passed = new AtomicBoolean(false);
        MockServerWebExchange exchange = exchangeFor(MockServerHttpRequest.get("/swagger-ui.html"));

        filter(KEY).filter(exchange, recordingChain(passed)).block();

        assertThat(passed).isTrue();
    }

    @Test
    @DisplayName("엔드포인트 프로퍼티를 바꾸면 규칙 B도 그 경로를 따라간다")
    void ruleFollowsConfiguredEndpoint() {
        EgovApiSecurityFilter filter =
                new EgovApiSecurityFilter(propsWith(KEY), new ObjectMapper(), "/custom-mcp");

        MockServerWebExchange blocked = exchangeFor(MockServerHttpRequest.post("/custom-mcp"));
        filter.filter(blocked, failingChain()).block();
        assertThat(blocked.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        AtomicBoolean passed = new AtomicBoolean(false);
        MockServerWebExchange open = exchangeFor(MockServerHttpRequest.post("/mcp"));
        filter.filter(open, recordingChain(passed)).block();
        assertThat(passed).isTrue();
    }

    @Test
    @DisplayName("규칙 A - 외부 IP의 재인덱싱 호출은 403")
    void localhostOnlyRuleStillApplies() {
        MockServerWebExchange exchange = exchangeFor(
                MockServerHttpRequest.post("/api/documents/reindex")
                        .remoteAddress(new InetSocketAddress("10.0.0.5", 54321)));

        filter(KEY).filter(exchange, failingChain()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("규칙 A - X-Forwarded-For 를 위조해도 외부 IP 는 403")
    void forwardedHeaderCannotBypassLocalhostRule() {
        // 전에는 이 헤더를 우선 신뢰해서, LAN 의 아무 기기가 이 한 줄로 통과했다.
        // 헤더는 클라이언트가 임의로 넣을 수 있으므로 판정 근거가 될 수 없다.
        MockServerWebExchange exchange = exchangeFor(
                MockServerHttpRequest.post("/api/documents/reindex")
                        .header("X-Forwarded-For", "127.0.0.1")
                        .remoteAddress(new InetSocketAddress("10.0.0.5", 54321)));

        filter(KEY).filter(exchange, failingChain()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("규칙 A - 루프백에서 온 호출은 통과한다 (IPv4 · IPv6)")
    void loopbackPeerIsAllowed() {
        for (String loopback : new String[] { "127.0.0.1", "::1" }) {
            AtomicBoolean passed = new AtomicBoolean(false);
            MockServerWebExchange exchange = exchangeFor(
                    MockServerHttpRequest.post("/api/documents/reindex")
                            .remoteAddress(new InetSocketAddress(loopback, 54321)));

            filter(KEY).filter(exchange, recordingChain(passed)).block();

            assertThat(passed).as("loopback %s", loopback).isTrue();
            assertThat(exchange.getResponse().getStatusCode()).isNull();
        }
    }

    // helpers

    private EgovApiSecurityFilter filter(String configuredKey) {
        return new EgovApiSecurityFilter(propsWith(configuredKey), new ObjectMapper(), "/mcp");
    }

    private SecurityProperties propsWith(String configuredKey) {
        SecurityProperties props = new SecurityProperties();
        props.setApiKeys(List.of(configuredKey));
        return props;
    }

    private MockServerWebExchange exchangeFor(MockServerHttpRequest.BaseBuilder<?> builder) {
        return MockServerWebExchange.from(builder.build());
    }

    private WebFilterChain recordingChain(AtomicBoolean flag) {
        return exchange -> {
            flag.set(true);
            return Mono.empty();
        };
    }

    /** 통과하면 안 되는 경우에 쓴다. 통과하면 테스트가 실패한다. */
    private WebFilterChain failingChain() {
        return exchange -> Mono.error(new AssertionError("차단돼야 하는 요청이 통과했다"));
    }

    /** 본문은 JSON-RPC 오류다. 사유 문구는 error.message 안에 있다. */
    private String bodyOf(MockServerWebExchange exchange) {
        return jsonRpcErrorOf(exchange).message();
    }

    private McpSchema.JSONRPCResponse.JSONRPCError jsonRpcErrorOf(MockServerWebExchange exchange) {
        String raw = exchange.getResponse().getBodyAsString().block();
        try {
            McpSchema.JSONRPCResponse parsed =
                    new ObjectMapper().readValue(raw, McpSchema.JSONRPCResponse.class);
            assertThat(parsed.error()).as("error 필드가 있어야 클라이언트가 사유를 읽는다").isNotNull();
            return parsed.error();
        } catch (Exception e) {
            throw new AssertionError("403 본문이 JSON-RPC 오류로 파싱되지 않았다: " + raw, e);
        }
    }
}
