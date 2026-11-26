package com.example.webflux.etl.readers;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.reader.HWPXReader;
import kr.dogfoot.hwpxlib.tool.textextractor.TextExtractMethod;
import kr.dogfoot.hwpxlib.tool.textextractor.TextExtractor;
import kr.dogfoot.hwpxlib.tool.textextractor.TextMarks;

import lombok.extern.slf4j.Slf4j;

/**
 * HWPX 본문 텍스트 추출기.
 *
 * 두 경로가 공유한다.
 *
 * EgovHwpxDocumentReader : 서버 디렉터리 스캔 (기동 적재 · REST 재인덱싱)
 * EgovDocumentIngestServiceImpl : 클라이언트에서 요청받아 적재 (MCP ingestDocument)
 *
 * 본문 추출만 하고 Document 생성은 각 경로가 직접 한다. 메타데이터 규약이 다르기 때문이다.
 * 자세한 근거는 EgovDocxTextExtractor 주석 참조.
 *
 * DOCX 와 달리 바이트를 바로 읽을 수 없다. hwpxlib 에서는
 * HWPXReader.fromFile(File) 과 fromFilepath(String) 뿐이고 스트림 입력이 없다. (1.0.8 에서 재확인 완료)
 * 
 * 그래서 업로드 경로에서는 임시 파일을 거친다.
 */
@Slf4j
public final class EgovHwpxTextExtractor {

    private EgovHwpxTextExtractor() {
    }

    /**
     * 문단·표 구조를 남기기 위한 추출 표식.
     *
     * hwpxlib의 TextExtractor는 TextMarks가 null이면 구분자를 하나도 넣지
     * 않는다. 그 결과 문서 전체가 개행 없는 한 줄이 되고, 표는 셀 값이 이어 붙어 행과 열의 관계가
     * 사라진다.
     *
     * 문단·표 행·표 셀 세 가지만 지정한다. 셀 구분자는 셀 사이에만 들어가며 행 맨 앞에는
     * 붙지 않는다.
     */
    private static final TextMarks STRUCTURE_MARKS = new TextMarks()
            .paraSeparatorAnd("\n")
            .tableRowSeparatorAnd("\n")
            .tableCellSeparatorAnd(" | ");

    /** 파일에서 추출 (디렉터리 스캔 경로). */
    public static String extract(File file) throws Exception {
        HWPXFile hwpxFile = HWPXReader.fromFile(file);
        return TextExtractor.extract(
                hwpxFile,
                TextExtractMethod.InsertControlTextBetweenParagraphText,
                false,
                STRUCTURE_MARKS);
    }

    /**
     * 바이트 배열에서 추출 (업로드 경로).
     *
     * hwpxlib 가 파일만 받으므로 임시 파일을 거친다. 성공·실패와 무관하게 정리한다.
     * 동시 업로드가 겹쳐도 충돌하지 않도록 Files.createTempFile 로 고유 이름을 받는다.
     */
    public static String extract(byte[] hwpxBytes) throws Exception {
        Path temp = Files.createTempFile("egov-ingest-", ".hwpx");
        try {
            Files.write(temp, hwpxBytes);
            return extract(temp.toFile());
        } finally {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException e) {
                // 추출 결과를 버릴 이유가 없으므로 정리 실패만 남긴다.
                log.warn("[HWPX] 임시 파일 삭제 실패 - {}: {}", temp, e.getMessage());
            }
        }
    }
}
