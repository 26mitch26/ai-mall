package com.ai.mall.agent.customer.service.rag;

import com.ai.mall.agent.customer.model.Document;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;

/**
 * 非结构化知识文件解析器（PDF / Word / HTML / TXT → Document）
 * <p>
 * 解决简历上"网页和 PDF 等非结构化内容如何自动化为 RAG 可用知识"的落地：
 * - PDF：pdfbox 逐页抽取文本
 * - Word(.docx)：poi-ooxml 抽取段落 + 表格单元格
 * - HTML：jsoup 去除标签/脚本/样式，只保留正文文本（"符号自动化处理"）
 * - TXT/Markdown：按 UTF-8 读取
 * <p>
 * 输出统一为生产 {@link Document}（id/content/source/type），
 * 可直接交给 {@link RagService#indexDocuments} 完成分块 → 双路索引。
 * 解析后的文本经过统一清洗：去控制字符、压缩空白、移除残留标记。
 */
@Service
public class KnowledgeFileParser {

    /** 解析知识文件为生产 Document（内部已清洗） */
    public Document parse(InputStream in, String originalFilename) throws IOException {
        String type = resolveType(originalFilename);
        String raw = extract(in, type, originalFilename);
        String cleaned = clean(raw);
        return Document.builder()
                .id(normalizeId(originalFilename))
                .content(cleaned)
                .source(originalFilename)
                .type(type)
                .build();
    }

    /** 按类型抽取原始文本 */
    private String extract(InputStream in, String type, String filename) throws IOException {
        return switch (type) {
            case "pdf" -> extractPdf(in);
            case "word" -> extractWord(in);
            case "html" -> extractHtml(in);
            default -> extractPlainText(in);
        };
    }

    private String extractPdf(InputStream in) throws IOException {
        try (PDDocument pdf = Loader.loadPDF(new RandomAccessReadBuffer(in))) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(pdf);
            if (text == null || text.isBlank()) {
                throw new IOException("PDF 无可抽取文本，可能为扫描件：" + text);
            }
            return text;
        }
    }

    private String extractWord(InputStream in) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(in)) {
            StringBuilder sb = new StringBuilder();
            for (XWPFParagraph p : doc.getParagraphs()) {
                if (!p.getText().isBlank()) {
                    sb.append(p.getText().trim()).append('\n');
                }
            }
            for (XWPFTable table : doc.getTables()) {
                table.getRows().forEach(row -> {
                    StringBuilder line = new StringBuilder();
                    row.getTableCells().forEach(cell -> line.append(cell.getText().trim()).append("｜"));
                    if (line.length() > 0) {
                        sb.append(line).append('\n');
                    }
                });
            }
            return sb.toString();
        }
    }

    private String extractHtml(InputStream in) throws IOException {
        org.jsoup.nodes.Document html = Jsoup.parse(in, "UTF-8", "");
        html.select("script, style, noscript, iframe, svg").remove();
        // 保留块级结构换行，正文文字用 .text() 归一化
        StringBuilder sb = new StringBuilder();
        for (Element el : html.select("h1, h2, h3, h4, p, li, td, th, div, article, section")) {
            String t = el.text().trim();
            if (!t.isBlank()) {
                sb.append(t).append('\n');
            }
        }
        return sb.toString();
    }

    private String extractPlainText(InputStream in) throws IOException {
        return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * 统一清洗：
     * 1. 剔除控制字符（保留 \n \t）
     * 2. 压缩连续空白与空行
     * 3. 移除残留的 HTML 标签与常见 PDF/网页装饰符号
     */
    private String clean(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw
                // 去除标签属性在内的残余 HTML 标签
                .replaceAll("<[^>]+>", " ")
                // 保留 \n\t，去掉其余控制字符
                .replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", " ")
                // 常见装饰符号归一为空格（•・●▶★☆◆◇■□▲▼※→←↑↓ 等）
                .replaceAll("[•・●▶★☆◆◇■□▲▼※❖▸◦▪▫✗✔✓→←↑↓\\|_~^]", " ")
                // 压缩连续空白
                .replaceAll("[ \t\u00A0]+", " ")
                // 压缩连续空行
                .replaceAll("\\n\\s*\\n+", "\n")
                .trim();
        return s;
    }

    private String resolveType(String filename) {
        String lower = filename == null ? "" : filename.toLowerCase();
        if (lower.endsWith(".pdf")) return "pdf";
        if (lower.endsWith(".docx") || lower.endsWith(".doc")) return "word";
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "html";
        return "text";
    }

    /** 文件名 → 稳定 id（保留可读性，去掉不安全字符） */
    private String normalizeId(String filename) {
        String base = filename == null ? "doc" : filename.replaceAll("\\.(pdf|docx|doc|html|htm|txt|md)$", "");
        String safe = base.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5_-]", "_");
        return safe + "_" + LocalDate.now().hashCode();
    }
}