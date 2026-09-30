package com.tutor.knowledge;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 企业级多格式文档结构化解析服务：
 * 1. Word (.docx)：通过 Apache POI 解析 Heading 标题样式与表格，转为规范 Markdown；
 * 2. PDF (.pdf)：通过 PDFBox 3.x 提取结构化纯文本并去噪；
 * 3. Markdown / TXT：直接标准化规整；
 * 4. 自动注入 YAML Frontmatter 与面包屑路径上下文。
 */
@Slf4j
@Service
public class DocumentParserService {

    public record ParsedDocument(
            String filename,
            String category,
            String kp,
            String source,
            String markdownContent,
            List<ParsedChunk> chunks
    ) {}

    public record ParsedChunk(
            String title,
            String breadcrumb,
            String text,
            int tokenEstimate
    ) {}

    /**
     * 将上传的文件解析为带有 Frontmatter 的标准化 Markdown，并提取带面包屑的切片预览
     */
    public ParsedDocument parse(InputStream in, String filename, String category, String kp, String source) throws Exception {
        byte[] bytes = in.readAllBytes();
        String ext = FileUtil.extName(filename).toLowerCase();
        String rawBody;

        switch (ext) {
            case "docx" -> rawBody = parseDocx(bytes);
            case "pdf" -> rawBody = parsePdf(bytes);
            case "md", "markdown" -> rawBody = cleanMarkdown(new String(bytes, StandardCharsets.UTF_8));
            default -> rawBody = cleanPlainText(new String(bytes, StandardCharsets.UTF_8), filename);
        }

        String safeKp = StrUtil.isNotBlank(kp) ? kp.trim() : "综合知识";
        String safeCat = StrUtil.isNotBlank(category) ? category.trim().toUpperCase() : "SKILL";
        String safeSource = StrUtil.isNotBlank(source) ? source.trim() : filename;

        // 确保包含至少一个二级标题 (##) 以便底层以 ## 为语义切片
        String structuredBody = ensureHeadings(rawBody, filename);

        // 拼接标准 YAML Frontmatter
        String fullMarkdown = buildFrontmatter(safeKp, safeCat, safeSource) + "\n" + structuredBody;

        // 提取带面包屑路径的切片预览
        List<ParsedChunk> chunks = extractChunksWithBreadcrumbs(filename, structuredBody);

        return new ParsedDocument(filename, safeCat, safeKp, safeSource, fullMarkdown, chunks);
    }

    /**
     * 解析 Word .docx 文件：提取段落标题与表格
     */
    private String parseDocx(byte[] bytes) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            for (IBodyElement elem : doc.getBodyElements()) {
                if (elem instanceof XWPFParagraph para) {
                    String text = para.getText().trim();
                    if (text.isBlank()) continue;

                    String style = para.getStyle();
                    if (style != null && (style.equalsIgnoreCase("1") || style.toLowerCase().contains("heading 1") || style.contains("标题 1"))) {
                        sb.append("\n# ").append(text).append("\n\n");
                    } else if (style != null && (style.equalsIgnoreCase("2") || style.toLowerCase().contains("heading 2") || style.contains("标题 2"))) {
                        sb.append("\n## ").append(text).append("\n\n");
                    } else if (style != null && (style.equalsIgnoreCase("3") || style.toLowerCase().contains("heading 3") || style.contains("标题 3"))) {
                        sb.append("\n### ").append(text).append("\n\n");
                    } else {
                        sb.append(text).append("\n\n");
                    }
                } else if (elem instanceof XWPFTable table) {
                    sb.append(renderMarkdownTable(table)).append("\n\n");
                }
            }
        }
        return sb.toString().trim();
    }

    /**
     * 将 XWPFTable 转换为标准的 Markdown 表格
     */
    private String renderMarkdownTable(XWPFTable table) {
        StringBuilder sb = new StringBuilder();
        List<XWPFTableRow> rows = table.getRows();
        if (rows.isEmpty()) return "";

        // 1. 表头
        XWPFTableRow header = rows.get(0);
        List<String> headers = header.getTableCells().stream().map(c -> c.getText().trim().replace("|", "\\|")).toList();
        sb.append("| ").append(String.join(" | ", headers)).append(" |\n");
        sb.append("|").append(" --- |".repeat(headers.size())).append("\n");

        // 2. 数据行
        for (int i = 1; i < rows.size(); i++) {
            List<String> cells = rows.get(i).getTableCells().stream().map(c -> c.getText().trim().replace("|", "\\|")).toList();
            sb.append("| ").append(String.join(" | ", cells)).append(" |\n");
        }
        return sb.toString();
    }

    /**
     * 解析 PDF 文件：提取纯文本去噪
     */
    private String parsePdf(byte[] bytes) throws Exception {
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String rawText = stripper.getText(doc);
            return cleanPlainText(rawText, "PDF Document");
        }
    }

    /**
     * 清洗文本中的页眉、页脚与常见格式噪点
     */
    private String cleanPlainText(String text, String defaultTitle) {
        if (StrUtil.isBlank(text)) return "";
        // 过滤页码正则，如 "第 1 页 共 10 页" 或 "- 1 -"
        String cleaned = text.replaceAll("(?m)^\\s*(第\\s*\\d+\\s*页.*|\\-\\s*\\d+\\s*\\-|Page\\s*\\d+.*)$\\n?", "");
        return cleaned.trim();
    }

    /**
     * 清洗 Markdown 文本（移除已有的重复 Frontmatter 等）
     */
    private String cleanMarkdown(String text) {
        if (StrUtil.isBlank(text)) return "";
        String trimmed = text.trim();
        if (trimmed.startsWith("---")) {
            int second = trimmed.indexOf("---", 3);
            if (second > 0) {
                trimmed = trimmed.substring(second + 3).trim();
            }
        }
        return trimmed;
    }

    /**
     * 确保文档具备至少一个二级标题 (##)，若无则以文件名作为总标题
     */
    private String ensureHeadings(String body, String filename) {
        if (body.contains("## ")) {
            return body;
        }
        String cleanTitle = FileUtil.mainName(filename);
        return "## " + cleanTitle + "\n\n" + body;
    }

    private String buildFrontmatter(String kp, String category, String source) {
        return "---\nkp: " + kp + "\ncategory: " + category + "\nsource: " + source + "\n---";
    }

    /**
     * 切分切片并自动挂载面包屑上下文路径（Breadcrumb Path Augmentation）
     */
    private List<ParsedChunk> extractChunksWithBreadcrumbs(String filename, String markdown) {
        List<ParsedChunk> list = new ArrayList<>();
        String mainDoc = FileUtil.mainName(filename);
        String[] sections = markdown.split("(?m)^##\\s+");

        for (String sec : sections) {
            String trimmed = sec.trim();
            if (trimmed.isBlank()) continue;

            int newline = trimmed.indexOf('\n');
            String title = (newline > 0) ? trimmed.substring(0, newline).trim() : trimmed;
            String text = (newline > 0) ? trimmed.substring(newline).trim() : "";

            String breadcrumb = "【" + mainDoc + " > " + title + "】";
            String fullContext = breadcrumb + "\n" + text;
            int tokens = (int) (fullContext.length() * 0.75); // 字符转 Token 经验估算

            list.add(new ParsedChunk(title, breadcrumb, fullContext, tokens));
        }
        return list;
    }
}
