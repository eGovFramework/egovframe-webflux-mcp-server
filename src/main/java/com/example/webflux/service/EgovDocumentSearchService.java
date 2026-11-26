package com.example.webflux.service;

import java.util.List;

import reactor.core.publisher.Mono;

public interface EgovDocumentSearchService {

    Mono<String> searchDocuments(String query);

    Mono<String> describeKnowledgeBase();

    /**
     * 파일 하나의 개요(요약·청크 수·적재 정보)를 반환.
     * MCP URI 템플릿 리소스(resource://documents/ 뒤에 파일명)가 호출.
     */
    Mono<String> describeDocument(String filename);

    /**
     * 인덱싱된 파일명 목록을 반환.
     * MCP 자동완성(@McpComplete)이 후보로 사용.
     */
    Mono<List<String>> listIndexedFilenames();
}
