package com.example.aichat.util;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

/**
 * 文档文本抽取:支持 .txt / .md / .pdf / .docx / .xlsx / .xls。
 * 说明:.doc(老 Word 二进制)POI 支持有限,提示另存为 .docx。
 */
public final class DocParser {

    private DocParser() {
    }

    /** 按扩展名抽取纯文本;不支持的格式抛 IllegalArgumentException */
    public static String extract(byte[] bytes, String filename) throws IOException {
        String fn = filename == null ? "" : filename.toLowerCase();
        if (fn.endsWith(".txt") || fn.endsWith(".md") || fn.endsWith(".markdown")) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        if (fn.endsWith(".pdf")) {
            return extractPdf(bytes);
        }
        if (fn.endsWith(".docx")) {
            return extractDocx(bytes);
        }
        if (fn.endsWith(".xlsx") || fn.endsWith(".xls")) {
            return extractExcel(bytes);
        }
        throw new IllegalArgumentException("暂不支持的文件类型:" + (filename == null ? "" : filename)
                + " — 支持 .txt / .md / .pdf / .docx / .xlsx(老版 .doc 请另存为 .docx)");
    }

    private static String extractPdf(byte[] bytes) throws IOException {
        try (PDDocument doc = PDDocument.load(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc);
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("PDF 未提取到文本(可能是扫描件,需 OCR,暂不支持)");
            }
            return text;
        }
    }

    private static String extractDocx(byte[] bytes) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (InputStream in = new ByteArrayInputStream(bytes);
             XWPFDocument doc = new XWPFDocument(in)) {
            for (XWPFParagraph p : doc.getParagraphs()) {
                appendLine(sb, p.getText());
            }
            for (XWPFTable table : doc.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    StringBuilder line = new StringBuilder();
                    for (XWPFTableCell cell : row.getTableCells()) {
                        StringBuilder cellText = new StringBuilder();
                        for (XWPFParagraph p : cell.getParagraphs()) {
                            for (XWPFRun r : p.getRuns()) {
                                cellText.append(r.getText(0));
                            }
                        }
                        if (!cellText.toString().isBlank()) {
                            line.append(cellText).append(" | ");
                        }
                    }
                    appendLine(sb, line.toString());
                }
            }
        }
        if (sb.isEmpty()) {
            throw new IllegalArgumentException("Word 文档未提取到文本");
        }
        return sb.toString();
    }

    private static String extractExcel(byte[] bytes) throws IOException {
        StringBuilder sb = new StringBuilder();
        DataFormatter fmt = new DataFormatter();
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            for (Sheet sheet : wb) {
                sb.append("【").append(sheet.getSheetName()).append("】\n");
                Iterator<Row> rows = sheet.rowIterator();
                while (rows.hasNext()) {
                    Row row = rows.next();
                    StringBuilder line = new StringBuilder();
                    Iterator<Cell> cells = row.cellIterator();
                    while (cells.hasNext()) {
                        String v = fmt.formatCellValue(cells.next());
                        if (!v.isBlank()) {
                            line.append(v).append(" | ");
                        }
                    }
                    appendLine(sb, line.toString());
                }
            }
        }
        if (sb.isEmpty()) {
            throw new IllegalArgumentException("Excel 未提取到文本");
        }
        return sb.toString();
    }

    private static void appendLine(StringBuilder sb, String line) {
        if (line != null && !line.isBlank()) {
            sb.append(line.trim()).append('\n');
        }
    }
}
