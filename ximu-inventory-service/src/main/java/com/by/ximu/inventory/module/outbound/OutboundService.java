package com.by.ximu.inventory.module.outbound;

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
import com.by.ximu.inventory.module.stock.StockOperationService;
import com.by.ximu.inventory.util.DocNoSequenceService;
import lombok.RequiredArgsConstructor;
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
 * 出库服务：分页查询 + 单据创建（头 + 明细 + 单号自动生成） + 状态机流转（CREATED → APPROVED）。
 *
 * <p>流转前置校验：仅 CREATED 可批准；非法迁移抛 {@link IllegalStateException}。
 * <p>批准（APPROVED）后按明细逐行联动扣减 {@code inventory_stock}，库存不足抛异常整体回滚。
 */
@Service
@RequiredArgsConstructor
public class OutboundService extends ServiceImpl<OutboundMapper, Outbound> {

    private static final String PREFIX = "OUT";

    private final OutboundItemMapper outboundItemMapper;
    private final StockOperationService stockOperationService;
    private final OperationLogService operationLogService;
    private final DocNoSequenceService docNoSequenceService;

    /**
     * 分页查询（支持按状态筛选 + keyword 模糊搜索单号/销售单号）。
     * 返回头字段 + totalQty（明细数量汇总）。
     */
    public Map<String, Object> page(PageQuery query, String status) {
        LambdaQueryWrapper<Outbound> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(status)) {
            wrapper.eq(Outbound::getStatus, status);
        }
        if (StringUtils.hasText(query.getKeyword())) {
            // R2-P2-26：keyword 走 LIKE '%kw%'（前导通配）无法命中 V7 的 status/created_at 索引，
            // 数据量大时全表扫——当前单量级可接受；若成瓶颈改前缀索引/全文检索或对账侧拉数据
            String kw = query.getKeyword();
            wrapper.and(w -> w.like(Outbound::getOutboundNo, kw)
                    .or().like(Outbound::getSaleOrderNo, kw));
        }
        wrapper.orderByDesc(Outbound::getCreatedAt);
        Page<Outbound> p = baseMapper.selectPage(query.buildPage(), wrapper);
        Page<OutboundDetailVO> voPage = new Page<>(p.getCurrent(), p.getSize(), p.getTotal());
        voPage.setRecords(toVoList(p.getRecords()));
        return query.toPageMap(voPage);
    }

    /**
     * 创建出库单（头 + 明细 + 单号自动生成）。
     *
     * <p>单号：前端不传则按 {@code OUT + yyyyMMdd + 3位序号} 自动生成；传了则校验唯一。
     * <p>兼容：{@code items} 为空但传了旧单品字段（{@code productName/qty}）时自动转成一条明细。
     */
    @Transactional
    public OutboundDetailVO create(OutboundCreateRequest req) {
        // 幂等：requestId 非空时按「requestId + 当前操作人」查重（P1-7：复合幂等键），
        // 命中则返回已存在单据
        String requestId = req.getRequestId();
        if (StringUtils.hasText(requestId)) {
            Outbound existed = findByIdempotent(requestId);
            if (existed != null) {
                return toVo(existed, listItems(existed.getId()));
            }
        }
        String docNo = req.getOutboundNo();
        if (!StringUtils.hasText(docNo)) {
            docNo = nextDocNo();
        } else if (count(new LambdaQueryWrapper<Outbound>().eq(Outbound::getOutboundNo, docNo)) > 0) {
            throw new IllegalArgumentException("出库单号已存在: " + docNo);
        }
        List<OutboundItem> items = normalizeItems(req);
        // 明细至少一行（R2-P1-2）：normalizeItems 已把兼容单品转成明细，此处 items 为空即二者皆空，拒绝建空单
        ItemValidators.requireNonEmpty(items, "出库");
        Outbound head = new Outbound();
        head.setOutboundNo(docNo);
        head.setSaleOrderNo(req.getSaleOrderNo());
        head.setFreightBearer(req.getFreightBearer());
        head.setCarrier(req.getCarrier());
        head.setPlateNo(req.getPlateNo());
        head.setDriver(req.getDriver());
        head.setDriverPhone(req.getDriverPhone());
        head.setStatus(DocStatus.CREATED.name());
        head.setCreatedBy(OperatorContext.getOperatorId());
        head.setRequestId(requestId);
        try {
            save(head);
        } catch (DuplicateKeyException e) {
            // 并发下同 requestId 同时插入，唯一索引兜底：R2-P2-23 撞键后回查 → sleep 退避 → 再回查，
            // 命中返回已存在单据，仍查不到才按并发冲突拒绝（不再裸抛 DuplicateKey 落 400 固定文案）
            Outbound existed = RetrySupport.retryIdempotent(() -> findByIdempotent(requestId));
            return toVo(existed, listItems(existed.getId()));
        }
        if (items != null) {
            for (OutboundItem it : items) {
                it.setId(null);
                it.setOutboundId(head.getId());
                outboundItemMapper.insert(it);
            }
        }
        operationLogService.recordInTx("outbound", "CREATE", head.getId(), head.getOutboundNo(), OperatorContext.getOperatorName(), req);
        return toVo(head, items);
    }

    /**
     * 批准：CREATED → APPROVED；按明细逐行联动扣减库存（库存不足抛 {@link IllegalStateException}，整体回滚）。
     */
    @Transactional
    public void approve(Long id) {
        Outbound outbound = DocGuard.requireExists(getById(id), "出库单", id);
        Auths.requireRole(Role.APPROVER, Role.ADMIN);
        Auths.requireNotSelfOrAdmin(outbound.getCreatedBy());
        DocGuard.requireTransitionStatus(outbound.getStatus(), DocStatus.CREATED.name(), "批准");
        outbound.setStatus(DocStatus.APPROVED.name());
        DocGuard.requireUpdateSucceeded(updateById(outbound));
        // 库存联动：按明细逐行扣减；先按五维键排序（P1-3），
        // 保证并发批准不同单据时对 inventory_stock 以一致行序加锁，消除交叉死锁
        List<OutboundItem> items = listItems(id);
        items.sort(Comparator.comparing(it -> StockOperationService.dimsKey(
                it.getOrgId(), it.getProductName(), it.getMaterial(), it.getSpec(), it.getGrade())));
        for (OutboundItem it : items) {
            stockOperationService.decreaseStock(it.getOrgId(), it.getGrade(), it.getProductName(), it.getMaterial(), it.getSpec(), it.getQty());
        }
        operationLogService.recordInTx("outbound", "APPROVE", id, outbound.getOutboundNo(), OperatorContext.getOperatorName(), null);
    }

    /**
     * 级联删除：先按「id + CREATED 状态」条件删头，再删明细（同事务）。
     *
     * <p>条件删除（P0-3）：防止「读到 CREATED → 并发流转（状态变、库存已扣减）→ 仍删除」的 TOCTOU 竞态。
     * 状态条件足以阻断窗口（任何流转必改 status），影响 0 行即并发冲突抛异常，明细删除随之回滚。
     */
    @Transactional
    public void deleteWithItems(Long id) {
        if (id == null) {
            return;
        }
        Outbound head = getById(id);
        if (head == null) {
            return;
        }
        DocGuard.requireTransitionStatus(head.getStatus(), DocStatus.CREATED.name(), "删除");
        Auths.requireCreatorOrAdmin(head.getCreatedBy());
        int deleted = baseMapper.delete(new LambdaQueryWrapper<Outbound>()
                .eq(Outbound::getId, id)
                .eq(Outbound::getStatus, DocStatus.CREATED.name()));
        if (deleted == 0) {
            throw new IllegalStateException("单据状态已变化或已被他人操作，删除失败，请刷新重试");
        }
        outboundItemMapper.delete(new LambdaQueryWrapper<OutboundItem>().eq(OutboundItem::getOutboundId, id));
        operationLogService.recordInTx("outbound", "DELETE", id, head.getOutboundNo(), OperatorContext.getOperatorName(), null);
    }

    /**
     * 编辑出库单头（白名单字段）：仅本人 CREATED 单据可编辑（ADMIN 不限）。
     *
     * <p>请求经 {@link OutboundUpdateRequest} 白名单绑定，{@code id/status/version/createdBy/时间戳} 不可经此修改；
     * 部分更新语义：DTO 字段为 null 表示保持原值。
     * <p>编辑与审计同事务；乐观锁冲突时抛异常提示刷新重试。
     */
    @Transactional
    public void updateHead(Long id, OutboundUpdateRequest req) {
        Outbound existed = DocGuard.requireExists(getById(id), "出库单", id);
        if (!DocStatus.CREATED.name().equals(existed.getStatus())) {
            throw new IllegalStateException("仅 CREATED 状态可编辑");
        }
        Auths.requireCreatorOrAdmin(existed.getCreatedBy());
        if (req.getSaleOrderNo() != null) {
            existed.setSaleOrderNo(req.getSaleOrderNo());
        }
        if (req.getFreightBearer() != null) {
            existed.setFreightBearer(req.getFreightBearer());
        }
        if (req.getCarrier() != null) {
            existed.setCarrier(req.getCarrier());
        }
        if (req.getPlateNo() != null) {
            existed.setPlateNo(req.getPlateNo());
        }
        if (req.getDriver() != null) {
            existed.setDriver(req.getDriver());
        }
        if (req.getDriverPhone() != null) {
            existed.setDriverPhone(req.getDriverPhone());
        }
        if (!updateById(existed)) {
            throw new IllegalStateException("并发冲突，单据已被他人修改，请刷新后重试");
        }
        operationLogService.recordInTx("outbound", "UPDATE", id, existed.getOutboundNo(), OperatorContext.getOperatorName(), req);
    }

    /** 查询头 + 明细，组装 VO（GET /{id}） */
    public OutboundDetailVO getDetail(Long id) {
        Outbound head = getById(id);
        if (head == null) {
            return null;
        }
        return toVo(head, listItems(id));
    }

    /** 查询某头下的明细 */
    public List<OutboundItem> listItems(Long outboundId) {
        if (outboundId == null) {
            return Collections.emptyList();
        }
        return outboundItemMapper.selectList(
                new LambdaQueryWrapper<OutboundItem>().eq(OutboundItem::getOutboundId, outboundId));
    }

    /** 幂等回查：requestId + 当前操作人（P1-7 复合幂等键；操作人缺失时退化为仅 requestId，与历史行为一致） */
    private Outbound findByIdempotent(String requestId) {
        Long operatorId = OperatorContext.getOperatorId();
        return getOne(new LambdaQueryWrapper<Outbound>()
                .eq(Outbound::getRequestId, requestId)
                .eq(operatorId != null, Outbound::getCreatedBy, operatorId), false);
    }

    // ===== 内部方法 =====

    /**
     * 兼容旧单品字段：items 为空且传了 productName 时，转成一条明细。
     *
     * <p>出口统一做五维归一化（P1-4）：品名/物料/规格/等级 trim + 全角转半角后落库，
     * 消除不可见差异在联动时 miss 五维匹配、裂变出新库存行。
     */
    private List<OutboundItem> normalizeItems(OutboundCreateRequest req) {
        List<OutboundItem> items = req.getItems();
        if ((items == null || items.isEmpty()) && StringUtils.hasText(req.getProductName())) {
            if (req.getOrgId() == null) {
                throw new IllegalArgumentException("组织(orgId)不能为空");
            }
            OutboundItem single = new OutboundItem();
            single.setOrgId(req.getOrgId());
            single.setProductName(req.getProductName());
            single.setGrade(req.getGrade());
            single.setQty(req.getQty());
            items = new ArrayList<>(List.of(single));
        }
        if (items != null) {
            for (OutboundItem it : items) {
                it.setProductName(DimsNormalizer.normalize(it.getProductName()));
                it.setMaterial(DimsNormalizer.normalize(it.getMaterial()));
                it.setSpec(DimsNormalizer.normalize(it.getSpec()));
                it.setGrade(DimsNormalizer.normalize(it.getGrade()));
                // R2-P1-1：qty 必填且 >0（@Positive 不拦 null，null/0 会在批准联动时静默跳过扣减）
                ItemValidators.requireQtyPositive(it.getQty(), "出库明细数量(qty)");
            }
        }
        return items;
    }

    private List<OutboundDetailVO> toVoList(List<Outbound> heads) {
        if (heads == null || heads.isEmpty()) {
            return Collections.emptyList();
        }
        Set<Long> ids = heads.stream().map(Outbound::getId).collect(Collectors.toSet());
        List<OutboundItem> all = outboundItemMapper.selectList(
                new LambdaQueryWrapper<OutboundItem>().in(OutboundItem::getOutboundId, ids));
        Map<Long, List<OutboundItem>> grouped = all.stream()
                .collect(Collectors.groupingBy(OutboundItem::getOutboundId));
        List<OutboundDetailVO> vos = new ArrayList<>(heads.size());
        for (Outbound h : heads) {
            vos.add(toVo(h, grouped.getOrDefault(h.getId(), Collections.emptyList())));
        }
        return vos;
    }

    private OutboundDetailVO toVo(Outbound head, List<OutboundItem> items) {
        OutboundDetailVO vo = new OutboundDetailVO();
        vo.setId(head.getId());
        vo.setOutboundNo(head.getOutboundNo());
        vo.setSaleOrderNo(head.getSaleOrderNo());
        vo.setFreightBearer(head.getFreightBearer());
        vo.setCarrier(head.getCarrier());
        vo.setPlateNo(head.getPlateNo());
        vo.setDriver(head.getDriver());
        vo.setDriverPhone(head.getDriverPhone());
        vo.setStatus(head.getStatus());
        vo.setCreatedAt(head.getCreatedAt());
        vo.setUpdatedAt(head.getUpdatedAt());
        vo.setVersion(head.getVersion());
        vo.setItems(items == null ? Collections.emptyList() : items);
        vo.setTotalQty(QuantitySupport.sumQty(items, OutboundItem::getQty));
        return vo;
    }

    /** 生成当天出库单号（DB 原子取号，多实例安全） */
    private String nextDocNo() {
        return docNoSequenceService.next(PREFIX);
    }

}
