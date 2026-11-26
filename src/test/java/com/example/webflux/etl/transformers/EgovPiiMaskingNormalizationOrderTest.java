package com.example.webflux.etl.transformers;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 마스킹과 정규화의 순서가 결과를 바꾸는 것을 고정한다.
 *
 * 마스킹 패턴은 숫자 사이 구분자로 ASCII 공백과 하이픈만 받는다. HWP·PDF 에서 뽑은 텍스트에
 * 섞여 나오는 전각 공백·비줄바꿈 공백·제로폭 문자로 끊긴 번호는 EgovContentFormatTransformer 가
 * 그 문자를 ASCII 공백으로 바꾸거나 지운 뒤에야 걸린다.
 *
 * 그래서 두 적재 경로 모두 정규화 뒤에 마스킹한다. MCP 적재 경로는 Sampling 요청보다 먼저
 * 정규화와 마스킹을 마치고, 그 결과를 Sampling 입력과 저장에 함께 쓴다.
 * (EgovDocumentIngestServiceImpl.runPipeline 1.5단계, EgovDocumentManagementServiceImpl 3.5단계)
 */
class EgovPiiMaskingNormalizationOrderTest {

    /** 검증자리까지 유효한 주민등록번호. */
    private static final String RRN = "9001011234568";

    /** Luhn 검사를 통과하는 카드번호. */
    private static final String CARD = "4111111111111111";

    @Test
    @DisplayName("전각 공백으로 끊긴 주민등록번호는 정규화 전에는 마스킹되지 않는다")
    void ideographicSpaceSurvivesMaskingBeforeNormalization() {
        String text = "문의자 " + RRN.substring(0, 6) + "　" + RRN.substring(6) + " 확인";

        assertThat(masking().mask(text)).isEqualTo(text);
    }

    @Test
    @DisplayName("정규화가 전각 공백을 ASCII 공백으로 바꾸면 주민등록번호가 마스킹된다")
    void normalizationThenMaskingCatchesIdeographicSpace() {
        String text = "문의자 " + RRN.substring(0, 6) + "　" + RRN.substring(6) + " 확인";

        assertThat(masking().mask(normalize(text))).isEqualTo("문의자 [RRN] 확인");
    }

    @Test
    @DisplayName("비줄바꿈 공백으로 끊긴 카드번호도 정규화 뒤에 마스킹된다")
    void normalizationThenMaskingCatchesNonBreakingSpace() {
        String text = "결제 4111 1111 1111 1111 승인";

        assertThat(masking().mask(text)).isEqualTo(text);
        assertThat(masking().mask(normalize(text))).isEqualTo("결제 [CARD] 승인");
    }

    @Test
    @DisplayName("제로폭 공백으로 끊긴 주민등록번호도 정규화 뒤에 마스킹된다")
    void normalizationThenMaskingCatchesZeroWidthSpace() {
        String text = "담당 " + RRN.substring(0, 6) + "​" + RRN.substring(6) + " 처리";

        assertThat(masking().mask(text)).isEqualTo(text);
        assertThat(masking().mask(normalize(text))).isEqualTo("담당 [RRN] 처리");
    }

    @Test
    @DisplayName("이미 치환된 토큰은 다시 마스킹해도 그대로 남는다")
    void maskingIsIdempotent() {
        EgovPiiMaskingTransformer t = masking();
        String once = t.mask("문의자 " + RRN + " 결제 " + CARD + " api_key=ak_live_9f2c31");

        assertThat(once).isEqualTo("문의자 [RRN] 결제 [CARD] api_key=[SECRET]");
        assertThat(t.mask(once)).isEqualTo(once);
    }

    @Test
    @DisplayName("두 번 적용해도 메타데이터 플래그는 한 번만 붙는다")
    void metadataFlagStableAcrossTwoPasses() {
        EgovPiiMaskingTransformer t = masking();
        List<Document> first = t.apply(List.of(new Document("문의자 " + RRN + " 확인")));
        List<Document> second = t.apply(first);

        assertThat(second.get(0).getText()).isEqualTo("문의자 [RRN] 확인");
        assertThat(second.get(0).getMetadata())
                .containsEntry(EgovPiiMaskingTransformer.METADATA_MASKING_APPLIED, true);
    }

    private EgovPiiMaskingTransformer masking() {
        return new EgovPiiMaskingTransformer(true, true, true, true,
                "[RRN]", "[CARD]", "[SECRET]", "[ACCOUNT]", "");
    }

    /** 운영 기본값(application.yml)과 같은 설정의 정규화. */
    private String normalize(String content) {
        EgovContentFormatTransformer transformer = new EgovContentFormatTransformer();
        ReflectionTestUtils.setField(transformer, "normalizationEnabled", true);
        ReflectionTestUtils.setField(transformer, "removeHtmlTags", true);
        ReflectionTestUtils.setField(transformer, "normalizeWhitespace", true);
        ReflectionTestUtils.setField(transformer, "normalizeNewlines", true);
        ReflectionTestUtils.setField(transformer, "removeCodeBlocks", false);
        ReflectionTestUtils.setField(transformer, "cleanSpecialChars", true);
        return transformer.apply(List.of(new Document(content))).get(0).getText();
    }
}
