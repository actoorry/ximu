package com.by.ximu.inventory.module.inbound;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.by.ximu.common.Auths;
import com.by.ximu.common.DimsNormalizer;
import com.by.ximu.common.DocStatus;
import com.by.ximu.common.OperatorContext;
import com.by.ximu.common.PageQuery;
import com.by.ximu.common.Role;
import com.by.ximu.inventory.common.ItemValidators;
import com.by.ximu.inventory.common.QuantitySupport;
import com.by.ximu.inventory.common.RetrySupport;
import com.by.ximu.common.web.audit.OperationLogService;
import com.by.ximu.inventory.module.stock.StockOperationService;
import com.by.ximu.inventory.util.DocNoSequenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 入库服务：分页查询 + 单据创建（头 + 明细 + 单号自动生成） + 状态机流转（CREATED → APPROVED → CHECKED）。
 *
 * <p>流转前置校验：仅 CREATED 可批准；仅 APPROVED 可审核；非法迁移抛 {@link IllegalStateException}。
 * <p>审核（CHECKED）后按明细逐行联动增加 {@code inventory_stock} 库存，与状态流转同事务，任一步失败整体回滚。
 */
@Service
@RequiredArgsConstructor
public class InboundService extends ServiceImpl<InboundMapper, Inbound> {

    private static final String PREFIX = "IN";

    private final InboundItemMapper inboundItemMapper;
    private final StockOperationService stockOperationService;
    private final OperationLogService operationLogService;
    private final DocNoSequenceService docNoSequenceService;

    /**
     * 分页查询（支持按状态/入库类型筛选 + keyword 模糊搜索单号/来源单号）。
     * 返回头字段 + totalQty（明细数量汇总）。
     */
    public Map<String, Object> page(PageQuery query, String status, String inboundType) {
        LambdaQueryWrapper<Inbound> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(status)) {
            wrapper.eq(Inbound::getStatus, status);
        }
        if (StringUtils.hasText(inboundType)) {
            wrapper.eq(Inbound::getInboundType, inboundType);
        }
        if (StringUtils.hasText(query.getKeyword())) {
            // R2-P2-26：keyword 走 LIKE '%kw%'（前导通配）无法命中 V7 的 status/created_at 索引，
            // 数据量大时全表扫——当前单量级可接受；若成瓶颈改前缀索引/全文检索或对账侧拉数据
            String kw = query.getKeyword();
            wrapper.and(w -> w.like(Inbound::getInboundNo, kw)
                    .or().like(Inbound::getSourceOrderNo, kw));
        }
        wrapper.orderByDesc(Inbound::getCreatedAt);
        Page<Inbound> p = baseMapper.selectPage(query.buildPage(), wrapper);
        Page<InboundDetailVO> voPage = new Page<>(p.getCurrent(), p.getSize(), p.getTotal());
        voPage.setRecords(toVoList(p.getRecords()));
        return query.toPageMap(voPage);
    }

    /**
     * 创建入库单（头 + 明细 + 单号自动生成）。
     *
     * <p>单号：前端不传则按 {@code IN + yyyyMMdd + 3位序号} 自动生成；传了则校验唯一。
     * <p>兼容：{@code items} 为空但传了旧单品字段（{@code productName/qty}）时自动转成一条明细。
     */
    @Transactional
    public InboundDetailVO create(InboundCreateRequest req) {
        // 0. 幂等：requestId 非空时按「requestId + 当前操作人」查重（P1-7：复合幂等键，
        //    两个用户携带同一 requestId 重试互不串单），命中则返回已存在单据
        String requestId = req.getRequestId();
        if (StringUtils.hasText(requestId)) {
            Inbound existed = findByIdempotent(requestId);
            if (existed != null) {
                return toVo(existed, listItems(existed.getId()));
            }
        }
        // 1. 单号：不传则生成，传了则校验唯一（唯一索引兜底）
        String docNo = req.getInboundNo();
        if (!StringUtils.hasText(docNo)) {
            docNo = nextDocNo();
        } else if (count(new LambdaQueryWrapper<Inbound>().eq(Inbound::getInboundNo, docNo)) > 0) {
            throw new IllegalArgumentException("入库单号已存在: " + docNo);
        }
        // 2. 兼容旧单品字段 → 明细
        List<InboundItem> items = normalizeItems(req);
        // 2.5 明细至少一行（R2-P1-2）：normalizeItems 已把兼容单品转成明细，此处 items 为空即二者皆空，拒绝建空单
        ItemValidators.requireNonEmpty(items, "入库");
        // 3. 保存头
        Inbound head = new Inbound();
        head.setInboundNo(docNo);
        head.setInboundType(req.getInboundType());
        head.setSourceOrderNo(req.getSourceOrderNo());
        head.setChecker(req.getChecker());
        head.setAuditLevel(req.getAuditLevel());
        head.setStatus(DocStatus.CREATED.name());
        head.setCreatedBy(OperatorContext.getOperatorId());
        head.setRequestId(requestId);
        try {
            save(head);
        } catch (DuplicateKeyException e) {
            // 并发下同 requestId 同时插入，唯一索引兜底：返回已存在的单据
            // R2-P2-23：撞键后败方立即回查大概率读不到对手尚未提交的行（对手事务仍持锁）——
            // sleep 200ms 等对手提交后重查一次，仍查不到才按并发冲突拒绝（不再裸抛 DuplicateKey 落 400 固定文案）
            Inbound existed = findByIdempotent(requestId);
            if (existed == null) {
                RetrySupport.sleepQuietly();
                existed = findByIdempotent(requestId);
            }
            if (existed != null) {
                return toVo(existed, listItems(existed.getId()));
            }
            throw new IllegalStateException("并发重复请求，请稍后重试");
        }
        // 4. 保存明细
        if (items != null) {
            for (InboundItem it : items) {
                it.setId(null);
                it.setInboundId(head.getId());
                inboundItemMapper.insert(it);
            }
        }
        operationLogService.recordInTx("inbound", "CREATE", head.getId(), head.getInboundNo(), OperatorContext.getOperatorName(), req);
        return toVo(head, items);
    }

    /**
     * 批准：CREATED → APPROVED。
     *
     * <p>审核级别（auditLevel）为制单人建单/编辑时指定的业务字段，流转不再接受请求体覆盖（P0-1）。
     */
    @Transactional
    public void approve(Long id) {
        Inbound inbound = getById(id);
        if (inbound == null) {
            throw new IllegalArgumentException("入库单不存在: " + id);
        }
        Auths.requireRole(Role.APPROVER, Role.ADMIN);
        Auths.requireNotSelfOrAdmin(inbound.getCreatedBy());
        if (!DocStatus.CREATED.name().equals(inbound.getStatus())) {
            throw new IllegalStateException("当前状态[" + inbound.getStatus() + "]不允许批准，仅 CREATED 状态可批准");
        }
        inbound.setStatus(DocStatus.APPROVED.name());
        if (!updateById(inbound)) {
            throw new IllegalStateException("单据已被他人操作，请刷新重试");
        }
        operationLogService.recordInTx("inbound", "APPROVE", id, inbound.getInboundNo(), OperatorContext.getOperatorName(),
                Map.of("auditLevel", inbound.getAuditLevel() == null ? "" : inbound.getAuditLevel()));
    }

    /**
     * 审核：APPROVED → CHECKED，审核人取可信登录人；按明细逐行联动增加库存。
     *
     * <p>审核人不信任请求体（P0-1），一律取 {@link OperatorContext#getOperatorName()}。
     */
    @Transactional
    public void check(Long id) {
        Inbound inbound = getById(id);
        if (inbound == null) {
            throw new IllegalArgumentException("入库单不存在: " + id);
        }
        Auths.requireRole(Role.CHECKER, Role.ADMIN);
        Auths.requireNotSelfOrAdmin(inbound.getCreatedBy());
        if (!DocStatus.APPROVED.name().equals(inbound.getStatus())) {
            throw new IllegalStateException("当前状态[" + inbound.getStatus() + "]不允许审核，仅 APPROVED 状态可审核");
        }
        String checker = requireOperatorName();
        inbound.setStatus(DocStatus.CHECKED.name());
        inbound.setChecker(checker);
        if (!updateById(inbound)) {
            throw new IllegalStateException("单据已被他人操作，请刷新重试");
        }
        // 库存联动：按明细逐行增加库存（settle_qty 优先，无则用 qty）；
        // 先按五维键排序再联动（P1-3），保证并发审核不同单据时对 inventory_stock 以一致行序加锁，消除交叉死锁
        List<InboundItem> items = listItems(id);
        items.sort(Comparator.comparing(it -> StockOperationService.dimsKey(
                it.getOrgId(), it.getProductName(), it.getMaterial(), it.getSpec(), it.getGrade())));
        for (InboundItem it : items) {
            BigDecimal qty = it.getSettleQty() != null ? it.getSettleQty() : it.getQty();
            stockOperationService.increaseStock(it.getOrgId(), it.getGrade(), it.getProductName(), it.getMaterial(), it.getSpec(), qty);
        }
        operationLogService.recordInTx("inbound", "CHECK", id, inbound.getInboundNo(), OperatorContext.getOperatorName(),
                Map.of("checker", checker));
    }

    /** 审核人必须来自可信登录上下文；缺失说明过滤器链异常，宁可拒绝落库也不写空值/伪造值 */
    private String requireOperatorName() {
        String name = OperatorContext.getOperatorName();
        if (name == null || name.isBlank()) {
            throw new IllegalStateException("操作人上下文缺失，无法记录审核人");
        }
        return name;
    }

    /** 幂等回查：requestId + 当前操作人（P1-7 复合幂等键；操作人缺失时退化为仅 requestId，与历史行为一致） */
    private Inbound findByIdempotent(String requestId) {
        Long operatorId = OperatorContext.getOperatorId();
        return getOne(new LambdaQueryWrapper<Inbound>()
                .eq(Inbound::getRequestId, requestId)
                .eq(operatorId != null, Inbound::getCreatedBy, operatorId), false);
    }

    /**
     * 级联删除：先按「id + CREATED 状态」条件删头，再删明细（同事务）。
     *
     * <p>条件删除（P0-3）：防止「读到 CREATED → 并发流转（状态变、库存已联动）→ 仍删除」的 TOCTOU 竞态。
     * 状态条件足以阻断窗口（任何流转必改 status），影响 0 行即并发冲突抛异常，明细删除随之回滚。
     */
    @Transactional
    public void deleteWithItems(Long id) {
        if (id == null) {
            return;
        }
        Inbound head = getById(id);
        if (head == null) {
            return;
        }
        if (!DocStatus.CREATED.name().equals(head.getStatus())) {
            throw new IllegalStateException("当前状态[" + head.getStatus() + "]不允许删除，仅 CREATED 状态可删除");
        }
        Auths.requireCreatorOrAdmin(head.getCreatedBy());
        int deleted = baseMapper.delete(new LambdaQueryWrapper<Inbound>()
                .eq(Inbound::getId, id)
                .eq(Inbound::getStatus, DocStatus.CREATED.name()));
        if (deleted == 0) {
            throw new IllegalStateException("单据状态已变化或已被他人操作，删除失败，请刷新重试");
        }
        inboundItemMapper.delete(new LambdaQueryWrapper<InboundItem>().eq(InboundItem::getInboundId, id));
        operationLogService.recordInTx("inbound", "DELETE", id, head.getInboundNo(), OperatorContext.getOperatorName(), null);
    }

    /**
     * 编辑入库单头（白名单字段）：仅本人 CREATED 单据可编辑（ADMIN 不限）。
     *
     * <p>请求经 {@link InboundUpdateRequest} 白名单绑定，{@code id/status/version/createdBy/时间戳} 不可经此修改；
     * 部分更新语义：DTO 字段为 null 表示保持原值。
     * <p>编辑与审计同事务；乐观锁冲突时抛异常提示刷新重试。
     */
    @Transactional
    public void updateHead(Long id, InboundUpdateRequest req) {
        Inbound existed = getById(id);
        if (existed == null) {
            throw new IllegalArgumentException("入库单不存在: " + id);
        }
        if (!DocStatus.CREATED.name().equals(existed.getStatus())) {
            throw new IllegalStateException("仅 CREATED 状态可编辑");
        }
        Auths.requireCreatorOrAdmin(existed.getCreatedBy());
        if (req.getInboundType() != null) {
            existed.setInboundType(req.getInboundType());
        }
        if (req.getSourceOrderNo() != null) {
            existed.setSourceOrderNo(req.getSourceOrderNo());
        }
        if (req.getChecker() != null) {
            existed.setChecker(req.getChecker());
        }
        if (req.getAuditLevel() != null) {
            existed.setAuditLevel(req.getAuditLevel());
        }
        if (!updateById(existed)) {
            throw new IllegalStateException("并发冲突，单据已被他人修改，请刷新后重试");
        }
        operationLogService.recordInTx("inbound", "UPDATE", id, existed.getInboundNo(), OperatorContext.getOperatorName(), req);
    }

    /** 查询头 + 明细，组装 VO（GET /{id}） */
    public InboundDetailVO getDetail(Long id) {
        Inbound head = getById(id);
        if (head == null) {
            return null;
        }
        return toVo(head, listItems(id));
    }

    /** 查询某头下的明细 */
    public List<InboundItem> listItems(Long inboundId) {
        if (inboundId == null) {
            return Collections.emptyList();
        }
        return inboundItemMapper.selectList(
                new LambdaQueryWrapper<InboundItem>().eq(InboundItem::getInboundId, inboundId));
    }

    // ===== 内部方法 =====

    /**
     * 兼容旧单品字段：items 为空且传了 productName 时，转成一条明细。
     *
     * <p>出口统一做五维归一化（P1-4）：品名/物料/规格/等级 trim + 全角转半角后落库，
     * 消除「" 铜管" 与 "铜管"」类不可见差异在联动时 miss 五维匹配、裂变出新库存行。
     */
    private List<InboundItem> normalizeItems(InboundCreateRequest req) {
        List<InboundItem> items = req.getItems();
        if ((items == null || items.isEmpty()) && StringUtils.hasText(req.getProductName())) {
            if (req.getOrgId() == null) {
                throw new IllegalArgumentException("组织(orgId)不能为空");
            }
            InboundItem single = new InboundItem();
            single.setOrgId(req.getOrgId());
            single.setProductName(req.getProductName());
            single.setGrade(req.getGrade());
            single.setQty(req.getQty());
            single.setSettleQty(req.getSettleQty());
            items = new ArrayList<>(List.of(single));
        }
        if (items != null) {
            for (InboundItem it : items) {
                it.setProductName(DimsNormalizer.normalize(it.getProductName()));
                it.setMaterial(DimsNormalizer.normalize(it.getMaterial()));
                it.setSpec(DimsNormalizer.normalize(it.getSpec()));
                it.setGrade(DimsNormalizer.normalize(it.getGrade()));
                // R2-P1-1：qty 必填且 >0（@Positive 不拦 null，null/0 会在联动时静默跳过）；settleQty 可选但非空则必须 >0
                ItemValidators.requireQtyPositive(it.getQty(), "入库明细数量(qty)");
                if (it.getSettleQty() != null) {
                    ItemValidators.requireQtyPositive(it.getSettleQty(), "结算数量(settleQty)");
                }
            }
        }
        return items;
    }

    private List<InboundDetailVO> toVoList(List<Inbound> heads) {
        if (heads == null || heads.isEmpty()) {
            return Collections.emptyList();
        }
        Set<Long> ids = heads.stream().map(Inbound::getId).collect(Collectors.toSet());
        List<InboundItem> all = inboundItemMapper.selectList(
                new LambdaQueryWrapper<InboundItem>().in(InboundItem::getInboundId, ids));
        Map<Long, List<InboundItem>> grouped = all.stream()
                .collect(Collectors.groupingBy(InboundItem::getInboundId));
        List<InboundDetailVO> vos = new ArrayList<>(heads.size());
        for (Inbound h : heads) {
            vos.add(toVo(h, grouped.getOrDefault(h.getId(), Collections.emptyList())));
        }
        return vos;
    }

    private InboundDetailVO toVo(Inbound head, List<InboundItem> items) {
        InboundDetailVO vo = new InboundDetailVO();
        vo.setId(head.getId());
        vo.setInboundNo(head.getInboundNo());
        vo.setInboundType(head.getInboundType());
        vo.setSourceOrderNo(head.getSourceOrderNo());
        vo.setStatus(head.getStatus());
        vo.setChecker(head.getChecker());
        vo.setAuditLevel(head.getAuditLevel());
        vo.setCreatedAt(head.getCreatedAt());
        vo.setUpdatedAt(head.getUpdatedAt());
        vo.setVersion(head.getVersion());
        vo.setItems(items == null ? Collections.emptyList() : items);
        vo.setTotalQty(QuantitySupport.sumQty(items, InboundItem::getQty));
        return vo;
    }

    /** 生成当天入库单号（DB 原子取号，多实例安全） */
    private String nextDocNo() {
        return docNoSequenceService.next(PREFIX);
    }

}
