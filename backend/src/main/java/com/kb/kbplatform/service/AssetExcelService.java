package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.Asset;
import com.kb.kbplatform.domain.AssetCategory;
import com.kb.kbplatform.repository.AssetCategoryRepository;
import com.kb.kbplatform.repository.AssetRepository;
import org.apache.poi.ss.usermodel.*;   // POI 5.x：DateUtil 在 ss.usermodel 包下（不是 ss.util）
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * 资产台账 Excel 批量导入 / 导出（POI）。
 * - 导出：列表筛选条件直接复用，导出列顺序与导入模板一致，可「导出 → 改 → 导回」闭环。
 * - 导入：按表头名定位列（列顺序变了也能认），逐行校验，单行失败不影响其他行，
 *         最后返回成功数与失败明细（行号 + 原因），便于用户照着改。
 */
@Service
public class AssetExcelService {

    /** 表头（中文）与字段名的对应顺序，导出 / 模板 / 导入三处共用 */
    private static final String[] HEADERS = {
            "资产编号", "资产名称", "分类", "品牌", "型号", "序列号", "状态",
            "责任人", "部门", "存放位置", "购置日期", "价格", "保修到期", "供应商", "备注"
    };
    private static final String[] FIELDS = {
            "code", "name", "categoryName", "brand", "model", "sn", "status",
            "ownerName", "department", "location", "purchaseDate", "price", "warrantyExpire", "supplier", "remark"
    };
    private static final int COLS = HEADERS.length;

    private static final Map<String, String> STATUS_CN = Map.of(
            "在用", "IN_USE", "闲置", "IDLE", "维修", "REPAIR", "报废", "SCRAPPED");
    private static final Set<String> STATUS_EN = Set.of("IN_USE", "IDLE", "REPAIR", "SCRAPPED");
    private static final Map<String, String> STATUS_LABEL = Map.of(
            "IN_USE", "在用", "IDLE", "闲置", "REPAIR", "维修", "SCRAPPED", "报废");

    private final AssetService assetService;
    private final AssetRepository assetRepo;
    private final AssetCategoryRepository catRepo;
    private final DataFormatter fmt = new DataFormatter();

    public AssetExcelService(AssetService assetService, AssetRepository assetRepo,
                             AssetCategoryRepository catRepo) {
        this.assetService = assetService;
        this.assetRepo = assetRepo;
        this.catRepo = catRepo;
    }

    // ---------------- 导出 ----------------
    public byte[] export(List<Asset> assets) {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sh = wb.createSheet("资产台账");
            Row hr = sh.createRow(0);
            CellStyle headStyle = headStyle(wb);
            for (int i = 0; i < COLS; i++) {
                Cell c = hr.createCell(i);
                c.setCellValue(HEADERS[i]);
                c.setCellStyle(headStyle);
            }
            int r = 1;
            for (Asset a : assets) {
                Row row = sh.createRow(r++);
                for (int i = 0; i < COLS; i++) {
                    Cell c = row.createCell(i);
                    setCell(c, valueOf(a, FIELDS[i]));
                }
            }
            for (int i = 0; i < COLS; i++) sh.autoSizeColumn(i);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("生成 Excel 失败", e);
        }
    }

    // ---------------- 导入模板 ----------------
    public byte[] template() {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sh = wb.createSheet("资产导入模板");
            CellStyle headStyle = headStyle(wb);
            Row hr = sh.createRow(0);
            for (int i = 0; i < COLS; i++) {
                Cell c = hr.createCell(i);
                c.setCellValue(HEADERS[i]);
                c.setCellStyle(headStyle);
            }
            Row demo = sh.createRow(1);
            demo.createCell(0).setCellValue("（留空自动生成）");
            demo.createCell(1).setCellValue("ThinkPad T14 笔记本");
            demo.createCell(2).setCellValue("（填已有分类名，不存在会报错）");
            demo.createCell(3).setCellValue("Lenovo");
            demo.createCell(4).setCellValue("T14 Gen3");
            demo.createCell(5).setCellValue("SN123456");
            demo.createCell(6).setCellValue("在用");
            demo.createCell(7).setCellValue("张三");
            demo.createCell(8).setCellValue("技术部");
            demo.createCell(9).setCellValue("A座301");
            demo.createCell(10).setCellValue(LocalDate.now().toString());
            demo.createCell(11).setCellValue(6500);
            demo.createCell(12).setCellValue(LocalDate.now().plusYears(3).toString());
            demo.createCell(13).setCellValue("联想（北京）");
            demo.createCell(14).setCellValue("示例行，导入前请删除");

            Row tip = sh.createRow(3);
            tip.createCell(0).setCellValue(
                    "填写说明：①「资产名称」必填；②「资产编号」留空则自动生成；"
                            + "③「状态」填 在用 / 闲置 / 维修 / 报废；④日期格式 yyyy-MM-dd；⑤分类必须是系统中已存在的分类名。");
            for (int i = 0; i < COLS; i++) sh.autoSizeColumn(i);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("生成模板失败", e);
        }
    }

    // ---------------- 导入 ----------------
    public Map<String, Object> importAssets(MultipartFile file, Long operatorId, String operatorName) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("请选择要导入的 Excel 文件");
        int success = 0;
        List<Map<String, Object>> failed = new ArrayList<>();
        Map<String, Long> catByName = new HashMap<>();
        for (AssetCategory c : catRepo.findAll()) catByName.put(c.getName().trim(), c.getId());

        try (InputStream in = file.getInputStream();
             Workbook wb = WorkbookFactory.create(in)) {
            Sheet sh = wb.getSheetAt(0);
            if (sh == null) throw new IllegalArgumentException("工作表为空");
            Row header = sh.getRow(sh.getFirstRowNum());
            if (header == null) throw new IllegalArgumentException("缺少表头行，请先下载模板");

            Map<String, Integer> col = new HashMap<>();
            for (int i = 0; i < header.getLastCellNum(); i++) {
                String h = str(header.getCell(i));
                if (h == null) continue;
                for (int k = 0; k < COLS; k++) {
                    if (HEADERS[k].equals(h)) { col.put(FIELDS[k], i); break; }
                }
            }
            if (!col.containsKey("name")) throw new IllegalArgumentException("表头缺少「资产名称」列，请使用模板");

            for (int r = sh.getFirstRowNum() + 1; r <= sh.getLastRowNum(); r++) {
                Row row = sh.getRow(r);
                if (row == null || isBlankRow(row)) continue;
                try {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("name", require(cellStr(row, col, "name"), "资产名称"));

                    String code = cellStr(row, col, "code");
                    if (code != null) {
                        if (assetRepo.findByCode(code).isPresent())
                            throw new IllegalArgumentException("资产编号已存在：" + code);
                        body.put("code", code);
                    }
                    String cat = cellStr(row, col, "categoryName");
                    if (cat != null) {
                        Long cid = catByName.get(cat);
                        if (cid == null) throw new IllegalArgumentException("分类不存在：" + cat);
                        body.put("categoryId", cid);
                    }
                    put(body, "brand", cellStr(row, col, "brand"));
                    put(body, "model", cellStr(row, col, "model"));
                    put(body, "sn", cellStr(row, col, "sn"));
                    put(body, "ownerName", cellStr(row, col, "ownerName"));
                    put(body, "department", cellStr(row, col, "department"));
                    put(body, "location", cellStr(row, col, "location"));
                    put(body, "supplier", cellStr(row, col, "supplier"));
                    put(body, "remark", cellStr(row, col, "remark"));
                    body.put("status", normalizeStatus(cellStr(row, col, "status")));

                    LocalDate pd = cellDate(row, col, "purchaseDate");
                    if (pd != null) body.put("purchaseDate", pd.toString());
                    LocalDate we = cellDate(row, col, "warrantyExpire");
                    if (we != null) body.put("warrantyExpire", we.toString());
                    BigDecimal price = cellDecimal(row, col, "price");
                    if (price != null) body.put("price", price);

                    assetService.createAsset(body, operatorId, operatorName);
                    success++;
                } catch (Exception e) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("row", r + 1);                       // 报表用 1 基行号
                    m.put("name", cellStr(row, col, "name"));
                    m.put("reason", e.getMessage());
                    failed.add(m);
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("读取文件失败，请确认是有效的 .xlsx 文件");
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("解析失败，请确认是有效的 Excel 文件");
        }

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", success);
        res.put("failedCount", failed.size());
        res.put("failed", failed);
        return res;
    }

    // ---------------- 内部工具 ----------------
    private CellStyle headStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(true);
        s.setFont(f);
        s.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return s;
    }

    private Object valueOf(Asset a, String field) {
        switch (field) {
            case "code": return a.getCode();
            case "name": return a.getName();
            case "categoryName": return a.getCategoryName();
            case "brand": return a.getBrand();
            case "model": return a.getModel();
            case "sn": return a.getSn();
            case "status": return STATUS_LABEL.getOrDefault(a.getStatus(), a.getStatus());
            case "ownerName": return a.getOwnerName();
            case "department": return a.getDepartment();
            case "location": return a.getLocation();
            case "purchaseDate": return a.getPurchaseDate();
            case "price": return a.getPrice();
            case "warrantyExpire": return a.getWarrantyExpire();
            case "supplier": return a.getSupplier();
            case "remark": return a.getRemark();
            default: return null;
        }
    }

    private void setCell(Cell c, Object v) {
        if (v == null) { c.setBlank(); return; }
        if (v instanceof Number n) { c.setCellValue(n.doubleValue()); return; }
        c.setCellValue(String.valueOf(v));
    }

    private String cellStr(Row row, Map<String, Integer> col, String field) {
        Integer i = col.get(field);
        return i == null ? null : str(row.getCell(i));
    }

    /** 统一按「显示值」读单元格：数字/公式/布尔都转成字符串，空串归一为 null */
    private String str(Cell c) {
        if (c == null) return null;
        String s = fmt.formatCellValue(c).trim();
        return s.isEmpty() ? null : s;
    }

    private LocalDate cellDate(Row row, Map<String, Integer> col, String field) {
        Integer i = col.get(field);
        if (i == null) return null;
        Cell c = row.getCell(i);
        if (c == null) return null;
        if (c.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(c)) {
            return c.getLocalDateTimeCellValue().toLocalDate();
        }
        String s = str(c);
        if (s == null) return null;
        s = s.replace('/', '-').replace('.', '-');
        try {
            return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
        } catch (Exception e) {
            throw new IllegalArgumentException("日期应为 yyyy-MM-dd，实际：" + s);
        }
    }

    private BigDecimal cellDecimal(Row row, Map<String, Integer> col, String field) {
        Integer i = col.get(field);
        if (i == null) return null;
        Cell c = row.getCell(i);
        if (c == null) return null;
        if (c.getCellType() == CellType.NUMERIC) return BigDecimal.valueOf(c.getNumericCellValue());
        String s = str(c);
        if (s == null) return null;
        String t = s.replace(",", "").replace("￥", "").replace("¥", "").trim();
        try {
            return new BigDecimal(t);
        } catch (Exception e) {
            throw new IllegalArgumentException("价格应为数字，实际：" + s);
        }
    }

    private String normalizeStatus(String s) {
        if (s == null || s.isBlank()) return "IN_USE";
        String t = s.trim();
        String cn = STATUS_CN.get(t);
        if (cn != null) return cn;
        String up = t.toUpperCase();
        if (STATUS_EN.contains(up)) return up;
        throw new IllegalArgumentException("状态只能是 在用/闲置/维修/报废，实际：" + s);
    }

    private boolean isBlankRow(Row row) {
        for (int i = row.getFirstCellNum(); i < row.getLastCellNum(); i++) {
            if (str(row.getCell(i)) != null) return false;
        }
        return true;
    }

    private void put(Map<String, Object> body, String key, String val) {
        if (val != null) body.put(key, val);
    }

    private String require(String v, String field) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException(field + "不能为空");
        return v.trim();
    }
}
