package com.example.ragknowledgebase.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins what the parser does and does not extract. The delivery documents state these limits to clients, so a
 * change in behaviour here must be reflected there.
 */
class DocumentParserTests {
    private final DocumentParser parser = new DocumentParser();

    @TempDir
    Path dir;

    @Test
    void docxBodyParagraphsAreExtractedButTableCellsAreNot() throws IOException {
        Path file = dir.resolve("sample.docx");
        try (XWPFDocument document = new XWPFDocument(); OutputStream out = Files.newOutputStream(file)) {
            document.createParagraph().createRun().setText("body paragraph text");
            XWPFTable table = document.createTable(1, 2);
            table.getRow(0).getCell(0).setText("table cell only");
            table.getRow(0).getCell(1).setText("second cell");
            document.write(out);
        }

        List<ParsedSection> sections = parser.parse(file, "docx");

        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).text()).contains("body paragraph text");
        assertThat(sections.get(0).text()).doesNotContain("table cell only").doesNotContain("second cell");
    }

    @Test
    void pdfKeepsTextPagesAndSilentlyDropsPagesWithoutATextLayer() throws IOException {
        Path file = dir.resolve("sample.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage textPage = new PDPage();
            document.addPage(textPage);
            try (PDPageContentStream content = new PDPageContentStream(document, textPage)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 700);
                content.showText("text layer page");
                content.endText();
            }
            // A page with no text layer, like a scanned image page: nothing for the text stripper to read.
            document.addPage(new PDPage());
            document.save(file.toFile());
        }

        List<ParsedSection> sections = parser.parse(file, "pdf");

        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).locator()).isEqualTo("p1");
        assertThat(sections.get(0).text()).contains("text layer page");
    }

    @Test
    void pdfWithoutAnyTextLayerYieldsNoSections() throws IOException {
        Path file = dir.resolve("scan.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.save(file.toFile());
        }

        assertThat(parser.parse(file, "pdf")).isEmpty();
    }
}
