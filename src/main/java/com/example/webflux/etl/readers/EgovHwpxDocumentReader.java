package com.example.webflux.etl.readers;

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
public class EgovHwpxDocumentReader implements DocumentReader {

    @Value("${app.document.hwpx-path:#{null}}")
    private String hwpxDocumentPath;

    @Override
    public List<Document> get() {
        if (hwpxDocumentPath == null || hwpxDocumentPath.isBlank()) {
            log.info("HWPX 문서 경로가 설정되지 않아 건너뜁니다.");
            return List.of();
        }

        log.info("HWPX 문서 읽기 시작 - 경로: {}", hwpxDocumentPath);

        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources(hwpxDocumentPath);

            if (resources.length == 0) {
                log.warn("HWPX 파일을 찾을 수 없습니다: {}", hwpxDocumentPath);
                return List.of();
            }

            log.info("{}개의 HWPX 파일을 찾았습니다.", resources.length);

            List<Document> allDocuments = new ArrayList<>();

            for (Resource resource : resources) {
                log.info("HWPX 파일 처리 중: {}", resource.getFilename());
                try {
                    Document doc = readHwpxResource(resource);
                    if (doc != null) {
                        allDocuments.add(doc);
                    }
                } catch (Exception e) {
                    log.error("HWPX 파일 '{}' 처리 중 오류 발생: {}", resource.getFilename(), e.getMessage());
                }
            }

            log.info("총 {}개의 HWPX 문서를 읽었습니다.", allDocuments.size());
            return allDocuments;

        } catch (Exception e) {
            log.error("HWPX 문서 읽기 중 오류 발생", e);
            return List.of();
        }
    }

    private Document readHwpxResource(Resource resource) throws Exception {
        String filename = resource.getFilename();
        if (filename == null) {
            log.warn("파일명이 null입니다: {}", resource.getDescription());
            return null;
        }

        // 본문 추출 로직은 문서 적재 경로와 공유한다. EgovHwpxTextExtractor 참조.
        String content = EgovHwpxTextExtractor.extract(resource.getFile());

        if (content == null || content.trim().isEmpty()) {
            log.warn("빈 HWPX 파일 건너뜀: {}", filename);
            return null;
        }

        // 하위 폴더의 동명 파일이 같은 id 를 받지 않도록 기본 디렉터리 기준 상대 경로를 쓴다.
        // 확장자를 포함한 값을 id·source 양쪽에 그대로 쓴다.
        String idKey = EgovDocumentIdResolver.resolveKey(resource, hwpxDocumentPath);
        String customId = String.format("hwpx-%s", idKey);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("file_name", filename);
        metadata.put("source", idKey);
        metadata.put("type", "hwpx");
        metadata.put("content_length", content.length());
        metadata.put("original_id", customId);

        log.info("HWPX 문서 로드 완료: {}, 크기: {}자", filename, content.length());
        return new Document(customId, content, metadata);
    }
}
