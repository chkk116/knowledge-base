package com.kb.kbplatform.web;

import com.kb.kbplatform.domain.Asset;
import com.kb.kbplatform.security.TokenClaims;
import com.kb.kbplatform.service.AssetService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 扫码只读接口：登录用户扫码（资产编号）后读取资产基础信息。
 * 普通用户仅见脱敏字段（名称/编号/状态/责任人/说明）；持有 ASSET_MANAGE 权限者可见完整字段。
 */
@RestController
@RequestMapping("/api/assets")
public class AssetController {

    private final AssetService svc;

    public AssetController(AssetService svc) {
        this.svc = svc;
    }

    @GetMapping("/{code}")
    public Map<String, Object> getByCode(@RequestAttribute("kbUser") TokenClaims c, @PathVariable String code) {
        // 该路径未列入 AuthFilter.isPublic，因此未登录会被拦截返回 401，无需在此重复校验
        Asset a = svc.getAssetByCode(code);
        boolean full = c != null && c.hasPerm("ASSET_MANAGE");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", a.getCode());
        m.put("name", a.getName());
        m.put("status", a.getStatus());
        m.put("ownerName", a.getOwnerName());
        m.put("remark", a.getRemark());
        m.put("borrowStatus", a.getBorrowStatus());
        if (full) {
            m.put("categoryName", a.getCategoryName());
            m.put("brand", a.getBrand());
            m.put("model", a.getModel());
            m.put("sn", a.getSn());
            m.put("price", a.getPrice());
            m.put("purchaseDate", a.getPurchaseDate());
            m.put("warrantyExpire", a.getWarrantyExpire());
            m.put("department", a.getDepartment());
            m.put("location", a.getLocation());
            m.put("supplier", a.getSupplier());
        }
        return m;
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handle(ResponseStatusException e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", e.getReason());
        return ResponseEntity.status(e.getStatusCode()).body(m);
    }
}
