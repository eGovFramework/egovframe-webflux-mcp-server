package com.example.webflux.etl.transformers;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * EgovContentFormatTransformer의 특수문자 정리가 양방향 텍스트 제어문자를 빠짐없이 지우면서도,
 * 화이트리스트가 아니므로 한글·ASCII 밖의 정상 내용(악센트 라틴 문자, 수학 기호, 가나)은 그대로 두는지
 * 검증한다.
 *
 * ‪-‮(LRE/RLE/LRO/RLO, 구식 임베딩·오버라이드)만 막고 같은 목적의
 * ⁦-⁩(LRI/RLI/FSI/PDI, 격리문자)를 빠뜨리고 있었다. egovframe-ai-rag PR #85/#86
 * 교차 점검 중 실행으로 확인해 추가했다.
 */
class EgovContentFormatTransformerSpecialCharsTest {

    @Test
    @DisplayName("특수문자 정리는 양방향 격리문자(LRI/RLI/FSI/PDI)도 지운다")
    void cleanSpecialCharsRemovesBidiIsolateControls() {
        EgovContentFormatTransformer transformer = cleanSpecialCharsOnly();

        assertThat(text(transformer, "가" + "⁦" + "나" + "⁩")).isEqualTo("가나");
        assertThat(text(transformer, "가" + "⁧" + "나" + "⁩")).isEqualTo("가나");
        assertThat(text(transformer, "가" + "⁨" + "나" + "⁩")).isEqualTo("가나");
    }

    @Test
    @DisplayName("특수문자 정리는 기존과 같이 양방향 임베딩·오버라이드 제어문자를 지운다")
    void cleanSpecialCharsStillRemovesBidiEmbeddingControls() {
        EgovContentFormatTransformer transformer = cleanSpecialCharsOnly();

        assertThat(text(transformer, "가" + "‪" + "나" + "‬")).isEqualTo("가나");
        assertThat(text(transformer, "가" + "‮" + "나")).isEqualTo("가나");
    }

    @Test
    @DisplayName("특수문자 정리는 화이트리스트가 아니라서 한글·ASCII 밖의 정상 내용을 지우지 않는다")
    void cleanSpecialCharsKeepsNonKoreanContent() {
        EgovContentFormatTransformer transformer = cleanSpecialCharsOnly();

        assertThat(text(transformer, "Jos" + "é" + " 사용자")).isEqualTo("Jos" + "é" + " 사용자");
        assertThat(text(transformer, "합계 " + "∑" + " 무한대 " + "∞"))
                .isEqualTo("합계 " + "∑" + " 무한대 " + "∞");
        assertThat(text(transformer, "일본어 " + "こんにちは" + " 테스트"))
                .isEqualTo("일본어 " + "こんにちは" + " 테스트");
    }

    private EgovContentFormatTransformer cleanSpecialCharsOnly() {
        EgovContentFormatTransformer transformer = new EgovContentFormatTransformer();
        ReflectionTestUtils.setField(transformer, "normalizationEnabled", true);
        ReflectionTestUtils.setField(transformer, "removeHtmlTags", false);
        ReflectionTestUtils.setField(transformer, "normalizeWhitespace", false);
        ReflectionTestUtils.setField(transformer, "normalizeNewlines", false);
        ReflectionTestUtils.setField(transformer, "removeCodeBlocks", false);
        ReflectionTestUtils.setField(transformer, "cleanSpecialChars", true);
        return transformer;
    }

    private String text(EgovContentFormatTransformer transformer, String content) {
        return transformer.apply(List.of(new Document(content))).get(0).getText();
    }
}
