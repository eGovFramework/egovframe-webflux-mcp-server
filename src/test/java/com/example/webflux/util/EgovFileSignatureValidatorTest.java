package com.example.webflux.util;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EgovFileSignatureValidator 단위 테스트.
 *
 * 막으려는 것은 범용 MCP 클라이언트가 바이너리 파일을 적재할 때 도착하는 깨진 바이트다.
 * Base64 디코딩은 문법만 보므로, LLM 이 만들어낸 그럴듯한 문자열도 디코딩에는 성공한다.
 */
class EgovFileSignatureValidatorTest {

    private static final String JOB_ID = "test-job";

    private static final byte[] PDF_HEADER  = { 0x25, 0x50, 0x44, 0x46 };                 // %PDF
    private static final byte[] ZIP_HEADER  = { 0x50, 0x4B, 0x03, 0x04 };                 // PK..
    private static final byte[] OLE_HEADER  = { (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
                                                (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1 };

    // 정상 통과

    @Test
    @DisplayName("PDF 시그니처로 시작하면 통과한다")
    void pdfWithCorrectSignaturePasses() {
        assertThatCode(() -> EgovFileSignatureValidator.validate(
                withTrailer(PDF_HEADER), "guide.pdf", JOB_ID))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("DOCX 와 HWPX 는 둘 다 ZIP 시그니처를 쓴다")
    void ooxmlAndHwpxSharePkSignature() {
        assertThatCode(() -> EgovFileSignatureValidator.validate(
                withTrailer(ZIP_HEADER), "report.docx", JOB_ID))
                .doesNotThrowAnyException();

        assertThatCode(() -> EgovFileSignatureValidator.validate(
                withTrailer(ZIP_HEADER), "report.hwpx", JOB_ID))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("HWP 는 OLE 복합문서 시그니처를 쓴다")
    void hwpUsesOleSignature() {
        assertThatCode(() -> EgovFileSignatureValidator.validate(
                withTrailer(OLE_HEADER), "report.hwp", JOB_ID))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("시그니처가 정의되지 않은 형식은 검사하지 않는다")
    void formatsWithoutSignatureAreSkipped() {
        byte[] plainText = "그냥 텍스트입니다".getBytes(StandardCharsets.UTF_8);

        assertThatCode(() -> EgovFileSignatureValidator.validate(plainText, "note.txt", JOB_ID))
                .doesNotThrowAnyException();
        assertThatCode(() -> EgovFileSignatureValidator.validate(plainText, "note.md", JOB_ID))
                .doesNotThrowAnyException();
        assertThatCode(() -> EgovFileSignatureValidator.validate(plainText, "확장자없음", JOB_ID))
                .doesNotThrowAnyException();
    }

    // 차단

    @Test
    @DisplayName("범용 클라이언트가 PDF 를 텍스트로 읽어 넘기면 거부한다")
    void pdfReadAsTextIsRejected() {
        // LLM 이 PDF 를 읽으려다 얻은 깨진 텍스트. Base64 디코딩은 성공했으나 내용이 PDF 가 아니다
        byte[] garbage = "%!PS-Adobe 또는 알 수 없는 내용".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> EgovFileSignatureValidator.validate(garbage, "guide.pdf", JOB_ID))
                .isInstanceOf(FileSignatureMismatchException.class)
                .hasMessageContaining("PDF 형식이 아닙니다");
    }

    @Test
    @DisplayName("확장자와 다른 형식의 바이트는 거부한다")
    void mismatchedFormatIsRejected() {
        // 내용은 멀쩡한 PDF 인데 파일명은 .docx
        assertThatThrownBy(() -> EgovFileSignatureValidator.validate(
                withTrailer(PDF_HEADER), "report.docx", JOB_ID))
                .isInstanceOf(FileSignatureMismatchException.class)
                .hasMessageContaining("DOCX 형식이 아닙니다");
    }

    @Test
    @DisplayName("HWP 자리에 ZIP 이 오면 거부한다 - hwp 와 hwpx 는 다른 형식이다")
    void hwpDoesNotAcceptZip() {
        assertThatThrownBy(() -> EgovFileSignatureValidator.validate(
                withTrailer(ZIP_HEADER), "report.hwp", JOB_ID))
                .isInstanceOf(FileSignatureMismatchException.class);
    }

    @Test
    @DisplayName("내용이 비어 있으면 거부한다")
    void emptyContentIsRejected() {
        assertThatThrownBy(() -> EgovFileSignatureValidator.validate(new byte[0], "guide.pdf", JOB_ID))
                .isInstanceOf(FileSignatureMismatchException.class)
                .hasMessageContaining("비어 있습니다");

        assertThatThrownBy(() -> EgovFileSignatureValidator.validate(null, "guide.pdf", JOB_ID))
                .isInstanceOf(FileSignatureMismatchException.class);
    }

    @Test
    @DisplayName("시그니처보다 짧은 내용은 거부한다")
    void contentShorterThanSignatureIsRejected() {
        assertThatThrownBy(() -> EgovFileSignatureValidator.validate(
                new byte[] { 0x25, 0x50 }, "guide.pdf", JOB_ID))
                .isInstanceOf(FileSignatureMismatchException.class);
    }

    // 진단 메시지

    @Test
    @DisplayName("오류 메시지에 jobId·파일명·실제 앞바이트와 대안 경로가 담긴다")
    void messageCarriesDiagnostics() {
        byte[] garbage = { 0x68, 0x65, 0x6C, 0x6C, 0x6F };   // "hello"

        assertThatThrownBy(() -> EgovFileSignatureValidator.validate(garbage, "guide.pdf", JOB_ID))
                .isInstanceOf(FileSignatureMismatchException.class)
                .satisfies(e -> {
                    String msg = e.getMessage();
                    assertThat(msg).contains(JOB_ID);
                    assertThat(msg).contains("guide.pdf");
                    assertThat(msg).contains("68656C6C6F");        // 실제로 무엇이 왔는지
                    assertThat(msg).contains("/ingest");           // 대안 안내
                    assertThat(msg).contains("reindex");
                });
    }

    @Test
    @DisplayName("안내 문구는 공용 상수를 그대로 쓴다 - 디코딩 실패 경로와 같은 문구여야 한다")
    void messageUsesSharedGuidance() {
        assertThatThrownBy(() -> EgovFileSignatureValidator.validate(
                "not a pdf".getBytes(StandardCharsets.UTF_8), "guide.pdf", JOB_ID))
                .hasMessageContaining(EgovFileSignatureValidator.BINARY_TRANSFER_GUIDANCE);
    }

    @Test
    @DisplayName("확장자 대소문자를 가리지 않는다")
    void extensionIsCaseInsensitive() {
        assertThatCode(() -> EgovFileSignatureValidator.validate(
                withTrailer(PDF_HEADER), "GUIDE.PDF", JOB_ID))
                .doesNotThrowAnyException();
    }

    /** 시그니처 뒤에 본문이 이어지는 형태로 만든다. */
    private static byte[] withTrailer(byte[] signature) {
        byte[] trailer = " ... 이어지는 본문".getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[signature.length + trailer.length];
        System.arraycopy(signature, 0, result, 0, signature.length);
        System.arraycopy(trailer, 0, result, signature.length, trailer.length);
        return result;
    }
}
