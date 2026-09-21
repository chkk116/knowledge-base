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
    private final AssetService self;   // 自注入代理：createAsset 的重试落库必须走代理才能开 REQUIRES_NEW 新事务

    public AssetService(AssetRepository assetRepo, AssetCategoryRepository catRepo,
                        AssetLogRepository logRepo, AssetBorrowRepository borrowRepo,
                        @org.springframework.context.annotation.Lazy AssetService self) {
        this.assetRepo = assetRepo;
        this.catRepo = catRepo;
        this.logRepo = logRepo;
        this.borrowRepo = borrowRepo;
        this.self = self;
    }

    // ---------- 编号生成 ----------
    public String nextCode() {
        return nextAvailableCode();
    }

    /**
     * 生成一个未被占用的资产编号（F15）。
     * 原实现直接用 max(id)+1：编号允许自定义，例如已存在 AS000002 但最大 id 仍是 1 时，
     * 自动编号会反复生成 AS000002 并撞唯一约束，后续空编号行持续失败。
     * 这里从 max(id)+1 起顺序探测可用值，跳过已被自定义编号占用的号段。
     */
    public String nextAvailableCode() {
        long n = (assetRepo.maxId() == null ? 0 : assetRepo.maxId()) + 1;
        // 一次取回 AS 前缀已占用编号，内存探测——号段拥挤时不再产生最多 2000 次逐号 DB 往返
        java.util.Set<String> used = new java.util.HashSet<>(assetRepo.findCodeByPrefix("AS"));
        for (int i = 0; i < 2000; i++) {
            String candidate = "AS" + String.format("%06d", n + i);
            if (!used.contains(candidate)) return candidate;
        }
        // 极端兜底：号段被大量自定义编号占满时，退化为时间戳后缀（补零保持格式一致 + 随机尾巴防同毫秒并发重号）
        return String.format("AS%08d", System.currentTimeMillis()) + String.format("%02d", java.util.concurrent.ThreadLocalRandom.current().nextInt(100));
    }

    // ---------- CRUD ----------
    // 编排方法不加事务：PG 下唯一约束冲突会 abort 当前事务，同一事务内重试必然失败；
    // 单次落库走 createAssetInNewTx 的独立事务（经 self 代理），重试才真正生效。
    public Asset createAsset(Map<String, Object> body, Long operatorId, String operatorName) {
        Asset a = new Asset();
        String code = str(body.get("code"));
        a.setCode(code != null && !code.isBlank() ? code.trim() : nextAvailableCode());
        a.setName(requireStr(body.get("name"), "资产名称"));
        applyFields(a, body);
        // 自动编号仍可能并发撞号：唯一约束失败时重算一次再试（有界重试，不做无限循环）
        int attempt = 0;
        while (true) {
            try {
                return self.createAssetInNewTx(a, operatorId, operatorName);
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                if (code != null && !code.isBlank()) throw e;      // 用户自填编号冲突：如实报错
                if (++attempt > 3) throw e;
                a.setCode(nextAvailableCode());
            }
        }
    }

    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public Asset createAssetInNewTx(Asset a, Long operatorId, String operatorName) {
        Asset saved = assetRepo.save(a);
        writeLog(saved.getId(), AssetLog.ACTION_CREATE, operatorId, operatorName,
                "建档资产「" + saved.getName() + "」（" + saved.getCode() + "）");
        return enrich(saved);
    }

    /** 更新资产：与借还共用资产行锁（F09），避免「改为报废/维修」与「正在借出」并发产生矛盾状态 */
    @org.springframework.transaction.annotation.Transactional
    public Asset updateAsset(Long id, Map<String, Object> body, Long operatorId, String operatorName) {
        Asset a = assetRepo.findByIdForUpdate(id).orElseThrow(
                () -> new ResponseStatusException(NOT_FOUND, "资产不存在"));
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
    /** 借出：事务内先对资产行加锁（F09），把「查活跃借用 → 插入」变成串行，避免并发借给两人 */
    @org.springframework.transaction.annotation.Transactional
    public AssetBorrow borrow(Long assetId, Map<String, Object> body, Long operatorId, String operatorName, Long borrowerId) {
        Asset a = assetRepo.findByIdForUpdate(assetId).orElseThrow(
                () -> new ResponseStatusException(NOT_FOUND, "资产不存在"));
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

    /** 归还：与借出使用同一把资产行锁（F09），避免「归还」与「再次借出」交错产生双重占用 */
    @org.springframework.transaction.annotation.Transactional
    public AssetBorrow returnBorrow(Long borrowId, Map<String, Object> body, Long operatorId, String operatorName) {
        AssetBorrow b = borrowRepo.findById(borrowId).orElseThrow(
                () -> new ResponseStatusException(NOT_FOUND, "借用记录不存在"));
        Asset a = assetRepo.findByIdForUpdate(b.getAssetId()).orElseThrow(
                () -> new ResponseStatusException(NOT_FOUND, "资产不存在"));
        // 拿到资产锁后回读借用记录并复核状态：归还前的检查属于 check-then-act，
        // 两个并发归还都会通过预先检查；锁后复核才能挡住「双击归还」重复写 returnAt/日志
        b = borrowRepo.findByIdForUpdate(borrowId).orElseThrow(
                () -> new ResponseStatusException(NOT_FOUND, "借用记录不存在"));
        if (AssetBorrow.STATUS_RETURNED.equals(b.getStatus())) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "该借用记录已归还");
        }
        b.setReturnAt(LocalDateTime.now());
        b.setStatus(AssetBorrow.STATUS_RETURNED);
        b.setReturnRemark(str(body.get("returnRemark")));
        AssetBorrow saved = borrowRepo.save(b);
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
