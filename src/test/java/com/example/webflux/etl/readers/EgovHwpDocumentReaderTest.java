package com.example.webflux.etl.readers;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.test.util.ReflectionTestUtils;

import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.bodytext.ParagraphListInterface;
import kr.dogfoot.hwplib.object.bodytext.Section;
import kr.dogfoot.hwplib.object.bodytext.control.ControlTable;
import kr.dogfoot.hwplib.object.bodytext.control.ControlType;
import kr.dogfoot.hwplib.object.bodytext.control.table.Cell;
import kr.dogfoot.hwplib.object.bodytext.control.table.Row;
import kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph;
import kr.dogfoot.hwplib.tool.blankfilemaker.BlankFileMaker;
import kr.dogfoot.hwplib.writer.HWPWriter;

/**
 * EgovHwpDocumentReader가 실제 .hwp 파일을 읽어 표 구조까지 보존한 본문과
 * 메타데이터를 올바르게 구성하는지 검증한다.
 *
 * hwplib으로 문서를 메모리에서 만들어 디스크에 쓴 뒤, 리더가 실제로 그 파일을
 * kr.dogfoot.hwplib.reader.HWPReader로 읽어 들이는 전 과정을 검증한다.
 */
class EgovHwpDocumentReaderTest {

    @TempDir
    Path baseDir;

    @Test
    @DisplayName("HWP 파일에서 문단과 표 구조를 보존한 본문을 읽는다")
    void readsStructuredTextFromHwp() throws Exception {
        writeHwp("운영지침.hwp");

        List<Document> documents = read("*.hwp");

        assertThat(documents).hasSize(1);
        Document document = documents.get(0);
        assertThat(document.getText())
                .contains("제1조(목적) 이 규정은 수수료 기준을 정함을 목적으로 한다")
                .contains("민원종류 | 수수료 | 처리기간")
                .contains("주민등록등본 | 400원 | 즉시");
        assertThat(document.getMetadata().get("type")).isEqualTo("hwp");
        assertThat(document.getMetadata().get("source")).isEqualTo("운영지침.hwp");
        assertThat(document.getMetadata().get("page_number")).isEqualTo(1);
        assertThat(document.getId()).isEqualTo("hwp-운영지침.hwp_1");
    }

    @Test
    @DisplayName("경로가 설정되지 않으면 건너뛴다")
    void skipsWhenPathNotConfigured() {
        EgovHwpDocumentReader reader = new EgovHwpDocumentReader();
        ReflectionTestUtils.setField(reader, "hwpDocumentPath", null);

        assertThat(reader.get()).isEmpty();
    }

    private void addParagraph(ParagraphListInterface list, String text) throws Exception {
        Paragraph paragraph = list.addNewParagraph();
        paragraph.createText();
        paragraph.getText().addString(text);
    }

    private void writeHwp(String filename) throws Exception {
        HWPFile file = BlankFileMaker.make();
        Section section = file.getBodyText().getSectionList().get(0);
        addParagraph(section, "제1조(목적) 이 규정은 수수료 기준을 정함을 목적으로 한다");

        Paragraph tableParagraph = section.addNewParagraph();
        tableParagraph.createText();
        // 제어 문자를 넣어야 표 컨트롤이 문단에 연결된다
        tableParagraph.getText().addExtendCharForTable();
        ControlTable table = (ControlTable) tableParagraph.addNewControl(ControlType.Table);

        String[][] rows = {
                {"민원종류", "수수료", "처리기간"},
                {"주민등록등본", "400원", "즉시"}};
        for (String[] row : rows) {
            Row tableRow = table.addNewRow();
            for (String cell : row) {
                Cell tableCell = tableRow.addNewCell();
                addParagraph(tableCell.getParagraphList(), cell);
            }
        }

        HWPWriter.toFile(file, baseDir.resolve(filename).toAbsolutePath().toString());
    }

    private List<Document> read(String glob) {
        EgovHwpDocumentReader reader = new EgovHwpDocumentReader();
        String pattern = "file:" + baseDir.toAbsolutePath().toString().replace('\\', '/') + "/" + glob;
        ReflectionTestUtils.setField(reader, "hwpDocumentPath", pattern);
        return reader.get();
    }
}
