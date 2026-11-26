package com.example.webflux.util;

/**
 * 적재가 거부되거나 실패했음을 MCP 호출자에게 알리는 예외.
 *
 * MCP 는 거부를 JSON-RPC 오류가 아니라 정상 응답 + `isError = true` 플래그로 알린다.
 * 이 플래그는 `@McpTool` 메서드가 예외로 끝났을 때만 켜진다.
 * (mcp-annotations AbstractAsyncMcpToolMethodCallback.createAsyncErrorResult).
 *
 * 사유별로 던지는 예외는 따로 있다
 * (`UnsupportedFileTypeException` · `PromptInjectionDetectedException` · `DocumentOverwriteForbiddenException` ·
 * `FileSignatureMismatchException`). 이 클래스는 그것들을 감싸 jobId · 파일명을 붙인 최종 메시지를 만드는 용도다.
 */
public class DocumentIngestFailedException extends RuntimeException {

    public DocumentIngestFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
