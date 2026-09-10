package com.kb.kbplatform.web;

import com.kb.kbplatform.domain.Asset;
import com.kb.kbplatform.domain.AssetBorrow;
import com.kb.kbplatform.domain.AssetCategory;
import com.kb.kbplatform.domain.AssetLog;
import com.kb.kbplatform.security.TokenClaims;
import com.kb.kbplatform.service.AssetExcelService;
import com.kb.kbplatform.service.AssetService;
import com.kb.kbplatform.service.AuditService;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.FORBIDDEN;

@RestController
@RequestMapping("/api/admin/assets")
public class AdminAssetController {

    private static final String PERM = "ASSET_MANAGE";

    private final AssetService svc;
    private final AssetExcelService excel;
    private final AuditService audit;

    public AdminAssetController(AssetService svc, AssetExcelService excel, AuditService audit) {
        this.svc = svc;
        this.excel = excel;
        this.audit = audit;
    }

    private void requirePerm(TokenClaims c) {
        if (c == null || !c.hasPerm(PERM)) {
            throw new ResponseStatusException(FORBIDDEN, "需要「资产管理」权限");
        }
    }

    private String opName(TokenClaims c) {
        return c != null ? c.username() : "系统";
    }

    private Long opId(TokenClaims c) {
        return c != null ? c.userId() : null;
    }

    // ---------- 资产台账 ----------
    @GetMapping
    public Page<Asset> list(@RequestAttribute("kbUser") TokenClaims c,
                            @RequestParam(required = false, defaultValue = "") String keyword,
                            @RequestParam(required = false) Long categoryId,
                            @RequestParam(required = false) String status,
                            @RequestParam(required = false) String department,
                            @RequestParam(required = false) String ownerName,
                            @RequestParam(required = false) String location,
                            @RequestParam(required = false) String borrowStatus,
                            @RequestParam(required = false, defaultValue = "newest") String sort,
                            @RequestParam(required = false, defaultValue = "0") int page,
                            @RequestParam(required = false, defaultValue = "15") int size) {
        requirePerm(c);
        return svc.search(keyword, categoryId, status, department, ownerName, location, borrowStatus, page, size, sort);
    }

    @GetMapping("/stats")
    public Map<String, Object> stats(@RequestAttribute("kbUser") TokenClaims c) {
        requirePerm(c);
        return svc.stats();
    }

    @GetMapping("/next-code")
    public Map<String, String> nextCode(@RequestAttribute("kbUser") TokenClaims c) {
        requirePerm(c);
        return Map.of("code", svc.nextCode());
    }

    // ---------- Excel 批量导入 / 导出 ----------
    /** 下载导入模板（含示例行与填写说明） */
    @GetMapping("/template")
    public ResponseEntity<byte[]> template(@RequestAttribute("kbUser") TokenClaims c) {
        requirePerm(c);
        return xlsx(excel.template(), "asset-template.xlsx");
    }

    /** 按当前列表筛选条件导出 xlsx（导出列与模板一致，可直接改完导回）；
     *  传入 ids 时按勾选的 id 列表导出（忽略筛选条件） */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestAttribute("kbUser") TokenClaims c,
                                         @RequestParam(required = false, defaultValue = "") String keyword,
                                         @RequestParam(required = false) Long categoryId,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(required = false) String department,
                                         @RequestParam(required = false) String ownerName,
                                         @RequestParam(required = false) String location,
                                         @RequestParam(required = false) String borrowStatus,
                                         @RequestParam(required = false, defaultValue = "newest") String sort,
                                         @RequestParam(required = false) List<Long> ids) {
        requirePerm(c);
        List<Asset> list = (ids != null && !ids.isEmpty())
                ? svc.listByIds(ids)
                : svc.searchAll(keyword, categoryId, status, department, ownerName, location, borrowStatus, sort);
        return xlsx(excel.export(list), "assets.xlsx");
    }

    /** 批量导入：逐行校验，单行失败不影响其他行，返回成功数与失败明细（行号 + 原因） */
    @PostMapping("/import")
    public Map<String, Object> importAssets(@RequestAttribute("kbUser") TokenClaims c,
                                            @RequestParam("file") MultipartFile file) {
        requirePerm(c);
        Map<String, Object> res = excel.importAssets(file, opId(c), opName(c));
        audit.log(opId(c), opName(c), "IMPORT_ASSET", "ASSET", null, null,
                "批量导入资产：成功 " + res.get("success") + " 条，失败 " + res.get("failedCount") + " 条");
        return res;
    }

    private ResponseEntity<byte[]> xlsx(byte[] data, String filename) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(data);
    }

    @PostMapping
    public Asset create(@RequestAttribute("kbUser") TokenClaims c, @RequestBody Map<String, Object> body) {
        requirePerm(c);
        Asset a = svc.createAsset(body, opId(c), opName(c));
        audit.log(opId(c), opName(c), "CREATE_ASSET", "ASSET", a.getId(), a.getName(),
                "建档资产「" + a.getName() + "」（" + a.getCode() + "）");
        return a;
    }

    @GetMapping("/{id}")
    public Asset get(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id) {
        requirePerm(c);
        return svc.getAsset(id);
    }

    @PutMapping("/{id}")
    public Asset update(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id,
                        @RequestBody Map<String, Object> body) {
        requirePerm(c);
        Asset updated = svc.updateAsset(id, body, opId(c), opName(c));
        audit.log(opId(c), opName(c), "UPDATE_ASSET", "ASSET", id, updated.getName(),
                "更新资产「" + updated.getName() + "」");
        return updated;
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id) {
        requirePerm(c);
        Asset a = svc.getAsset(id);
        svc.deleteAsset(id, opId(c), opName(c));
        audit.log(opId(c), opName(c), "DELETE_ASSET", "ASSET", id, a.getName(),
                "删除资产「" + a.getName() + "」（" + a.getCode() + "）");
        return Map.of("ok", true);
    }

    /** 批量删除：尽力删除，存在未归还借用记录或已不存在的资产会被跳过并计入 skipped，不阻断其余项 */
    @PostMapping("/batch-delete")
    public Map<String, Object> batchDelete(@RequestAttribute("kbUser") TokenClaims c,
                                           @RequestBody List<Long> ids) {
        requirePerm(c);
        int deleted = 0, skipped = 0;
        for (Long id : ids) {
            try {
                svc.getAsset(id);
                svc.deleteAsset(id, opId(c), opName(c));
                deleted++;
            } catch (ResponseStatusException e) {
                skipped++;
            }
        }
        audit.log(opId(c), opName(c), "DELETE_ASSET_BATCH", "ASSET", null, null,
                "批量删除资产：成功 " + deleted + " 条，跳过 " + skipped + " 条");
        return Map.of("ok", true, "deleted", deleted, "skipped", skipped);
    }

    // ---------- 借还 ----------
    @GetMapping("/{id}/borrows")
    public List<AssetBorrow> borrows(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id) {
        requirePerm(c);
        return svc.borrows(id);
    }

    @PostMapping("/{id}/borrow")
    public AssetBorrow borrow(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id,
                              @RequestBody Map<String, Object> body) {
        requirePerm(c);
        AssetBorrow b = svc.borrow(id, body, opId(c), opName(c), opId(c));
        audit.log(opId(c), opName(c), "BORROW_ASSET", "ASSET", id, b.getAssetName(),
                "借出资产「" + b.getAssetName() + "」给 " + b.getBorrower());
        return b;
    }

    @PostMapping("/{id}/borrow/{borrowId}/return")
    public AssetBorrow returnBorrow(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id,
                                    @PathVariable Long borrowId, @RequestBody Map<String, Object> body) {
        requirePerm(c);
        AssetBorrow b = svc.returnBorrow(borrowId, body, opId(c), opName(c));
        audit.log(opId(c), opName(c), "RETURN_ASSET", "ASSET", id, b.getAssetName(),
                "归还资产「" + b.getAssetName() + "」");
        return b;
    }

    // ---------- 变更日志 ----------
    @GetMapping("/{id}/logs")
    public List<AssetLog> logs(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id) {
        requirePerm(c);
        return svc.logs(id);
    }

    // ---------- 分类管理 ----------
    @GetMapping("/categories")
    public List<AssetCategory> categories(@RequestAttribute("kbUser") TokenClaims c) {
        requirePerm(c);
        return svc.listCategories();
    }

    @PostMapping("/categories")
    public AssetCategory createCategory(@RequestAttribute("kbUser") TokenClaims c, @RequestBody Map<String, Object> body) {
        requirePerm(c);
        AssetCategory cat = svc.createCategory(str(body.get("name")), lng(body.get("parentId")), intVal(body.get("sort")));
        audit.log(opId(c), opName(c), "CREATE_ASSET_CATEGORY", "ASSET_CATEGORY", cat.getId(), cat.getName(),
                "新建资产分类「" + cat.getName() + "」");
        return cat;
    }

    @PutMapping("/categories/{id}")
    public AssetCategory updateCategory(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id,
                                        @RequestBody Map<String, Object> body) {
        requirePerm(c);
        AssetCategory cat = svc.updateCategory(id, str(body.get("name")), lng(body.get("parentId")), intVal(body.get("sort")));
        audit.log(opId(c), opName(c), "UPDATE_ASSET_CATEGORY", "ASSET_CATEGORY", id, cat.getName(),
                "更新资产分类「" + cat.getName() + "」");
        return cat;
    }

    @DeleteMapping("/categories/{id}")
    public Map<String, Object> deleteCategory(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id) {
        requirePerm(c);
        AssetCategory cat = svc.listCategories().stream().filter(x -> x.getId().equals(id)).findFirst().orElse(null);
        svc.deleteCategory(id);
        audit.log(opId(c), opName(c), "DELETE_ASSET_CATEGORY", "ASSET_CATEGORY", id,
                cat != null ? cat.getName() : String.valueOf(id), "删除资产分类");
        return Map.of("ok", true);
    }

    private String str(Object o) { return o == null ? null : String.valueOf(o); }
    private Long lng(Object o) {
        if (o == null || String.valueOf(o).isBlank()) return null;
        try { return Long.valueOf(String.valueOf(o)); } catch (NumberFormatException e) { return null; }
    }
    private int intVal(Object o) {
        if (o == null) return 0;
        try { return Integer.parseInt(String.valueOf(o)); } catch (NumberFormatException e) { return 0; }
    }
}
