package com.by.ximu.inventory.module.check;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.by.ximu.common.Auths;
import com.by.ximu.common.DimsNormalizer;
import com.by.ximu.common.DocStatus;
import com.by.ximu.common.OperatorContext;
import com.by.ximu.common.PageQuery;
import com.by.ximu.common.Role;
import com.by.ximu.inventory.common.DocGuard;
import com.by.ximu.inventory.common.ItemValidators;
import com.by.ximu.inventory.common.QuantitySupport;
import com.by.ximu.inventory.common.RetrySupport;
import com.by.ximu.common.web.audit.OperationLogService;
import com.by.ximu.common.web.log.BizLog;
import com.by.ximu.inventory.module.stock.StockOperationService;
import com.by.ximu.inventory.util.DocNoSequenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 盘点服务：分页查询 + 单据创建（头 + 明细 + 单号自动生成） + 状态机流转（CREATED → APPROVED → CHECKED）。
 *
 * <p>流转前置校验：仅 CREATED 可批准；仅 APPROVED 可审核；非法迁移抛 {@link IllegalStateException}。
 * <p>审核（CHECKED）后按明细逐行联动校正 {@code inventory_stock}（{@code actual_qty = 明细实盘值}）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryCheckService extends ServiceImpl<InventoryCheckMapper, InventoryCheck> {

    private static final String PREFIX = "CK";

    private final CheckItemMapper checkItemMapper;
    private final StockOperationService stockOperationService;
    private final OperationLogService operationLogService;
    private final DocNoSequenceService docNoSequenceService;

    /**
     * 分页查询（支持按状态/批号筛选 + keyword 模糊搜索盘点单号/批号）。
     * 返回头字段 + totalQty（明细实盘数量汇总）。
     */
    public Map<String, Object> page(PageQuery query, String status, String batchNo) {
        LambdaQueryWrapper<InventoryCheck> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(status)) {
            wrapper.eq(InventoryCheck::getStatus, status);
        }
        if (StringUtils.hasText(batchNo)) {
            wrapper.eq(InventoryCheck::getBatchNo, batchNo);
        }
        if (StringUtils.hasText(query.getKeyword())) {
            // R2-P2-26：keyword 走 LIKE '%kw%'（前导通配）无法命中 V7 的 status/created_at 索引，
            // 数据量大时全表扫——当前单量级可接受；若成瓶颈改前缀索引/全文检索或对账侧拉数据
            String kw = query.getKeyword();
            wrapper.and(w -> w.like(InventoryCheck::getCheckNo, kw)
                    .or().like(InventoryCheck::getBatchNo, kw));
        }
        wrapper.orderByDesc(InventoryCheck::getCreatedAt);
        Page<InventoryCheck> p = baseMapper.selectPage(query.buildPage(), wrapper);
        Page<CheckDetailVO> voPage = new Page<>(p.getCurrent(), p.getSize(), p.getTotal());
        voPage.setRecords(toVoList(p.getRecords()));
        return query.toPageMap(voPage);
    }

    /**
     * 创建盘点单（头 + 明细 + 单号自动生成）。
     *
     * <p>单号：前端不传则按 {@code CK + yyyyMMdd + 3位序号} 自动生成；传了则校验唯一。
     * <p>兼容：{@code items} 为空但传了旧单品字段（{@code actualQty}）时自动转成一条明细。
     */
    @Transactional
    @BizLog(module = "check", operation = "CREATE", message = "盘点单创建成功（单号取号+明细+审计）")
    public CheckDetailVO create(CheckCreateRequest req) {
        // 幂等：requestId 非空时按「requestId + 当前操作人」查重（P1-7：复合幂等键）
        String requestId = req.getRequestId();
        if (StringUtils.hasText(requestId)) {
            InventoryCheck existed = findByIdempotent(requestId);
            if (existed != null) {
                log.info("盘点单幂等命中: requestId={}, 返回既有单据 {}", requestId, existed.getCheckNo());
                return toVo(existed, listItems(existed.getId()));
            }
        }
        String docNo = req.getCheckNo();
        if (!StringUtils.hasText(docNo)) {
            docNo = nextDocNo();
        } else if (count(new LambdaQueryWrapper<InventoryCheck>().eq(InventoryCheck::getCheckNo, docNo)) > 0) {
            throw new IllegalArgumentException("盘点单号已存在: " + docNo);
        }
        List<CheckItem> items = normalizeItems(req);
        // 明细至少一行（R2-P1-2）：normalizeItems 已把兼容单品转成明细，此处 items 为空即二者皆空，拒绝建空单
        ItemValidators.requireNonEmpty(items, "盘点");
        InventoryCheck head = new InventoryCheck();
        head.setCheckNo(docNo);
        head.setBatchNo(req.getBatchNo());
        head.setStatus(DocStatus.CREATED.name());
        head.setCreatedBy(OperatorContext.getOperatorId());
        head.setRequestId(requestId);
        try {
            save(head);
        } catch (DuplicateKeyException e) {
            // 并发下同 requestId 同时插入，唯一索引兜底：R2-P2-23 撞键后回查 → sleep 退避 → 再回查，
            // 命中返回已存在单据，仍查不到才按并发冲突拒绝（不再裸抛 DuplicateKey 落 400 固定文案）
            InventoryCheck existed = RetrySupport.retryIdempotent(() -> findByIdempotent(requestId));
            log.info("盘点单并发撞键后幂等回查命中: requestId={}, 返回既有单据 {}", requestId, existed.getCheckNo());
            return toVo(existed, listItems(existed.getId()));
        }
        if (items != null) {
            for (CheckItem it : items) {
                it.setId(null);
                it.setCheckId(head.getId());
                checkItemMapper.insert(it);
            }
        }
        operationLogService.recordInTx("check", "CREATE", head.getId(), head.getCheckNo(), OperatorContext.getOperatorName(), req);
        log.info("盘点单创建: {} 明细{}行, 操作人={}", head.getCheckNo(), items == null ? 0 : items.size(), OperatorContext.getOperatorName());
        return toVo(head, items);
    }

    /**
     * 批准：CREATED → APPROVED。
     */
    @Transactional
    @BizLog(module = "check", operation = "APPROVE", message = "盘点单 {id} 批准成功 CREATED→APPROVED")
    public void approve(Long id) {
        InventoryCheck check = DocGuard.requireExists(getById(id), "盘点单", id);
        Auths.requireRole(Role.APPROVER, Role.ADMIN);
        Auths.requireNotSelfOrAdmin(check.getCreatedBy());
        DocGuard.requireTransitionStatus(check.getStatus(), DocStatus.CREATED.name(), "批准");
        check.setStatus(DocStatus.APPROVED.name());
        DocGuard.requireUpdateSucceeded(updateById(check));
        operationLogService.recordInTx("check", "APPROVE", id, check.getCheckNo(), OperatorContext.getOperatorName(), null);
        log.info("盘点单流转: {} CREATED -> APPROVED, 操作人={}", check.getCheckNo(), OperatorContext.getOperatorName());
    }

    /**
     * 审核：APPROVED → CHECKED；按明细逐行联动校正库存（{@code actual_qty = 明细实盘值}）。
     */
    @Transactional
    @BizLog(module = "check", operation = "CHECK", message = "盘点单 {id} 审核成功 APPROVED→CHECKED（库存校正+差异流水）")
    public void check(Long id) {
        InventoryCheck check = DocGuard.requireExists(getById(id), "盘点单", id);
        Auths.requireRole(Role.CHECKER, Role.ADMIN);
        Auths.requireNotSelfOrAdmin(check.getCreatedBy());
        DocGuard.requireTransitionStatus(check.getStatus(), DocStatus.APPROVED.name(), "审核");
        // 实盘数量必填校验（P1-2）：actualQty 为 null 的明细在校正联动时会被静默跳过，
        // 造成「盘点单已审核、库存却没盘」的假完成；审核前统一拒绝，让制单人补全后重新提交
        List<CheckItem> items = listItems(id);
        for (CheckItem it : items) {
            if (it.getActualQty() == null) {
                throw new IllegalStateException("存在未填写实盘数量(actualQty)的明细: "
                        + it.getProductName() + (StringUtils.hasText(it.getSpec()) ? "/" + it.getSpec() : "")
                        + "，请补全后再审核");
            }
        }
        check.setStatus(DocStatus.CHECKED.name());
        DocGuard.requireUpdateSucceeded(updateById(check));
        // 库存联动：按明细逐行校正到实盘数量；先按五维键排序（P1-3），
        // 保证并发审核不同盘点单时对 inventory_stock 以一致行序加锁，消除交叉死锁
        items.sort(Comparator.comparing(it -> StockOperationService.dimsKey(
                it.getOrgId(), it.getProductName(), it.getMaterial(), it.getSpec(), it.getGrade())));
        for (CheckItem it : items) {
            stockOperationService.adjustStock(it.getOrgId(), it.getGrade(), it.getProductName(), it.getMaterial(), it.getSpec(), it.getActualQty());
        }
        operationLogService.recordInTx("check", "CHECK", id, check.getCheckNo(), OperatorContext.getOperatorName(), null);
        log.info("盘点单流转: {} APPROVED -> CHECKED, 库存校正{}行, 操作人={}", check.getCheckNo(), items.size(), OperatorContext.getOperatorName());
    }

    /**
     * 级联删除：先按「id + CREATED 状态」条件删头，再删明细（同事务）。
     *
     * <p>条件删除（P0-3）：防止「读到 CREATED → 并发流转（状态变、库存已校正）→ 仍删除」的 TOCTOU 竞态。
     * 状态条件足以阻断窗口（任何流转必改 status），影响 0 行即并发冲突抛异常，明细删除随之回滚。
     */
    @Transactional
    @BizLog(module = "check", operation = "DELETE", message = "盘点单 {id} 废弃删除成功（级联删明细）")
    public void deleteWithItems(Long id) {
        if (id == null) {
            return;
        }
        InventoryCheck head = getById(id);
        if (head == null) {
            return;
        }
        DocGuard.requireTransitionStatus(head.getStatus(), DocStatus.CREATED.name(), "删除");
        Auths.requireCreatorOrAdmin(head.getCreatedBy());
        int deleted = baseMapper.delete(new LambdaQueryWrapper<InventoryCheck>()
                .eq(InventoryCheck::getId, id)
                .eq(InventoryCheck::getStatus, DocStatus.CREATED.name()));
        if (deleted == 0) {
            throw new IllegalStateException("单据状态已变化或已被他人操作，删除失败，请刷新重试");
        }
        checkItemMapper.delete(new LambdaQueryWrapper<CheckItem>().eq(CheckItem::getCheckId, id));
        operationLogService.recordInTx("check", "DELETE", id, head.getCheckNo(), OperatorContext.getOperatorName(), null);
    }

    /**
     * 编辑盘点单头（白名单字段）：仅本人 CREATED 单据可编辑（ADMIN 不限）。
     *
     * <p>请求经 {@link CheckUpdateRequest} 白名单绑定，{@code id/status/version/createdBy/时间戳} 不可经此修改；
     * 部分更新语义：DTO 字段为 null 表示保持原值。
     * <p>编辑与审计同事务；乐观锁冲突时抛异常提示刷新重试。
     */
    @Transactional
    @BizLog(module = "check", operation = "UPDATE", message = "盘点单 {id} 头部编辑成功（白名单字段）")
    public void updateHead(Long id, CheckUpdateRequest req) {
        InventoryCheck existed = DocGuard.requireExists(getById(id), "盘点单", id);
        if (!DocStatus.CREATED.name().equals(existed.getStatus())) {
            throw new IllegalStateException("仅 CREATED 状态可编辑");
        }
        Auths.requireCreatorOrAdmin(existed.getCreatedBy());
        if (req.getBatchNo() != null) {
            existed.setBatchNo(req.getBatchNo());
        }
        if (!updateById(existed)) {
            throw new IllegalStateException("并发冲突，单据已被他人修改，请刷新后重试");
        }
        operationLogService.recordInTx("check", "UPDATE", id, existed.getCheckNo(), OperatorContext.getOperatorName(), req);
    }

    /** 查询头 + 明细，组装 VO（GET /{id}） */
    public CheckDetailVO getDetail(Long id) {
        InventoryCheck head = getById(id);
        if (head == null) {
            return null;
        }
        return toVo(head, listItems(id));
    }

    /** 查询某头下的明细 */
    public List<CheckItem> listItems(Long checkId) {
        if (checkId == null) {
            return Collections.emptyList();
        }
        return checkItemMapper.selectList(
                new LambdaQueryWrapper<CheckItem>().eq(CheckItem::getCheckId, checkId));
    }

    // ===== 内部方法 =====

    /** 幂等回查：requestId + 当前操作人（P1-7 复合幂等键；操作人缺失时退化为仅 requestId，与历史行为一致） */
    private InventoryCheck findByIdempotent(String requestId) {
        Long operatorId = OperatorContext.getOperatorId();
        return getOne(new LambdaQueryWrapper<InventoryCheck>()
                .eq(InventoryCheck::getRequestId, requestId)
                .eq(operatorId != null, InventoryCheck::getCreatedBy, operatorId), false);
    }

    /**
     * 兼容旧单品字段：items 为空且传了 actualQty 时，转成一条明细。
     *
     * <p>出口统一做五维归一化（P1-4）：品名/物料/规格/等级 trim + 全角转半角后落库，
     * 消除不可见差异在联动时 miss 五维匹配、裂变出新库存行。
     */
    private List<CheckItem> normalizeItems(CheckCreateRequest req) {
        List<CheckItem> items = req.getItems();
        if ((items == null || items.isEmpty()) && req.getActualQty() != null) {
            if (req.getOrgId() == null) {
                throw new IllegalArgumentException("组织(orgId)不能为空");
            }
            // P2-19：兼容单品路径显式校验必填维度（actualQty 已由触发条件保证非空，productName 仍可能缺）
            ItemValidators.requireHasText(req.getProductName(), "品名(productName)");
            CheckItem single = new CheckItem();
            single.setOrgId(req.getOrgId());
            single.setProductName(req.getProductName());
            single.setGrade(req.getGrade());
            single.setSpec(req.getSpec());
            single.setActualQty(req.getActualQty());
            items = new ArrayList<>(List.of(single));
        }
        if (items != null) {
            for (CheckItem it : items) {
                it.setProductName(DimsNormalizer.normalize(it.getProductName()));
                it.setMaterial(DimsNormalizer.normalize(it.getMaterial()));
                it.setSpec(DimsNormalizer.normalize(it.getSpec()));
                it.setGrade(DimsNormalizer.normalize(it.getGrade()));
                // R2-P1-1：账面数量 bookQty 必填（>=0；null 会被 NOT NULL DEFAULT 0 静默篡改为 0 的假账面）
                ItemValidators.requireQtyNotNullOrNegative(it.getBookQty(), "盘点账面数量(bookQty)");
            }
        }
        return items;
    }

    private List<CheckDetailVO> toVoList(List<InventoryCheck> heads) {
        if (heads == null || heads.isEmpty()) {
            return Collections.emptyList();
        }
        Set<Long> ids = heads.stream().map(InventoryCheck::getId).collect(Collectors.toSet());
        List<CheckItem> all = checkItemMapper.selectList(
                new LambdaQueryWrapper<CheckItem>().in(CheckItem::getCheckId, ids));
        Map<Long, List<CheckItem>> grouped = all.stream()
                .collect(Collectors.groupingBy(CheckItem::getCheckId));
        List<CheckDetailVO> vos = new ArrayList<>(heads.size());
        for (InventoryCheck h : heads) {
            vos.add(toVo(h, grouped.getOrDefault(h.getId(), Collections.emptyList())));
        }
        return vos;
    }

    private CheckDetailVO toVo(InventoryCheck head, List<CheckItem> items) {
        CheckDetailVO vo = new CheckDetailVO();
        vo.setId(head.getId());
        vo.setCheckNo(head.getCheckNo());
        vo.setBatchNo(head.getBatchNo());
        vo.setStatus(head.getStatus());
        vo.setCreatedAt(head.getCreatedAt());
        vo.setUpdatedAt(head.getUpdatedAt());
        vo.setVersion(head.getVersion());
        vo.setItems(items == null ? Collections.emptyList() : items);
        vo.setTotalQty(QuantitySupport.sumQty(items, CheckItem::getActualQty));
        return vo;
    }

    /** 盘点汇总取实盘数量 actual_qty */
    /** 生成当天盘点单号（DB 原子取号，多实例安全） */
    private String nextDocNo() {
        return docNoSequenceService.next(PREFIX);
    }

}
