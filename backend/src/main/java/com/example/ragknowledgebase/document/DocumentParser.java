package com.example.ragknowledgebase.document;

import com.example.ragknowledgebase.common.BusinessException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Service;

@Service
public class DocumentParser {
    private static final int MIN_PDF_NON_WHITESPACE_CHARS = 20;
    public List<ParsedSection> parse(Path path, String fileType) {
        String normalized = fileType == null ? "" : fileType.toLowerCase(Locale.ROOT);
        try {
            return switch (normalized) {
                case "pdf" -> parsePdf(path);
                case "docx" -> parseDocx(path);
                case "txt", "md" -> parseText(path);
                default -> throw new BusinessException(400, "不支持的文档类型");
            };
        } catch (IOException ex) {
            throw new BusinessException(500, "文档解析失败，请检查文件内容");
        }
    }

    private List<ParsedSection> parseText(Path path) throws IOException {
        String text = Files.readString(path, StandardCharsets.UTF_8);
        return List.of(new ParsedSection("chunk#1", text));
    }

    private List<ParsedSection> parseDocx(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path);
             XWPFDocument document = new XWPFDocument(input)) {
            StringBuilder builder = new StringBuilder();
            document.getParagraphs().forEach(paragraph -> {
                String text = paragraph.getText();
                if (text != null && !text.isBlank()) {
                    builder.append(text).append("\n\n");
                }
            });
            return List.of(new ParsedSection("chunk#1", builder.toString()));
        }
    }

    private List<ParsedSection> parsePdf(Path path) throws IOException {
        try (PDDocument document = Loader.loadPDF(path.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            List<ParsedSection> sections = new ArrayList<>();
            int nonWhitespaceChars = 0;
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(document);
                if (text != null && !text.isBlank()) {
                    sections.add(new ParsedSection("p" + page, text));
                    nonWhitespaceChars += countNonWhitespace(text);
                }
            }
            if (document.getNumberOfPages() > 0 && nonWhitespaceChars < MIN_PDF_NON_WHITESPACE_CHARS) {
                throw new BusinessException(
                    422,
                    "PDF 未提取到有效文本（疑似纯扫描件），当前需先 OCR 转换后再上传"
                );
            }
            return sections;
        }
    }

    private static int countNonWhitespace(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isWhitespace(text.charAt(i))) {
                count++;
            }
        }
        return count;
    }
}
