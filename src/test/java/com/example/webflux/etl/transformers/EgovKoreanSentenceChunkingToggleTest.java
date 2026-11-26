package com.example.webflux.etl.transformers;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * EgovDocumentChunkTransformer의 한국어 문장경계 청킹 토글 동작을 검증한다.
 *
 * 원본(egovframe-ai-rag)은 별도 @Configuration 빈으로 splitter를 조건부 등록하지만,
 * 이 프로젝트의 EgovDocumentChunkTransformer는 apply() 호출 시점에 splitter를 직접
 * new로 생성하므로, ReflectionTestUtils로 토글 필드를 세팅해 관찰 가능한 결과로
 * 검증한다.
 */
class EgovKoreanSentenceChunkingToggleTest {

    @Test
    @DisplayName("토글 off(기본): 기존 TokenTextSplitter 결과와 동일하다")
    void fallsBackToTokenTextSplitterWhenDisabled() {
        EgovDocumentChunkTransformer transformer = transformer(30, 10, 100, 0, false);
        List<Document> documents = List.of(new Document(
                "전자정부 표준프레임워크 문서입니다. 기존 TokenTextSplitter 경로를 검증합니다. 폴백 결과가 같아야 합니다."));

        TokenTextSplitter tokenTextSplitter = TokenTextSplitter.builder()
                .withChunkSize(30)
                .withMinChunkSizeChars(10)
                .withMinChunkLengthToEmbed(0)
                .withMaxNumChunks(100)
                .withKeepSeparator(true)
                .build();

        List<String> expected = textsOf(tokenTextSplitter.apply(documents));
        List<String> actual = textsOf(transformer.apply(documents));

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    @DisplayName("토글 on: EgovKoreanSentenceSplitter 결과와 동일하고 문장 경계에서 청크가 끝난다")
    void usesKoreanSentenceSplitterWhenEnabled() {
        EgovDocumentChunkTransformer transformer = transformer(30, 10, 100, 0, true);
        List<Document> documents = List.of(new Document(
                "전자정부 표준프레임워크 문서입니다. 한국어 문장경계 splitter 경로를 검증합니다. 토글이 켜지면 이 경로를 탄다."));

        EgovKoreanSentenceSplitter koreanSentenceSplitter = new EgovKoreanSentenceSplitter(30, 10, 0, 100, true);

        List<String> expected = textsOf(koreanSentenceSplitter.apply(documents));
        List<String> actual = textsOf(transformer.apply(documents));

        assertThat(actual).isEqualTo(expected);
        assertThat(actual).allSatisfy(chunk -> assertThat(chunk).matches(".*(다|요|까|죠|음|임|함|네|오)[.!?]$"));
    }

    @Test
    @DisplayName("같은 입력이라도 토글 값에 따라 다른 splitter 경로를 탄다")
    void toggleSwitchesSplitterImplementation() {
        String text = "짧은 문장입니다. 두 번째 문장입니다. 세 번째로 조금 더 긴 문장이 이어집니다.";
        List<Document> documents = List.of(new Document(text));

        List<String> disabled = textsOf(transformer(20, 10, 100, 0, false).apply(documents));
        List<String> enabled = textsOf(transformer(20, 10, 100, 0, true).apply(documents));

        assertThat(enabled).isNotEqualTo(disabled);
    }

    private EgovDocumentChunkTransformer transformer(int chunkSize, int minChunkSizeChars, int maxNumChunks,
            int minChunkLengthToEmbed, boolean koreanSentenceEnabled) {
        EgovDocumentChunkTransformer transformer = new EgovDocumentChunkTransformer();
        ReflectionTestUtils.setField(transformer, "chunkSize", chunkSize);
        ReflectionTestUtils.setField(transformer, "minChunkSizeChars", minChunkSizeChars);
        ReflectionTestUtils.setField(transformer, "maxNumChunks", maxNumChunks);
        ReflectionTestUtils.setField(transformer, "minChunkLengthToEmbed", minChunkLengthToEmbed);
        ReflectionTestUtils.setField(transformer, "koreanSentenceEnabled", koreanSentenceEnabled);
        return transformer;
    }

    private List<String> textsOf(List<Document> documents) {
        return documents.stream().map(Document::getText).collect(Collectors.toList());
    }
}
