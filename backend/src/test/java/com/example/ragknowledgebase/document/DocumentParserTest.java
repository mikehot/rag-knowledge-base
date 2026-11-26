package com.example.ragknowledgebase.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ragknowledgebase.common.BusinessException;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentParserTest {

    private final DocumentParser parser = new DocumentParser();

    @Test
    void rejectsScannedOrTextlessPdfWithClearError(@TempDir Path tempDir) throws Exception {
        Path pdfPath = tempDir.resolve("scanned.pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            doc.save(pdfPath.toFile());
        }

        assertThatThrownBy(() -> parser.parse(pdfPath, "pdf"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("疑似纯扫描件");
    }

    @Test
    void parsesPdfWithSufficientText(@TempDir Path tempDir) throws Exception {
        Path pdfPath = tempDir.resolve("normal.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(50, 700);
                stream.showText("Enterprise RAG warranty policy: valid for 24 months.");
                stream.endText();
            }
            doc.save(pdfPath.toFile());
        }

        List<ParsedSection> sections = parser.parse(pdfPath, "pdf");
        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).locator()).isEqualTo("p1");
        assertThat(sections.get(0).text()).contains("Enterprise RAG warranty policy");
    }
}
