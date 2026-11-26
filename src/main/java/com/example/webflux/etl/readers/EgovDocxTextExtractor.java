package com.example.webflux.etl.readers;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

/**
 * DOCX 본문 텍스트 추출기.
 *
 * 두 경로가 공유한다.
 *
 * EgovDocxDocumentReader : 서버 디렉터리 스캔 (기동 적재 · REST 재인덱싱)
 * EgovDocumentIngestServiceImpl : 클라이언트에서 요청받아 적재 (MCP ingestDocument)
 *
 * 본문 추출만 하고 `org.springframework.ai.document.Document` 생성은 각 경로가 직접 한다.
 * 두 경로의 메타데이터 규약이 다르기 때문이다.
 *
 *   디렉터리 스캔 : source = 상대 경로,  original_id 부여,  content_length 있음
 *   업로드       : source = 파일명,     upload_source(clientId) 있음
 *
 * 업로드 경로의 `upload_source` 는 덮어쓰기 차단과 정리 대상 제외에 쓰이므로,
 * 리더의 메타데이터를 그대로 가져다 쓰면 업로드한 문서가 다음 재인덱싱에서 삭제된다.
 * 그래서 공유 범위를 텍스트 추출로 한정했다.
 */
public final class EgovDocxTextExtractor {

    private EgovDocxTextExtractor() {
    }

    /** 바이트 배열에서 추출 (업로드 경로). */
    public static String extract(byte[] docxBytes) throws IOException {
        try (InputStream is = new ByteArrayInputStream(docxBytes)) {
            return extract(is);
        }
    }

    /** 스트림에서 추출. 스트림은 호출자가 닫는다. */
    public static String extract(InputStream is) throws IOException {
        try (XWPFDocument document = new XWPFDocument(is);
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return extractor.getText();
        }
    }
}
