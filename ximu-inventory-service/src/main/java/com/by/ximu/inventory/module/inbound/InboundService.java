package com.by.ximu.inventory.module.inbound;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.by.ximu.common.Auths;
import com.by.ximu.common.OperatorContext;
import com.by.ximu.common.PageQuery;
import com.by.ximu.common.Role;
import com.by.ximu.inventory.module.log.OperationLogService;
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
import java.util.HashMap;
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
            String kw = query.getKeyword();
            wrapper.and(w -> w.like(Inbound::getInboundNo, kw)
                    .or().like(Inbound::getSourceOrderNo, kw));
        }
        wrapper.orderByDesc(Inbound::getCreatedAt);
        Page<Inbound> p = baseMapper.selectPage(buildPage(query), wrapper);
        Map<String, Object> map = new HashMap<>();
        map.put("list", toVoList(p.getRecords()));
        map.put("total", p.getTotal());
        return map;
    }

    /**
     * 创建入库单（头 + 明细 + 单号自动生成）。
     *
     * <p>单号：前端不传则按 {@code IN + yyyyMMdd + 3位序号} 自动生成；传了则校验唯一。
     * <p>兼容：{@code items} 为空但传了旧单品字段（{@code productName/qty}）时自动转成一条明细。
     */
    @Transactional
    public InboundDetailVO create(InboundCreateRequest req) {
        Auths.requireRole(Role.CREATOR, Role.ADMIN);
        // 0. 幂等：requestId 非空时先查重，命中则返回已存在单据（防双击/重试重复建单）
        String requestId = req.getRequestId();
        if (StringUtils.hasText(requestId)) {
            Inbound existed = getOne(new LambdaQueryWrapper<Inbound>().eq(Inbound::getRequestId, requestId), false);
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
        // 3. 保存头
        Inbound head = new Inbound();
        head.setInboundNo(docNo);
        head.setInboundType(req.getInboundType());
        head.setSourceOrderNo(req.getSourceOrderNo());
        head.setChecker(req.getChecker());
        head.setAuditLevel(req.getAuditLevel());
        head.setStatus("CREATED");
        head.setCreatedBy(OperatorContext.getOperatorId());
        head.setRequestId(requestId);
        try {
            save(head);
        } catch (DuplicateKeyException e) {
            // 并发下同 requestId 同时插入，唯一索引兜底：返回已存在的单据
            Inbound existed = getOne(new LambdaQueryWrapper<Inbound>().eq(Inbound::getRequestId, requestId), false);
            if (existed != null) {
                return toVo(existed, listItems(existed.getId()));
            }
            throw e;
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
     * 批准：CREATED → APPROVED，并记录审核级别。
     */
    @Transactional
    public void approve(Long id, String auditLevel) {
        Inbound inbound = getById(id);
        if (inbound == null) {
            throw new IllegalArgumentException("入库单不存在: " + id);
        }
        Auths.requireRole(Role.APPROVER, Role.ADMIN);
        Auths.requireNotSelfOrAdmin(inbound.getCreatedBy());
        if (!"CREATED".equals(inbound.getStatus())) {
            throw new IllegalStateException("当前状态[" + inbound.getStatus() + "]不允许批准，仅 CREATED 状态可批准");
        }
        inbound.setStatus("APPROVED");
        inbound.setAuditLevel(auditLevel);
        if (!updateById(inbound)) {
            throw new IllegalStateException("单据已被他人操作，请刷新重试");
        }
        operationLogService.recordInTx("inbound", "APPROVE", id, inbound.getInboundNo(), OperatorContext.getOperatorName(),
                Map.of("auditLevel", auditLevel == null ? "" : auditLevel));
    }

    /**
     * 审核：APPROVED → CHECKED，并记录审核人；按明细逐行联动增加库存。
     */
    @Transactional
    public void check(Long id, String checker) {
        Inbound inbound = getById(id);
        if (inbound == null) {
            throw new IllegalArgumentException("入库单不存在: " + id);
        }
        Auths.requireRole(Role.CHECKER, Role.ADMIN);
        Auths.requireNotSelfOrAdmin(inbound.getCreatedBy());
        if (!"APPROVED".equals(inbound.getStatus())) {
            throw new IllegalStateException("当前状态[" + inbound.getStatus() + "]不允许审核，仅 APPROVED 状态可审核");
        }
        inbound.setStatus("CHECKED");
        inbound.setChecker(checker);
        if (!updateById(inbound)) {
            throw new IllegalStateException("单据已被他人操作，请刷新重试");
        }
        // 库存联动：按明细逐行增加库存（settle_qty 优先，无则用 qty）
        for (InboundItem it : listItems(id)) {
            BigDecimal qty = it.getSettleQty() != null ? it.getSettleQty() : it.getQty();
            stockOperationService.increaseStock(it.getOrgId(), it.getGrade(), it.getProductName(), it.getMaterial(), it.getSpec(), qty);
        }
        operationLogService.recordInTx("inbound", "CHECK", id, inbound.getInboundNo(), OperatorContext.getOperatorName(),
                Map.of("checker", checker == null ? "" : checker));
    }

    /**
     * 级联删除：先删明细，再删头（同事务）。
     */
    @Transactional
    public void deleteWithItems(Long id) {
        if (id == null) {
            return;
        }
        Inbound head = getById(id);
        if (head != null && !"CREATED".equals(head.getStatus())) {
            throw new IllegalStateException("当前状态[" + head.getStatus() + "]不允许删除，仅 CREATED 状态可删除");
        }
        if (head != null) {
            Auths.requireCreatorOrAdmin(head.getCreatedBy());
        }
        inboundItemMapper.delete(new LambdaQueryWrapper<InboundItem>().eq(InboundItem::getInboundId, id));
        removeById(id);
        if (head != null) {
            operationLogService.recordInTx("inbound", "DELETE", id, head.getInboundNo(), OperatorContext.getOperatorName(), null);
        }
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
        if (!"CREATED".equals(existed.getStatus())) {
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

    /** 兼容旧单品字段：items 为空且传了 productName 时，转成一条明细 */
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
            return new ArrayList<>(List.of(single));
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
        vo.setTotalQty(sumQty(items));
        return vo;
    }

    private BigDecimal sumQty(List<InboundItem> items) {
        if (items == null || items.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return items.stream()
                .map(i -> i.getQty() == null ? BigDecimal.ZERO : i.getQty())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** 生成当天入库单号（DB 原子取号，多实例安全） */
    private String nextDocNo() {
        return docNoSequenceService.next(PREFIX);
    }

    private Page<Inbound> buildPage(PageQuery query) {
        int p = query.getPage() == null || query.getPage() < 1 ? 1 : query.getPage();
        int s = query.getSize() == null || query.getSize() < 1 ? 10 : query.getSize();
        s = Math.min(s, 200);
        return new Page<>(p, s);
    }
}
