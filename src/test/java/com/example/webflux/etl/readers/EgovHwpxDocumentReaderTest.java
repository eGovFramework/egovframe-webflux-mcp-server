package com.example.webflux.etl.readers;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.test.util.ReflectionTestUtils;

import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.object.content.section_xml.SectionXMLFile;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Para;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Run;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.T;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Table;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.table.Tc;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.table.Tr;
import kr.dogfoot.hwpxlib.tool.blankfilemaker.BlankFileMaker;
import kr.dogfoot.hwpxlib.writer.HWPXWriter;

/**
 * EgovHwpxDocumentReader가 실제 .hwpx 파일을 읽어 표 구조까지 보존한 본문과
 * 메타데이터를 올바르게 구성하는지 검증한다.
 */
class EgovHwpxDocumentReaderTest {

    @TempDir
    Path baseDir;

    @Test
    @DisplayName("HWPX 파일에서 문단과 표 구조를 보존한 본문을 읽는다")
    void readsStructuredTextFromHwpx() throws Exception {
        writeHwpx("운영지침.hwpx");

        List<Document> documents = read("*.hwpx");

        assertThat(documents).hasSize(1);
        Document document = documents.get(0);
        assertThat(document.getText())
                .contains("제1조(목적) 이 규정은 수수료 기준을 정함을 목적으로 한다")
                .contains("민원종류 | 수수료 | 처리기간")
                .contains("주민등록등본 | 400원 | 즉시");
        assertThat(document.getMetadata().get("type")).isEqualTo("hwpx");
        assertThat(document.getMetadata().get("source")).isEqualTo("운영지침.hwpx");
        assertThat(document.getId()).isEqualTo("hwpx-운영지침.hwpx");
    }

    @Test
    @DisplayName("경로가 설정되지 않으면 건너뛴다")
    void skipsWhenPathNotConfigured() {
        EgovHwpxDocumentReader reader = new EgovHwpxDocumentReader();
        ReflectionTestUtils.setField(reader, "hwpxDocumentPath", null);

        assertThat(reader.get()).isEmpty();
    }

    private void addText(Para para, String text) {
        Run run = para.addNewRun();
        run.charPrIDRefAnd("0");
        T t = run.addNewT();
        t.addText(text);
    }

    private void addCell(Tr row, String text) {
        Tc cell = row.addNewTc();
        cell.createSubList();
        addText(cell.subList().addNewPara(), text);
    }

    private void writeHwpx(String filename) throws Exception {
        HWPXFile file = BlankFileMaker.make();
        SectionXMLFile section = file.sectionXMLFileList().get(0);
        addText(section.addNewPara(), "제1조(목적) 이 규정은 수수료 기준을 정함을 목적으로 한다");

        Para tableParagraph = section.addNewPara();
        Run run = tableParagraph.addNewRun();
        run.charPrIDRefAnd("0");
        Table table = run.addNewTable();
        String[][] rows = {
                {"민원종류", "수수료", "처리기간"},
                {"주민등록등본", "400원", "즉시"}};
        for (String[] row : rows) {
            Tr tr = table.addNewTr();
            for (String cell : row) {
                addCell(tr, cell);
            }
        }

        HWPXWriter.toFilepath(file, baseDir.resolve(filename).toAbsolutePath().toString());
    }

    private List<Document> read(String glob) {
        EgovHwpxDocumentReader reader = new EgovHwpxDocumentReader();
        String pattern = "file:" + baseDir.toAbsolutePath().toString().replace('\\', '/') + "/" + glob;
        ReflectionTestUtils.setField(reader, "hwpxDocumentPath", pattern);
        return reader.get();
    }
}
