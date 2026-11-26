package com.example.webflux.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import com.example.webflux.etl.readers.EgovDocxDocumentReader;
import com.example.webflux.etl.readers.EgovHwpDocumentReader;
import com.example.webflux.etl.readers.EgovHwpxDocumentReader;
import com.example.webflux.etl.readers.EgovMarkdownDocumentReader;
import com.example.webflux.etl.readers.EgovPdfDocumentReader;
import com.example.webflux.etl.readers.EgovTxtDocumentReader;
import com.example.webflux.model.DocumentMetadata;
import com.example.webflux.repository.DocumentMetadataRepository;
import com.example.webflux.service.impl.EgovDocumentManagementServiceImpl;
import com.example.webflux.util.DocumentHashUtil;
import com.example.webflux.util.EgovStaleVectorCleaner;

/**
 * 갱신된 PDF 의 페이지 수가 줄었을 때 사라진 페이지의 벡터와 해시 행을 지우는지 확인한다.
 *
 * 변경 감지와 페이지 단위 삭제는 지금 읽힌 페이지만 다룬다. 3쪽짜리 PDF 를 2쪽으로 바꾸면
 * 3쪽은 어느 쪽에도 걸리지 않아 예전 벡터가 검색 결과에 계속 나왔다.
 * 남은 페이지가 모두 '변경 없음'이어도 정리해야 하므로, 그 경우를 고정한다.
 */
class EgovDocumentManagementPdfPageShrinkTest {

    private static final Map<Integer, String> PAGES = Map.of(1, "첫 페이지 본문", 2, "둘째 페이지 본문");

    @Test
    @DisplayName("PDF 페이지 수가 줄면 남은 페이지가 변경되지 않았어도 사라진 페이지를 정리한다")
    void droppedPagesAreRemovedEvenWhenRemainingPagesAreUnchanged() throws Exception {
        EgovPdfDocumentReader pdfReader = mock(EgovPdfDocumentReader.class);
        when(pdfReader.get()).thenReturn(List.of(page(1), page(2)));

        DocumentMetadataRepository repository = mock(DocumentMetadataRepository.class);
        when(repository.findAllDistinctFilenames()).thenReturn(List.of("a.pdf"));
        // 남은 두 페이지는 저장된 해시와 같다 → 변경 없음
        when(repository.findByFilenameAndChunkIndex(eq("a.pdf"), anyInt())).thenAnswer(invocation -> {
            int chunkIndex = invocation.getArgument(1);
            String text = PAGES.get(chunkIndex + 1);
            return Optional.of(new DocumentMetadata(null, "a.pdf", chunkIndex,
                    DocumentHashUtil.calculateHash(text), LocalDateTime.now(), null, null));
        });

        EgovStaleVectorCleaner cleaner = mock(EgovStaleVectorCleaner.class);

        var service = new EgovDocumentManagementServiceImpl(
                mock(EgovMarkdownDocumentReader.class), pdfReader, mock(EgovTxtDocumentReader.class),
                mock(EgovDocxDocumentReader.class), mock(EgovHwpDocumentReader.class), mock(EgovHwpxDocumentReader.class),
                null, null, null, null, repository, cleaner, Runnable::run);

        // 변경된 페이지가 없으므로 처리 청크 수는 0
        assertThat(service.loadDocumentsAsync().get(5, TimeUnit.SECONDS)).isZero();

        verify(cleaner).deletePagesAfter("a.pdf", 2);
        verify(repository).deleteByFilenameAndChunkIndexGreaterThanEqual("a.pdf", 2);
        // 남은 페이지는 건드리지 않는다
        verify(cleaner, never()).deleteBySourceAndPage(anyString(), anyInt());
    }

    private static Document page(int pageNumber) {
        return new Document(PAGES.get(pageNumber), Map.of("source", "a.pdf", "page_number", pageNumber));
    }
}
