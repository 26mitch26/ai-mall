package com.ai.mall.agent.customer.service.rag;

import com.ai.mall.agent.customer.model.Document;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 知识文件解析管线测试：PDF / Word / HTML / TXT 现场构造并反向解析验证，
 * 验证"网页符号、PDF 非结构化内容 → 清洗后的纯文本知识"的自动化能力。
 */
class KnowledgeParserTest {

    private final KnowledgeFileParser parser = new KnowledgeFileParser();

    /** 用 pdfbox 现场生成一份 PDF（英文文本，规避中文字体嵌入问题），再解析 */
    @Test
    void parsePdf() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument pdf = new PDDocument()) {
            PDPage page = new PDPage();
            pdf.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(pdf, page)) {
                cs.beginText();
                cs.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 720);
                cs.showText("AI Mall Refund Policy");
                cs.newLineAtOffset(0, -20);
                cs.showText("Orders can be returned within 7 days of delivery.");
                cs.endText();
            }
            pdf.save(out);
        }
        Document doc = parser.parse(new ByteArrayInputStream(out.toByteArray()), "退款政策.pdf");
        assertNotNull(doc);
        assertTrue(doc.getContent().contains("AI Mall Refund Policy"), "PDF 正文应被抽取");
        assertTrue(doc.getContent().contains("returned within 7 days"), "PDF 多行文本应被抽取");
        assertTrue("pdf".equals(doc.getType()), "类型应为 pdf");
    }

    /** HTML：脚本/样式/装饰符号应被剥掉，只剩正文 */
    @Test
    void parseHtml() throws Exception {
        String html = """
                <!DOCTYPE html>
                <html><head><title>运费规则</title>
                <style>.hint { color: red; }</style>
                <script>alert('no');</script></head>
                <body>
                <h1>满 99 元包邮</h1>
                <p>不足 99 元，普通地区运费 8 元</p>
                <ul><li>冷链生鲜单独计费</li><li>大件走专线物流</li></ul>
                </body></html>
                """;
        Document doc = parser.parse(
                new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)), "shipping.html");
        assertFalse(doc.getContent().contains("<"), "HTML 标签应被剥离");
        assertFalse(doc.getContent().contains("script"), "脚本应被移除");
        assertTrue(doc.getContent().contains("满 99 元包邮"), "标题正文应保留");
        assertTrue(doc.getContent().contains("冷链生鲜单独计费"), "列表正文应保留");
        assertTrue("html".equals(doc.getType()));
    }

    /** Word：段落 + 表格单元格都应被抽成文本 */
    @Test
    void parseWord() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XWPFDocument xdoc = new XWPFDocument()) {
            XWPFParagraph p = xdoc.createParagraph();
            p.createRun().setText("换货流程说明");
            XWPFTable table = xdoc.createTable(2, 2);
            XWPFTableRow row0 = table.getRow(0);
            row0.getCell(0).setText("场景");
            row0.getCell(1).setText("运费承担");
            XWPFTableRow row1 = table.getRow(1);
            row1.getCell(0).setText("质量问题");
            row1.getCell(1).setText("商家承担");
            xdoc.write(out);
        }
        Document doc = parser.parse(new ByteArrayInputStream(out.toByteArray()), "售后.docx");
        assertTrue(doc.getContent().contains("换货流程说明"), "Word 段落应被抽取");
        assertTrue(doc.getContent().contains("商家承担"), "Word 表格单元格应被抽取");
        assertTrue("word".equals(doc.getType()));
    }

    /** TXT：原样读取并清洗装饰符号 */
    @Test
    void parseText() throws Exception {
        String txt = "会员生日券 8 折，有效期 30 天。\r\n◆ 每个账号限领 1 张 ● 不可转赠";
        Document doc = parser.parse(
                new ByteArrayInputStream(txt.getBytes(StandardCharsets.UTF_8)), "会员权益.txt");
        assertTrue(doc.getContent().contains("会员生日券 8 折"), "TXT 内容应原样读取");
        assertFalse(doc.getContent().contains("◆"), "装饰符号应被清洗");
        assertFalse(doc.getContent().contains("●"), "装饰符号应被清洗");
        assertTrue("text".equals(doc.getType()));
    }
}