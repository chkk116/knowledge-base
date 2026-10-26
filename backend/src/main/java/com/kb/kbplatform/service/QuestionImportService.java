/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.Question;
import com.kb.kbplatform.domain.QuestionOption;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

/**
 * 题库 Excel 批量导入（poi-ooxml，无新依赖）。
 *
 * 模板列（第一行是表头，第二行起为数据）：
 * 题型 | 题干 | 选项A | 选项B | 选项C | 选项D | 选项E | 选项F | 正确答案 | 解析 | 分类 | 难度
 *
 * - 题型：单选 / 多选 / 判断 / 填空 / 简答（也接受 SINGLE/MULTI/JUDGE/FILL/ESSAY）
 * - 判断：选项不用填，答案填 对/错（也接受 √/×、TRUE/FALSE）
 * - 多选：答案填 A,C（逗号/顿号/空格分隔均可）
 * - 填空/简答：答案是该题参考答案文本，无选项
 *
 * 逐行导入、逐行报错：单行失败不影响其他行，返回 { imported, failed: [{row, reason}] }。
 */
@Service
public class QuestionImportService {

    private static final String[] HEADERS = {
            "题型", "题干", "选项A", "选项B", "选项C", "选项D", "选项E", "选项F", "正确答案", "解析", "分类", "难度"
    };

    private final ExamService examService;

    public QuestionImportService(ExamService examService) {
        this.examService = examService;
    }

    public Map<String, Object> importXlsx(InputStream in) {
        List<Map<String, Object>> failed = new ArrayList<>();
        int imported = 0;
        int skipped = 0;
        try (Workbook wb = WorkbookFactory.create(in)) {
            Sheet sheet = wb.getSheetAt(0);
            if (sheet == null || sheet.getLastRowNum() < 1) {
                throw new IllegalArgumentException("文件里没有数据行（第一行是表头）");
            }
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                String type = cell(row, 0);
                String content = cell(row, 1);
                if (type.isEmpty() && content.isEmpty()) { skipped++; continue; }
                try {
                    Question q = buildQuestion(type, content, cell(row, 8), cell(row, 9), cell(row, 10), cell(row, 11));
                    List<QuestionOption> options = buildOptions(q.getType(), row, cell(row, 8));
                    examService.adminSaveQuestion(q, options);
                    imported++;
                } catch (Exception e) {
                    Map<String, Object> err = new LinkedHashMap<>();
                    err.put("row", r + 1);
                    err.put("reason", e.getMessage() == null ? "格式错误" : e.getMessage());
                    failed.add(err);
                }
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("无法解析该文件（仅支持 .xlsx / .xls）：" + e.getMessage());
        }
        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("imported", imported);
        ret.put("skipped", skipped);
        ret.put("failedCount", failed.size());
        // 只回传前 50 条，避免整表错行把响应撑爆
        ret.put("failed", failed.size() > 50 ? failed.subList(0, 50) : failed);
        return ret;
    }

    private Question buildQuestion(String type, String content, String answer, String analysis,
                                   String category, String difficulty) {
        if (content.isEmpty()) throw new IllegalArgumentException("题干不能为空");
        Question q = new Question();
        q.setType(normalizeType(type));
        q.setContent(content);
        q.setAnalysis(analysis.isEmpty() ? null : analysis);
        q.setCategory(category.isEmpty() ? null : category);
        if (!difficulty.isEmpty()) {
            try {
                int d = Integer.parseInt(difficulty.replace(".0", ""));
                if (d >= 1 && d <= 5) q.setDifficulty(d);
            } catch (NumberFormatException ignore) { /* 难度非法就按默认 1 */ }
        }
        if (!isObjective(q.getType()) && answer.isEmpty()) {
            throw new IllegalArgumentException("填空/简答题需要填参考答案");
        }
        if (isObjective(q.getType()) && answer.isEmpty()) {
            throw new IllegalArgumentException("客观题必须填正确答案");
        }
        return q;
    }

    private List<QuestionOption> buildOptions(String type, Row row, String answer) {
        List<QuestionOption> options = new ArrayList<>();
        if ("JUDGE".equals(type)) {
            boolean yes = isTrueAnswer(answer);
            options.add(option("A", "正确", yes));
            options.add(option("B", "错误", !yes));
            return options;
        }
        if (!isObjective(type)) return options;   // 填空/简答无选项
        String[] labels = {"A", "B", "C", "D", "E", "F"};
        for (int i = 0; i < labels.length; i++) {
            String text = cell(row, 2 + i);
            if (text.isEmpty()) continue;
            options.add(option(labels[i], text, answerPicked(answer, labels[i])));
        }
        if (options.isEmpty()) throw new IllegalArgumentException("客观题至少要有一列选项内容");
        if (options.stream().noneMatch(QuestionOption::isCorrect)) {
            throw new IllegalArgumentException("正确答案与选项不匹配（应填 A/B/C…）");
        }
        return options;
    }

    private QuestionOption option(String label, String content, boolean correct) {
        QuestionOption o = new QuestionOption();
        o.setLabel(label);
        o.setContent(content);
        o.setCorrect(correct);
        return o;
    }

    /** 答案里是否包含该选项（A / A,C / A、C / A C 均可） */
    private boolean answerPicked(String answer, String label) {
        String norm = answer.toUpperCase(Locale.ROOT).replaceAll("[^A-F]", " ");
        for (String part : norm.split("\\s+")) {
            if (part.equals(label)) return true;
        }
        return false;
    }

    private boolean isTrueAnswer(String answer) {
        String a = answer.trim().toUpperCase(Locale.ROOT);
        return a.equals("对") || a.equals("正确") || a.equals("√") || a.equals("TRUE") || a.equals("T")
                || a.equals("YES") || a.equals("A");
    }

    private String normalizeType(String raw) {
        String t = raw.trim().toUpperCase(Locale.ROOT);
        switch (raw.trim()) {
            case "单选": case "单选题": return "SINGLE";
            case "多选": case "多选题": return "MULTI";
            case "判断": case "判断题": return "JUDGE";
            case "填空": case "填空题": return "FILL";
            case "简答": case "简答题": case "问答": return "ESSAY";
            default: break;
        }
        if (t.equals("SINGLE") || t.equals("MULTI") || t.equals("JUDGE") || t.equals("FILL") || t.equals("ESSAY")) return t;
        throw new IllegalArgumentException("题型无法识别：" + (raw.isEmpty() ? "（空）" : raw));
    }

    private boolean isObjective(String type) {
        return "SINGLE".equals(type) || "MULTI".equals(type) || "JUDGE".equals(type);
    }

    private String cell(Row row, int idx) {
        Cell c = row.getCell(idx);
        if (c == null) return "";
        String v;
        switch (c.getCellType()) {
            case STRING -> v = c.getStringCellValue();
            case NUMERIC -> v = String.valueOf((long) c.getNumericCellValue());
            case BOOLEAN -> v = c.getBooleanCellValue() ? "TRUE" : "FALSE";
            case FORMULA -> v = c.getCellFormula();
            default -> v = "";
        }
        return v == null ? "" : v.trim();
    }

    /** 导入模板：表头 + 三行示例（单选 / 多选 / 判断），用户直接改内容即可 */
    public byte[] template() {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("题库");
            CellStyle head = wb.createCellStyle();
            Font bold = wb.createFont();
            bold.setBold(true);
            head.setFont(bold);
            Row h = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell c = h.createCell(i);
                c.setCellValue(HEADERS[i]);
                c.setCellStyle(head);
                sheet.setColumnWidth(i, i == 1 || i == 9 ? 9000 : 4200);
            }
            String[][] samples = {
                    {"单选", "下列哪个是关系型数据库？", "MySQL", "Redis", "MongoDB", "Elasticsearch", "", "", "A", "MySQL 是关系型；其余为 NoSQL/缓存/搜索引擎", "数据库", "1"},
                    {"多选", "以下哪些属于 HTTP 方法？", "GET", "POST", "PUSH", "DELETE", "", "", "A,B,D", "PUSH 不是标准 HTTP 方法", "网络", "2"},
                    {"判断", "JWT 的签名可以防止内容被篡改。", "", "", "", "", "", "", "对", "签名校验失败即视为无效令牌", "安全", "1"},
                    {"简答", "简述数据库索引的作用。", "", "", "", "", "", "", "加速查询、减少全表扫描；代价是写入变慢与额外存储", "", "数据库", "3"},
            };
            for (int r = 0; r < samples.length; r++) {
                Row row = sheet.createRow(r + 1);
                for (int c = 0; c < samples[r].length; c++) row.createCell(c).setCellValue(samples[r][c]);
            }
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("生成模板失败：" + e.getMessage());
        }
    }
}
