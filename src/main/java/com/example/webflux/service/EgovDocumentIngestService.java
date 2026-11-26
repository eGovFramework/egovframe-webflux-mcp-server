package com.example.webflux.service;

import org.springaicommunity.mcp.context.McpAsyncRequestContext;
import reactor.core.publisher.Mono;

public interface EgovDocumentIngestService {

    Mono<String> ingestDocument(McpAsyncRequestContext ctx, String jobId, String filename,
                                String content, String mimeType);
}
