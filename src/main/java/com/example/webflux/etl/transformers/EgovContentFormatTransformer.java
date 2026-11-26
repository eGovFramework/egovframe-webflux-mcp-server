package com.example.webflux.etl.transformers;

import java.util.List;
import java.util.regex.Pattern;

import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentTransformer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class EgovContentFormatTransformer implements DocumentTransformer {

    @Value("${app.document.normalization.enabled:false}")
    private boolean normalizationEnabled;

    @Value("${app.document.normalization.remove-html-tags:true}")
    private boolean removeHtmlTags;

    @Value("${app.document.normalization.normalize-whitespace:true}")
    private boolean normalizeWhitespace;

    @Value("${app.document.normalization.normalize-newlines:true}")
    private boolean normalizeNewlines;

    @Value("${app.document.normalization.remove-code-blocks:false}")
    private boolean removeCodeBlocks;

    @Value("${app.document.normalization.clean-special-chars:true}")
    private boolean cleanSpecialChars;

    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile("```[\\s\\S]*?```");
    /**
     * 문자 제거 목록. 제어문자 외 의미 없는 문자를 제거한다.
     */
    private static final Pattern SPECIAL_CHARS_PATTERN = Pattern.compile(
        "[\\p{Cntrl}&&[^\\n\\r\\t]]"        // 제어문자 (개행·CR·탭 제외)
        + "|[\\u200B-\\u200F]"            // 제로폭 공백/접합자, LTR/RTL 마크
        + "|[\\u202A-\\u202E]"            // 양방향 텍스트 제어 (구식 임베딩/오버라이드)
        + "|[\\u2066-\\u2069]"            // 양방향 텍스트 제어 (격리문자: LRI/RLI/FSI/PDI)
        + "|[\\u2060-\\u2064]"            // word joiner, invisible operators
        + "|\\uFEFF"                  // BOM / zero-width no-break space
        + "|\\uFFFD");                // replacement character (이미 깨진 바이트의 흔적)

    @Override
    public List<Document> apply(List<Document> documents) {
        if (!normalizationEnabled) {
            log.info("문서 정규화가 비활성화되어 있습니다. 원본 문서를 그대로 반환합니다.");
            return documents;
        }

        log.info("문서 형식 변환 시작: {}개 문서", documents.size());

        List<Document> normalizedDocuments = documents.stream()
                .map(this::normalizeDocument)
                .toList();

        log.info("문서 형식 변환 완료: {}개 문서", normalizedDocuments.size());
        return normalizedDocuments;
    }

    private Document normalizeDocument(Document document) {
        String originalContent = document.getText();
        String normalizedContent = originalContent;

        if (removeHtmlTags) {
            normalizedContent = normalizedContent.replaceAll("<[^>]*>", "");
        }

        if (normalizeWhitespace) {
            // \h 는 유니코드 수평 공백을 포함한다. 자바 \s 는 ASCII 전용이라
            // 비줄바꿈 공백(U+00A0)·전각 공백(U+3000) 등이 공백으로 인식되지 않았다.
            normalizedContent = normalizedContent.replaceAll("[\\h\\x0B\\f]+", " ");
        }

        if (normalizeNewlines) {
            normalizedContent = normalizedContent.replaceAll("\\r\\n?", "\n");
            normalizedContent = normalizedContent.replaceAll("\n{2,}", "\n");
        }

        if (removeCodeBlocks) {
            normalizedContent = CODE_BLOCK_PATTERN.matcher(normalizedContent).replaceAll("");
        }

        if (cleanSpecialChars) {
            normalizedContent = SPECIAL_CHARS_PATTERN.matcher(normalizedContent).replaceAll("");
        }

        normalizedContent = normalizedContent.trim();

        if (!originalContent.equals(normalizedContent)) {
            document.getMetadata().put("original_length", originalContent.length());
            document.getMetadata().put("normalized_length", normalizedContent.length());
            return new Document(document.getId(), normalizedContent, document.getMetadata());
        }

        return document;
    }
}
