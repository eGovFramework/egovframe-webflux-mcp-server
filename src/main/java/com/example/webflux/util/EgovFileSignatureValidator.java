package com.example.webflux.util;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 적재 바이트가 확장자에 맞는 형식인지 파일 시그니처로 검사한다.
 *
 * ingestDocument 는 content 를 문자열 파라미터로 받는다.
 * 전용 클라이언트(/ingest 화면)는 파일을 바이트로 읽어 Base64 로 싣지만,
 * 범용 MCP 클라이언트(VS Code · Claude Desktop 등)에서는 대개 LLM 이 파일을 읽어 그 칸을 채운다.
 * LLM 이 Base64 문자열을 다시 쓰는 과정에서 바이트가 바뀌기 쉬워 내용이 깨진 채 도착하곤 한다.
 * 판정 기준은 클라이언트 종류가 아니라 도착한 바이트이다.
 *
 * Base64 디코딩은 문법만 본다. LLM 이 만들어낸 그럴듯한 문자열도 디코딩은 성공하므로,
 * 검증이 없으면 의미 없는 바이트가 파서로 넘어가서 예외 없이 색인되어 버린다.
 *
 * 클라이언트 이름으로 막지 않는 이유는 화이트리스트를 서버가 들고 있어야 해서 특정 클라이언트에 종속되기 때문이다.
 *
 * Apache Tika 를 쓰지 않는 이유는 다음과 같다.
 * Tika 는 '무엇인지 알려주는' 도구이고 '거부하는' 도구가 아니다.
 * 이 클래스의 역할은 ".Base64 처리인지, UTF8 처리인지"만 구분하면 되고 들어오는 파일의 확장자를 정확하게 판정할 필요까지는 없었다.
 */
public final class EgovFileSignatureValidator {

    private EgovFileSignatureValidator() {
    }

    /** 확장자별 파일 시그니처. 같은 확장자에 여러 후보가 있을 수 있다. */
    private static final Map<String, List<byte[]>> SIGNATURES = Map.of(
            // %PDF
            ".pdf",  List.of(new byte[] { 0x25, 0x50, 0x44, 0x46 }),
            // ZIP 로컬 파일 헤더 (PK\x03\x04): OOXML(docx)·HWPX 공통
            ".docx", List.of(new byte[] { 0x50, 0x4B, 0x03, 0x04 }),
            ".hwpx", List.of(new byte[] { 0x50, 0x4B, 0x03, 0x04 }),
            // OLE 복합 문서 (HWP 5.0)
            ".hwp",  List.of(new byte[] {
                    (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
                    (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1 })
    );

    /**
     * 바이너리 전달 실패 시 공통 안내.
     *
     * 시그니처 불일치(이 클래스)와 Base64 디코딩 실패(적재 서비스) 양쪽에서 쓴다.
     * 두 경로 모두 대개 LLM 이 Base64 문자열을 다시 쓰면서 원본 바이트가 바뀐 경우이다.
     * 문구가 갈라지면 한쪽만 고치게 되므로 여기에 공통으로 만들었다.
     */
    public static final String BINARY_TRANSFER_GUIDANCE =
            "전달된 내용이 원본 파일의 바이트와 다릅니다. 모델이 파일 내용을 대화로 옮겨 쓰는 경로에서는 "
            + "바이너리 파일이 원본 그대로 전달되지 않는 경우가 많습니다. "
            + "전용 클라이언트의 /ingest 화면 업로드나 서버 REST 재인덱싱(POST /api/documents/reindex)을 사용하세요.";

    /** 오류 메시지에 담을 형식 이름. */
    private static final Map<String, String> FORMAT_NAMES = Map.of(
            ".pdf", "PDF", ".docx", "DOCX", ".hwp", "HWP", ".hwpx", "HWPX");

    /**
     * 확장자에 맞는 시그니처가 정의돼 있으면 검사한다.
     * 정의되지 않은 확장자(텍스트 등)는 검사 없이 통과한다.
     *
     * @throws FileSignatureMismatchException 바이트가 확장자와 맞지 않을 때
     */
    public static void validate(byte[] fileBytes, String filename, String jobId) {
        String ext = extensionOf(filename);
        List<byte[]> candidates = SIGNATURES.get(ext);
        if (candidates == null) {
            return;   // 시그니처가 없는 형식이라 검사 대상이 아니다
        }

        if (fileBytes == null || fileBytes.length == 0) {
            throw new FileSignatureMismatchException(String.format(
                    "[%s] %s - 내용이 비어 있습니다.", jobId, filename));
        }

        for (byte[] signature : candidates) {
            if (startsWith(fileBytes, signature)) {
                return;
            }
        }

        throw new FileSignatureMismatchException(String.format(
                "[%s] %s - 내용이 %s 형식이 아닙니다(앞부분: %s). %s",
                jobId, filename, FORMAT_NAMES.getOrDefault(ext, ext), head(fileBytes),
                BINARY_TRANSFER_GUIDANCE));
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        return data.length >= prefix.length
                && Arrays.equals(data, 0, prefix.length, prefix, 0, prefix.length);
    }

    /** 진단용. 실제로 무엇이 왔는지 앞 8바이트를 16진수로 보여준다. */
    private static String head(byte[] data) {
        return HexFormat.of().withUpperCase().formatHex(data, 0, Math.min(8, data.length));
    }

    private static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        String lower = filename.toLowerCase();
        int dot = lower.lastIndexOf('.');
        return dot < 0 ? "" : lower.substring(dot);
    }
}
