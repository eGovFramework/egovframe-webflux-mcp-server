package com.example.webflux.etl.readers;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.List;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * EgovDocxDocumentReader가 실제 .docx 파일을 읽어 본문과 메타데이터를
 * 올바르게 구성하는지 검증한다.
 */
class EgovDocxDocumentReaderTest {

    @TempDir
    Path baseDir;

    @Test
    @DisplayName("DOCX 파일에서 본문과 메타데이터를 읽는다")
    void readsTextAndMetadataFromDocx() throws Exception {
        writeDocx("운영지침.docx", "제1조(목적) 이 규정은 목적을 정한다", "제2조(적용범위) 모든 부서에 적용한다");

        List<Document> documents = read("*.docx");

        assertThat(documents).hasSize(1);
        Document document = documents.get(0);
        assertThat(document.getText())
                .contains("제1조(목적) 이 규정은 목적을 정한다")
                .contains("제2조(적용범위) 모든 부서에 적용한다");
        assertThat(document.getMetadata().get("type")).isEqualTo("docx");
        assertThat(document.getMetadata().get("source")).isEqualTo("운영지침.docx");
        assertThat(document.getMetadata().get("file_name")).isEqualTo("운영지침.docx");
        assertThat(document.getId()).isEqualTo("docx-운영지침.docx");
    }

    @Test
    @DisplayName("경로가 설정되지 않으면 건너뛴다")
    void skipsWhenPathNotConfigured() {
        EgovDocxDocumentReader reader = new EgovDocxDocumentReader();
        ReflectionTestUtils.setField(reader, "docxDocumentPath", null);

        assertThat(reader.get()).isEmpty();
    }

    private void writeDocx(String filename, String... paragraphs) throws Exception {
        try (XWPFDocument doc = new XWPFDocument();
             FileOutputStream out = new FileOutputStream(baseDir.resolve(filename).toFile())) {
            for (String text : paragraphs) {
                XWPFParagraph paragraph = doc.createParagraph();
                XWPFRun run = paragraph.createRun();
                run.setText(text);
            }
            doc.write(out);
        }
    }

    private List<Document> read(String glob) {
        EgovDocxDocumentReader reader = new EgovDocxDocumentReader();
        String pattern = "file:" + baseDir.toAbsolutePath().toString().replace('\\', '/') + "/" + glob;
        ReflectionTestUtils.setField(reader, "docxDocumentPath", pattern);
        return reader.get();
    }
}
