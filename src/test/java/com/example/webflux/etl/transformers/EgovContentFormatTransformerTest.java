package com.example.webflux.etl.transformers;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * EgovContentFormatTransformer의 공백 정규화가 유니코드 수평 공백(U+00A0, U+3000, U+202F)을
 * 일반 공백으로 바꾸는지 검증한다.
 *
 * egovframe-ai-rag PR #85와 같은 원인(자바 \s 는 ASCII 전용)이지만, 이 프로젝트의
 * SPECIAL_CHARS_PATTERN 은 화이트리스트가 아니라 제어문자·제로폭 문자 등만 지우는
 * 블랙리스트라 정규화가 안 되더라도 문자가 삭제되진 않는다. 그래도 어휘 검색 일관성을 위해
 * 일반 공백으로 맞춰준다.
 */
class EgovContentFormatTransformerTest {

    @Test
    @DisplayName("공백 정규화는 비줄바꿈 공백과 전각 공백도 일반 공백으로 바꾼다")
    void normalizeWhitespaceHandlesUnicodeSpaces() {
        EgovContentFormatTransformer transformer = transformer(true, true, false);

        // U+00A0 비줄바꿈 공백, U+3000 전각 공백, U+202F 좁은 비줄바꿈 공백
        assertThat(text(transformer, "기관" + " " + "문서")).isEqualTo("기관 문서");
        assertThat(text(transformer, "제1조" + "　" + "목적")).isEqualTo("제1조 목적");
        assertThat(text(transformer, "담당자" + " " + "연락처")).isEqualTo("담당자 연락처");
    }

    @Test
    @DisplayName("공백 정규화가 꺼져 있으면 유니코드 공백도 그대로 남는다")
    void unicodeSpacesAreKeptWhenNormalizationIsOff() {
        EgovContentFormatTransformer transformer = transformer(true, false, false);

        assertThat(text(transformer, "기관" + " " + "문서")).isEqualTo("기관" + " " + "문서");
    }

    @Test
    @DisplayName("운영 기본 설정에서도 유니코드 공백이 일반 공백으로 정규화된다")
    void unicodeSpacesAreNormalizedUnderProductionDefaults() {
        EgovContentFormatTransformer transformer = productionDefaults();

        assertThat(text(transformer, "기관" + " " + "문서")).isEqualTo("기관 문서");
        assertThat(text(transformer, "제1조" + "　" + "목적")).isEqualTo("제1조 목적");
        assertThat(text(transformer, "담당자" + " " + "연락처")).isEqualTo("담당자 연락처");
    }

    private EgovContentFormatTransformer transformer(boolean normalizationEnabled, boolean normalizeWhitespace,
            boolean cleanSpecialChars) {
        EgovContentFormatTransformer transformer = new EgovContentFormatTransformer();
        ReflectionTestUtils.setField(transformer, "normalizationEnabled", normalizationEnabled);
        ReflectionTestUtils.setField(transformer, "removeHtmlTags", false);
        ReflectionTestUtils.setField(transformer, "normalizeWhitespace", normalizeWhitespace);
        ReflectionTestUtils.setField(transformer, "normalizeNewlines", false);
        ReflectionTestUtils.setField(transformer, "removeCodeBlocks", false);
        ReflectionTestUtils.setField(transformer, "cleanSpecialChars", cleanSpecialChars);
        return transformer;
    }

    /** 운영 기본 설정 그대로 (application.yml 의 app.document.normalization.*). */
    private EgovContentFormatTransformer productionDefaults() {
        EgovContentFormatTransformer transformer = new EgovContentFormatTransformer();
        ReflectionTestUtils.setField(transformer, "normalizationEnabled", true);
        ReflectionTestUtils.setField(transformer, "removeHtmlTags", true);
        ReflectionTestUtils.setField(transformer, "normalizeWhitespace", true);
        ReflectionTestUtils.setField(transformer, "normalizeNewlines", true);
        ReflectionTestUtils.setField(transformer, "removeCodeBlocks", false);
        ReflectionTestUtils.setField(transformer, "cleanSpecialChars", true);
        return transformer;
    }

    private String text(EgovContentFormatTransformer transformer, String content) {
        return transformer.apply(List.of(new Document(content))).get(0).getText();
    }
}
