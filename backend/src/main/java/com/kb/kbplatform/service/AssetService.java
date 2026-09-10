package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.Asset;
import com.kb.kbplatform.domain.AssetBorrow;
import com.kb.kbplatform.domain.AssetCategory;
import com.kb.kbplatform.domain.AssetLog;
import com.kb.kbplatform.repository.AssetBorrowRepository;
import com.kb.kbplatform.repository.AssetCategoryRepository;
import com.kb.kbplatform.repository.AssetLogRepository;
import com.kb.kbplatform.repository.AssetRepository;
import jakarta.persistence.criteria.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class AssetService {

    private final AssetRepository assetRepo;
    private final AssetCategoryRepository catRepo;
    private final AssetLogRepository logRepo;
    private final AssetBorrowRepository borrowRepo;

    public AssetService(AssetRepository assetRepo, AssetCategoryRepository catRepo,
                        AssetLogRepository logRepo, AssetBorrowRepository borrowRepo) {
        this.assetRepo = assetRepo;
        this.catRepo = catRepo;
        this.logRepo = logRepo;
        this.borrowRepo = borrowRepo;
    }

    // ---------- 编号生成 ----------
    public String nextCode() {
        long n = (assetRepo.maxId() == null ? 0 : assetRepo.maxId()) + 1;
        return "AS" + String.format("%06d", n);
    }

    // ---------- CRUD ----------
    public Asset createAsset(Map<String, Object> body, Long operatorId, String operatorName) {
        Asset a = new Asset();
        String code = str(body.get("code"));
        a.setCode(code != null && !code.isBlank() ? code.trim() : nextCode());
        a.setName(requireStr(body.get("name"), "资产名称"));
        applyFields(a, body);
        Asset saved = assetRepo.save(a);
        writeLog(saved.getId(), AssetLog.ACTION_CREATE, operatorId, operatorName,
                "建档资产「" + saved.getName() + "」（" + saved.getCode() + "）");
        return enrich(saved);
    }

    public Asset updateAsset(Long id, Map<String, Object> body, Long operatorId, String operatorName) {
        Asset a = requireAsset(id);
        String before = summarize(a);
        applyFields(a, body);
        a.setUpdatedAt(LocalDateTime.now());
        Asset saved = assetRepo.save(a);
        writeLog(id, AssetLog.ACTION_UPDATE, operatorId, operatorName,
                "更新资产「" + saved.getName() + "」：" + before + " → " + summarize(saved));
        return enrich(saved);
    }

    public void deleteAsset(Long id, Long operatorId, String operatorName) {
        Asset a = requireAsset(id);
        // 有未归还借用记录时禁止删除，避免记录悬空
        if (borrowRepo.existsByAssetIdAndStatus(id, AssetBorrow.STATUS_BORROWED)) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "该资产尚有未归还的借用记录，请先归还后再删除");
        }
        assetRepo.delete(a);
        writeLog(id, AssetLog.ACTION_DELETE, operatorId, operatorName, "删除资产「" + a.getName() + "」（" + a.getCode() + "）");
    }

    public Asset getAsset(Long id) {
        return enrich(requireAsset(id));
    }

    public Asset getAssetByCode(String code) {
        Asset a = assetRepo.findByCode(code.trim()).orElseThrow(
                () -> new ResponseStatusException(NOT_FOUND, "未找到该资产"));
        return enrich(a);
    }

    // ---------- 搜索 ----------
    public Page<Asset> search(String keyword, Long categoryId, String status, String department,
                              String ownerName, String location, String borrowStatus,
                              int page, int size, String sort) {
        Sort s = resolveSort(sort);
        PageRequest pg = PageRequest.of(Math.max(0, page), Math.max(1, size), s);
        Page<Asset> p = assetRepo.findAll(buildSpec(keyword, categoryId, status, department,
                ownerName, location, borrowStatus), pg);
        return enrichPage(p, pg);
    }

    /** 导出用：不分页，返回全部命中（复用同一套筛选条件） */
    public List<Asset> searchAll(String keyword, Long categoryId, String status, String department,
                                 String ownerName, String location, String borrowStatus, String sort) {
        List<Asset> list = assetRepo.findAll(buildSpec(keyword, categoryId, status, department,
                ownerName, location, borrowStatus), resolveSort(sort));
        Map<Long, String> catNames = catRepo.findAll().stream()
                .collect(Collectors.toMap(AssetCategory::getId, AssetCategory::getName, (x, y) -> x));
        for (Asset a : list) {
            if (a.getCategoryId() != null) a.setCategoryName(catNames.get(a.getCategoryId()));
        }
        return list;
    }

    /** 批量勾选导出：按指定 id 列表返回（忽略筛选条件），复用分类名注入 */
    public List<Asset> listByIds(List<Long> ids) {
        List<Asset> list = assetRepo.findAllById(ids);
        Map<Long, String> catNames = catRepo.findAll().stream()
                .collect(Collectors.toMap(AssetCategory::getId, AssetCategory::getName, (x, y) -> x));
        for (Asset a : list) {
            if (a.getCategoryId() != null) a.setCategoryName(catNames.get(a.getCategoryId()));
        }
        return list;
    }

    private Specification<Asset> buildSpec(String keyword, Long categoryId, String status, String department,
                                           String ownerName, String location, String borrowStatus) {
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (keyword != null && !keyword.isBlank()) {
                String like = "%" + keyword.trim() + "%";
                ps.add(cb.or(
                        cb.like(root.get("name"), like),
                        cb.like(root.get("code"), like),
                        cb.like(root.get("sn"), like),
                        cb.like(root.get("brand"), like),
                        cb.like(root.get("model"), like),
                        cb.like(root.get("ownerName"), like),
                        cb.like(root.get("remark"), like)));
            }
            if (categoryId != null) ps.add(cb.equal(root.get("categoryId"), categoryId));
            if (status != null && !status.isBlank()) ps.add(cb.equal(root.get("status"), status.toUpperCase()));
            if (department != null && !department.isBlank())
                ps.add(cb.like(root.get("department"), "%" + department.trim() + "%"));
            if (ownerName != null && !ownerName.isBlank())
                ps.add(cb.like(root.get("ownerName"), "%" + ownerName.trim() + "%"));
            if (location != null && !location.isBlank())
                ps.add(cb.like(root.get("location"), "%" + location.trim() + "%"));
            if ("BORROWED".equals(borrowStatus)) {
                ps.add(borrowSubquery(root, query, cb, true));
            } else if ("AVAILABLE".equals(borrowStatus)) {
                ps.add(cb.not(borrowSubquery(root, query, cb, true)));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
    }

    private Predicate borrowSubquery(Root<Asset> root, CriteriaQuery<?> query, CriteriaBuilder cb, boolean borrowed) {
        Subquery<Long> sq = query.subquery(Long.class);
        Root<AssetBorrow> b = sq.from(AssetBorrow.class);
        sq.select(b.get("assetId"));
        sq.where(cb.equal(b.get("assetId"), root.get("id")),
                cb.equal(b.get("status"), AssetBorrow.STATUS_BORROWED));
        return cb.exists(sq);
    }

    private Sort resolveSort(String sort) {
        if ("oldest".equals(sort)) return Sort.by(Sort.Direction.ASC, "createdAt");
        if ("name".equals(sort)) return Sort.by(Sort.Direction.ASC, "name");
        if ("price_desc".equals(sort)) return Sort.by(Sort.Direction.DESC, "price");
        return Sort.by(Sort.Direction.DESC, "createdAt");
    }

    // ---------- 分类 ----------
    public List<AssetCategory> listCategories() {
        return catRepo.findAllByOrderBySortAscIdAsc();
    }

    public AssetCategory createCategory(String name, Long parentId, int sort) {
        AssetCategory c = new AssetCategory();
        c.setName(requireStr(name, "分类名称"));
        c.setParentId(parentId);
        c.setSort(sort);
        return catRepo.save(c);
    }

    public AssetCategory updateCategory(Long id, String name, Long parentId, int sort) {
        AssetCategory c = requireCategory(id);
        if (name != null) c.setName(name);
        if (parentId != null) {
            if (id.equals(parentId)) throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "分类不能归属自身");
            c.setParentId(parentId);
        }
        c.setSort(sort);
        return catRepo.save(c);
    }

    public void deleteCategory(Long id) {
        AssetCategory c = requireCategory(id);
        // 子分类一并删除（最多两级）
        catRepo.findByParentIdOrderBySortAscIdAsc(id).forEach(child -> catRepo.delete(child));
        // 该分类下的资产仅置空分类关联，不删除资产（定向查询，旧实现是 findAll() 全表过滤）
        List<Asset> affected = assetRepo.findByCategoryId(id);
        for (Asset a : affected) a.setCategoryId(null);
        if (!affected.isEmpty()) assetRepo.saveAll(affected);
        catRepo.delete(c);
    }

    // ---------- 借还 ----------
    public AssetBorrow borrow(Long assetId, Map<String, Object> body, Long operatorId, String operatorName, Long borrowerId) {
        Asset a = requireAsset(assetId);
        // 报废 / 维修中的资产不可借出（借出状态需与资产生命周期状态联动）
        if (Asset.STATUS_SCRAPPED.equals(a.getStatus()) || Asset.STATUS_REPAIR.equals(a.getStatus())) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "该资产当前状态（" + a.getStatus() + "）不可借出");
        }
        if (borrowRepo.existsByAssetIdAndStatus(assetId, AssetBorrow.STATUS_BORROWED)) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "该资产当前已借出，无法再次借出");
        }
        AssetBorrow b = new AssetBorrow();
        b.setAssetId(assetId);
        b.setBorrower(requireStr(body.get("borrower"), "借用人"));
        b.setBorrowerId(borrowerId);
        b.setBorrowReason(str(body.get("borrowReason")));
        b.setExpectedReturnAt(parseDate(body.get("expectedReturnAt")));
        b.setStatus(AssetBorrow.STATUS_BORROWED);
        AssetBorrow saved = borrowRepo.save(b);
        writeLog(assetId, AssetLog.ACTION_BORROW, operatorId, operatorName,
                "借出资产「" + a.getName() + "」给 " + saved.getBorrower());
        return enrichBorrow(saved);
    }

    public AssetBorrow returnBorrow(Long borrowId, Map<String, Object> body, Long operatorId, String operatorName) {
        AssetBorrow b = borrowRepo.findById(borrowId).orElseThrow(
                () -> new ResponseStatusException(NOT_FOUND, "借用记录不存在"));
        if (AssetBorrow.STATUS_RETURNED.equals(b.getStatus())) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "该借用记录已归还");
        }
        b.setReturnAt(LocalDateTime.now());
        b.setStatus(AssetBorrow.STATUS_RETURNED);
        b.setReturnRemark(str(body.get("returnRemark")));
        AssetBorrow saved = borrowRepo.save(b);
        Asset a = requireAsset(b.getAssetId());
        writeLog(b.getAssetId(), AssetLog.ACTION_RETURN, operatorId, operatorName,
                "归还资产「" + a.getName() + "」（借用人 " + saved.getBorrower() + "）");
        return enrichBorrow(saved);
    }

    public List<AssetLog> logs(Long assetId) {
        requireAsset(assetId);
        return logRepo.findByAssetIdOrderByCreatedAtDesc(assetId);
    }

    public List<AssetBorrow> borrows(Long assetId) {
        requireAsset(assetId);
        return borrowRepo.findByAssetIdOrderByBorrowAtDesc(assetId).stream()
                .map(this::enrichBorrow).collect(Collectors.toList());
    }

    // ---------- 统计 ----------
    /** 全部走 SQL 聚合：不把资产表拉进内存。
     *  旧实现是 findAll() 后 stream groupBy + 两次遍历，台账一大就拖垮接口。 */
    public Map<String, Object> stats() {
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (Object[] row : assetRepo.countGroupByStatus()) {
            String st = row[0] == null ? "UNKNOWN" : String.valueOf(row[0]);
            byStatus.put(st, ((Number) row[1]).longValue());
        }
        BigDecimal totalValue = assetRepo.sumPrice();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", assetRepo.count());
        m.put("byStatus", byStatus);
        m.put("borrowed", borrowRepo.countByStatus(AssetBorrow.STATUS_BORROWED));
        m.put("totalValue", totalValue == null ? BigDecimal.ZERO : totalValue);
        return m;
    }

    // ---------- 内部工具 ----------
    private void applyFields(Asset a, Map<String, Object> body) {
        if (body.get("name") != null) a.setName(str(body.get("name")));
        if (body.containsKey("categoryId")) a.setCategoryId(lng(body.get("categoryId")));
        if (body.containsKey("brand")) a.setBrand(str(body.get("brand")));
        if (body.containsKey("model")) a.setModel(str(body.get("model")));
        if (body.containsKey("sn")) a.setSn(str(body.get("sn")));
        if (body.get("status") != null) a.setStatus(str(body.get("status")));
        if (body.containsKey("ownerName")) a.setOwnerName(str(body.get("ownerName")));
        if (body.containsKey("ownerId")) a.setOwnerId(lng(body.get("ownerId")));
        if (body.containsKey("department")) a.setDepartment(str(body.get("department")));
        if (body.containsKey("location")) a.setLocation(str(body.get("location")));
        if (body.containsKey("purchaseDate")) a.setPurchaseDate(parseDate(body.get("purchaseDate")));
        if (body.containsKey("price")) a.setPrice(parseDecimal(body.get("price")));
        if (body.containsKey("warrantyExpire")) a.setWarrantyExpire(parseDate(body.get("warrantyExpire")));
        if (body.containsKey("supplier")) a.setSupplier(str(body.get("supplier")));
        if (body.containsKey("remark")) a.setRemark(str(body.get("remark")));
        if (body.containsKey("imageUrl")) a.setImageUrl(str(body.get("imageUrl")));
        if (body.containsKey("attachmentUrl")) a.setAttachmentUrl(str(body.get("attachmentUrl")));
    }

    private Asset requireAsset(Long id) {
        return assetRepo.findById(id).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "资产不存在"));
    }

    private AssetCategory requireCategory(Long id) {
        return catRepo.findById(id).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "分类不存在"));
    }

    private void writeLog(Long assetId, String action, Long operatorId, String operatorName, String detail) {
        try {
            AssetLog l = new AssetLog();
            l.setAssetId(assetId);
            l.setAction(action);
            l.setOperatorId(operatorId);
            l.setOperatorName(operatorName);
            l.setDetail(detail);
            logRepo.save(l);
        } catch (Exception ignored) {
            // best-effort
        }
    }

    /** 注入分类名称与借出状态 */
    private Asset enrich(Asset a) {
        if (a.getCategoryId() != null) {
            catRepo.findById(a.getCategoryId()).ifPresent(c -> a.setCategoryName(c.getName()));
        }
        boolean borrowed = borrowRepo.existsByAssetIdAndStatus(a.getId(), AssetBorrow.STATUS_BORROWED);
        a.setBorrowStatus(borrowed ? "BORROWED" : "AVAILABLE");
        return a;
    }

    private Page<Asset> enrichPage(Page<Asset> p, PageRequest pg) {
        Map<Long, String> catNames = catRepo.findAll().stream()
                .collect(Collectors.toMap(AssetCategory::getId, AssetCategory::getName, (x, y) -> x));
        List<Asset> pageAssets = p.getContent();
        Set<Long> ids = pageAssets.stream().map(Asset::getId).collect(Collectors.toSet());
        Set<Long> borrowedIds = ids.isEmpty() ? Collections.emptySet()
                : borrowRepo.findBorrowedAssetIds(AssetBorrow.STATUS_BORROWED, ids);
        List<Asset> content = pageAssets.stream().map(a -> {
            if (a.getCategoryId() != null) a.setCategoryName(catNames.get(a.getCategoryId()));
            a.setBorrowStatus(borrowedIds.contains(a.getId()) ? "BORROWED" : "AVAILABLE");
            return a;
        }).collect(Collectors.toList());
        return new PageImpl<>(content, pg, p.getTotalElements());
    }

    private AssetBorrow enrichBorrow(AssetBorrow b) {
        assetRepo.findById(b.getAssetId()).ifPresent(a -> {
            b.setAssetName(a.getName());
            b.setAssetCode(a.getCode());
        });
        return b;
    }

    private String summarize(Asset a) {
        return "名称=" + a.getName() + ", 分类=" + a.getCategoryName() + ", 状态=" + a.getStatus()
                + ", 责任人=" + a.getOwnerName() + ", 位置=" + a.getLocation();
    }

    private String requireStr(Object o, String field) {
        String s = str(o);
        if (s == null || s.isBlank()) throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, field + "不能为空");
        return s.trim();
    }

    private String str(Object o) { return o == null ? null : String.valueOf(o); }

    private Long lng(Object o) {
        if (o == null || String.valueOf(o).isBlank()) return null;
        try { return Long.valueOf(String.valueOf(o)); } catch (NumberFormatException e) { return null; }
    }

    private BigDecimal parseDecimal(Object o) {
        if (o == null || String.valueOf(o).isBlank()) return null;
        try { return new BigDecimal(String.valueOf(o)); } catch (NumberFormatException e) { return null; }
    }

    private LocalDate parseDate(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        if (s.isBlank()) return null;
        try { return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s); } catch (Exception e) { return null; }
    }
}
