package com.example.webflux.etl.readers;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class EgovDocxDocumentReader implements DocumentReader {

    @Value("${app.document.docx-path:#{null}}")
    private String docxDocumentPath;

    @Override
    public List<Document> get() {
        if (docxDocumentPath == null || docxDocumentPath.isBlank()) {
            log.info("DOCX 문서 경로가 설정되지 않아 건너뜁니다.");
            return List.of();
        }
        log.info("DOCX 문서 읽기 시작 - 경로: {}", docxDocumentPath);

        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        List<Document> allDocuments = new ArrayList<>();

        try {
            Resource[] resources = resolver.getResources(docxDocumentPath);

            if (resources.length == 0) {
                log.warn("DOCX 파일을 찾을 수 없습니다: {}", docxDocumentPath);
                return List.of();
            }

            log.info("{}개의 DOCX 파일을 찾았습니다.", resources.length);

            for (Resource resource : resources) {
                try {
                    Document doc = processDocxResource(resource);
                    if (doc != null) {
                        allDocuments.add(doc);
                    }
                } catch (Exception e) {
                    log.error("DOCX 파일 '{}' 처리 중 오류 발생: {}", resource.getFilename(), e.getMessage());
                }
            }

        } catch (IOException e) {
            log.error("DOCX 문서 읽기 중 오류 발생", e);
            return List.of();
        }

        log.info("총 {}개의 DOCX 문서를 읽었습니다.", allDocuments.size());
        return allDocuments;
    }

    private Document processDocxResource(Resource resource) throws IOException {
        String filename = resource.getFilename();
        if (filename == null) {
            log.warn("파일명이 null입니다: {}", resource.getDescription());
            return null;
        }

        String content = extractText(resource);
        if (content == null || content.trim().isEmpty()) {
            log.warn("빈 파일 건너뜀: {}", filename);
            return null;
        }

        // 하위 폴더의 동명 파일이 같은 id 를 받지 않도록 기본 디렉터리 기준 상대 경로를 쓴다.
        // 확장자를 포함한 값을 id·source 양쪽에 그대로 쓴다.
        String idKey = EgovDocumentIdResolver.resolveKey(resource, docxDocumentPath);
        Map<String, Object> metadata = createMetadata(filename, idKey, content);

        String docId = "docx-" + idKey;
        metadata.put("original_id", docId);

        log.info("DOCX 문서 로드 완료: {}, 크기: {}바이트", filename, content.length());
        return new Document(docId, content, metadata);
    }

    /** 본문 추출 로직은 문서 적재와 공유한다. EgovDocxTextExtractor 참조. */
    private String extractText(Resource resource) throws IOException {
        try (InputStream is = resource.getInputStream()) {
            return EgovDocxTextExtractor.extract(is);
        }
    }

    private Map<String, Object> createMetadata(String filename, String idKey, String content) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("source", idKey);
        metadata.put("file_name", filename);
        metadata.put("type", "docx");
        metadata.put("content_length", content.length());
        return metadata;
    }
}
