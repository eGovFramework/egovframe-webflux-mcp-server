package com.example.webflux.etl.readers;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class EgovTxtDocumentReader implements DocumentReader {

    @Value("${app.document.txt-path}")
    private String documentPath;

    @Override
    public List<Document> get() {
        List<Document> documents = new ArrayList<>();
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

        try {
            Resource[] resources = resolver.getResources(documentPath);

            if (resources.length == 0) {
                log.warn("텍스트 파일을 찾을 수 없습니다: {}", documentPath);
                return List.of();
            }

            log.info("{}개의 텍스트 파일을 찾았습니다.", resources.length);

            for (Resource resource : resources) {
                Document doc = processTxtResource(resource);
                if (doc != null) {
                    documents.add(doc);
                }
            }
        } catch (IOException e) {
            log.warn("텍스트 파일을 찾을 수 없거나 접근할 수 없습니다: {}", documentPath);
            return List.of();
        }

        return documents;
    }

    private Document processTxtResource(Resource resource) throws IOException {
        String filename = resource.getFilename();
        if (filename == null) {
            log.warn("파일명이 null입니다: {}", resource.getDescription());
            return null;
        }

        String content = readResourceContent(resource);
        if (content == null || content.trim().isEmpty()) {
            log.warn("빈 파일 건너뜀: {}", filename);
            return null;
        }

        // 하위 폴더의 동명 파일이 같은 id 를 받지 않도록 기본 디렉터리 기준 상대 경로를 쓴다
        String idKey = EgovDocumentIdResolver.resolveKey(resource, documentPath);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("source", idKey);
        metadata.put("type", "text");
        metadata.put("content_length", content.length());
        metadata.put("line_count", content.split("\n").length);

        String docId = "doc-" + idKey;

        log.info("텍스트 문서 로드 완료: {}, 크기: {}바이트", filename, content.length());
        return new Document(docId, content, metadata);
    }

    private String readResourceContent(Resource resource) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }
}
