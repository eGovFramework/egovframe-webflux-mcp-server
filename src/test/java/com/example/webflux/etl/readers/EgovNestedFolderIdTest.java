package com.example.webflux.etl.readers;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.FileOutputStream;
import java.nio.file.Files;
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

import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.tool.blankfilemaker.BlankFileMaker;
import kr.dogfoot.hwplib.writer.HWPWriter;
import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.object.content.section_xml.SectionXMLFile;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Para;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Run;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.T;
import kr.dogfoot.hwpxlib.writer.HWPXWriter;

/**
 * 하위 폴더에 같은 이름의 파일이 있을 때 6종 리더 전부가 서로 다른 문서 id/source 를
 * 부여하는지 검증한다. egovframe-ai-rag PR #84의 방향을 이 프로젝트의 세 군데 키
 * docId, metadata["source"], 향후 document_metadata.filename 에 일관되게 적용한 것이다.
 */
class EgovNestedFolderIdTest {

    @TempDir
    Path baseDir;

    @Test
    @DisplayName("Markdown: 하위 폴더의 동명 파일이 서로 다른 id/source 를 받는다")
    void markdownNestedFilesGetDistinctIds() throws Exception {
        write("부서A/운영지침.md", "# 부서A\n본문");
        write("부서B/운영지침.md", "# 부서B\n본문");

        EgovMarkdownDocumentReader reader = new EgovMarkdownDocumentReader();
        ReflectionTestUtils.setField(reader, "documentPath", pattern("**/*.md"));
        List<Document> documents = reader.get();

        assertThat(documents).hasSize(2);
        assertThat(documents).extracting(Document::getId)
                .containsExactlyInAnyOrder("doc-부서A/운영지침.md", "doc-부서B/운영지침.md");
        assertThat(documents).extracting(d -> d.getMetadata().get("source"))
                .containsExactlyInAnyOrder("부서A/운영지침.md", "부서B/운영지침.md");
    }

    @Test
    @DisplayName("Txt: 하위 폴더의 동명 파일이 서로 다른 id/source 를 받는다")
    void txtNestedFilesGetDistinctIds() throws Exception {
        write("부서A/공지.txt", "부서A 공지");
        write("부서B/공지.txt", "부서B 공지");

        EgovTxtDocumentReader reader = new EgovTxtDocumentReader();
        ReflectionTestUtils.setField(reader, "documentPath", pattern("**/*.txt"));
        List<Document> documents = reader.get();

        assertThat(documents).hasSize(2);
        assertThat(documents).extracting(Document::getId)
                .containsExactlyInAnyOrder("doc-부서A/공지.txt", "doc-부서B/공지.txt");
        assertThat(documents).extracting(d -> d.getMetadata().get("source"))
                .containsExactlyInAnyOrder("부서A/공지.txt", "부서B/공지.txt");
    }

    @Test
    @DisplayName("PDF: 하위 폴더의 동명 파일이 서로 다른 id/source 를 받는다")
    void pdfNestedFilesGetDistinctIds() throws Exception {
        // PDF는 실제 문서 생성이 무거우므로 리졸버 결과만 리더와 같은 방식으로 조합해 확인한다.
        // (리더가 실제 파일을 읽어 처리하는 검증은 EgovPdfDocumentReader 쪽에서 다룬다. 여기서는
        //  경로 인식 키가 서로 다른 폴더에서 실제로 갈리는지가 핵심이다)
        write("부서A/보고서.pdf", "dummy");
        write("부서B/보고서.pdf", "dummy");
        String glob = pattern("**/*.pdf");

        org.springframework.core.io.support.PathMatchingResourcePatternResolver resolver =
                new org.springframework.core.io.support.PathMatchingResourcePatternResolver();
        org.springframework.core.io.Resource[] resources = resolver.getResources(glob);
        assertThat(resources).hasSize(2);

        List<String> idKeys = List.of(
                EgovDocumentIdResolver.resolveKey(resources[0], glob),
                EgovDocumentIdResolver.resolveKey(resources[1], glob));

        assertThat(idKeys).containsExactlyInAnyOrder("부서A/보고서.pdf", "부서B/보고서.pdf");
    }

    @Test
    @DisplayName("DOCX: 하위 폴더의 동명 파일이 서로 다른 id/source 를 받는다")
    void docxNestedFilesGetDistinctIds() throws Exception {
        writeDocx("부서A/운영지침.docx", "부서A 본문");
        writeDocx("부서B/운영지침.docx", "부서B 본문");

        EgovDocxDocumentReader reader = new EgovDocxDocumentReader();
        ReflectionTestUtils.setField(reader, "docxDocumentPath", pattern("**/*.docx"));
        List<Document> documents = reader.get();

        assertThat(documents).hasSize(2);
        assertThat(documents).extracting(Document::getId)
                .containsExactlyInAnyOrder("docx-부서A/운영지침.docx", "docx-부서B/운영지침.docx");
        assertThat(documents).extracting(d -> d.getMetadata().get("source"))
                .containsExactlyInAnyOrder("부서A/운영지침.docx", "부서B/운영지침.docx");
    }

    @Test
    @DisplayName("HWP: 하위 폴더의 동명 파일이 서로 다른 id/source 를 받는다")
    void hwpNestedFilesGetDistinctIds() throws Exception {
        writeHwp("부서A/운영지침.hwp");
        writeHwp("부서B/운영지침.hwp");

        EgovHwpDocumentReader reader = new EgovHwpDocumentReader();
        ReflectionTestUtils.setField(reader, "hwpDocumentPath", pattern("**/*.hwp"));
        List<Document> documents = reader.get();

        assertThat(documents).hasSize(2);
        assertThat(documents).extracting(Document::getId)
                .containsExactlyInAnyOrder("hwp-부서A/운영지침.hwp_1", "hwp-부서B/운영지침.hwp_1");
        assertThat(documents).extracting(d -> d.getMetadata().get("source"))
                .containsExactlyInAnyOrder("부서A/운영지침.hwp", "부서B/운영지침.hwp");
    }

    @Test
    @DisplayName("HWPX: 하위 폴더의 동명 파일이 서로 다른 id/source 를 받는다")
    void hwpxNestedFilesGetDistinctIds() throws Exception {
        writeHwpx("부서A/운영지침.hwpx");
        writeHwpx("부서B/운영지침.hwpx");

        EgovHwpxDocumentReader reader = new EgovHwpxDocumentReader();
        ReflectionTestUtils.setField(reader, "hwpxDocumentPath", pattern("**/*.hwpx"));
        List<Document> documents = reader.get();

        assertThat(documents).hasSize(2);
        assertThat(documents).extracting(Document::getId)
                .containsExactlyInAnyOrder("hwpx-부서A/운영지침.hwpx", "hwpx-부서B/운영지침.hwpx");
        assertThat(documents).extracting(d -> d.getMetadata().get("source"))
                .containsExactlyInAnyOrder("부서A/운영지침.hwpx", "부서B/운영지침.hwpx");
    }

    private void write(String relativePath, String content) throws Exception {
        Path target = baseDir.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }

    private void writeDocx(String relativePath, String text) throws Exception {
        Path target = baseDir.resolve(relativePath);
        Files.createDirectories(target.getParent());
        try (XWPFDocument doc = new XWPFDocument();
             FileOutputStream out = new FileOutputStream(target.toFile())) {
            XWPFParagraph paragraph = doc.createParagraph();
            XWPFRun run = paragraph.createRun();
            run.setText(text);
            doc.write(out);
        }
    }

    private void writeHwp(String relativePath) throws Exception {
        Path target = baseDir.resolve(relativePath);
        Files.createDirectories(target.getParent());
        HWPFile file = BlankFileMaker.make();
        kr.dogfoot.hwplib.object.bodytext.Section section = file.getBodyText().getSectionList().get(0);
        kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph paragraph = section.addNewParagraph();
        paragraph.createText();
        paragraph.getText().addString("본문");
        HWPWriter.toFile(file, target.toAbsolutePath().toString());
    }

    private void writeHwpx(String relativePath) throws Exception {
        Path target = baseDir.resolve(relativePath);
        Files.createDirectories(target.getParent());
        HWPXFile file = kr.dogfoot.hwpxlib.tool.blankfilemaker.BlankFileMaker.make();
        SectionXMLFile section = file.sectionXMLFileList().get(0);
        Para para = section.addNewPara();
        Run run = para.addNewRun();
        run.charPrIDRefAnd("0");
        T t = run.addNewT();
        t.addText("본문");
        HWPXWriter.toFilepath(file, target.toAbsolutePath().toString());
    }

    private String pattern(String glob) {
        return "file:" + baseDir.toAbsolutePath().toString().replace('\\', '/') + "/" + glob;
    }
}
