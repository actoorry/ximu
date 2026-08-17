package com.by.ximu.inventory.module.outbound;

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
            String kw = query.getKeyword();
            wrapper.and(w -> w.like(Outbound::getOutboundNo, kw)
                    .or().like(Outbound::getSaleOrderNo, kw));
        }
        wrapper.orderByDesc(Outbound::getCreatedAt);
        Page<Outbound> p = baseMapper.selectPage(buildPage(query), wrapper);
        Map<String, Object> map = new HashMap<>();
        map.put("list", toVoList(p.getRecords()));
        map.put("total", p.getTotal());
        return map;
    }

    /**
     * 创建出库单（头 + 明细 + 单号自动生成）。
     *
     * <p>单号：前端不传则按 {@code OUT + yyyyMMdd + 3位序号} 自动生成；传了则校验唯一。
     * <p>兼容：{@code items} 为空但传了旧单品字段（{@code productName/qty}）时自动转成一条明细。
     */
    @Transactional
    public OutboundDetailVO create(OutboundCreateRequest req) {
        Auths.requireRole(Role.CREATOR, Role.ADMIN);
        // 幂等：requestId 非空时先查重，命中则返回已存在单据
        String requestId = req.getRequestId();
        if (StringUtils.hasText(requestId)) {
            Outbound existed = getOne(new LambdaQueryWrapper<Outbound>().eq(Outbound::getRequestId, requestId), false);
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
        Outbound head = new Outbound();
        head.setOutboundNo(docNo);
        head.setSaleOrderNo(req.getSaleOrderNo());
        head.setFreightBearer(req.getFreightBearer());
        head.setCarrier(req.getCarrier());
        head.setPlateNo(req.getPlateNo());
        head.setDriver(req.getDriver());
        head.setDriverPhone(req.getDriverPhone());
        head.setStatus("CREATED");
        head.setCreatedBy(OperatorContext.getOperatorId());
        head.setRequestId(requestId);
        try {
            save(head);
        } catch (DuplicateKeyException e) {
            Outbound existed = getOne(new LambdaQueryWrapper<Outbound>().eq(Outbound::getRequestId, requestId), false);
            if (existed != null) {
                return toVo(existed, listItems(existed.getId()));
            }
            throw e;
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
        Outbound outbound = getById(id);
        if (outbound == null) {
            throw new IllegalArgumentException("出库单不存在: " + id);
        }
        Auths.requireRole(Role.APPROVER, Role.ADMIN);
        Auths.requireNotSelfOrAdmin(outbound.getCreatedBy());
        if (!"CREATED".equals(outbound.getStatus())) {
            throw new IllegalStateException("当前状态[" + outbound.getStatus() + "]不允许批准，仅 CREATED 状态可批准");
        }
        outbound.setStatus("APPROVED");
        updateById(outbound);
        // 库存联动：按明细逐行扣减
        for (OutboundItem it : listItems(id)) {
            stockOperationService.decreaseStock(it.getOrgId(), it.getGrade(), it.getProductName(), it.getSpec(), it.getQty());
        }
        operationLogService.recordInTx("outbound", "APPROVE", id, outbound.getOutboundNo(), OperatorContext.getOperatorName(), null);
    }

    /**
     * 级联删除：先删明细，再删头（同事务）。
     */
    @Transactional
    public void deleteWithItems(Long id) {
        if (id == null) {
            return;
        }
        Outbound head = getById(id);
        if (head != null && !"CREATED".equals(head.getStatus())) {
            throw new IllegalStateException("当前状态[" + head.getStatus() + "]不允许删除，仅 CREATED 状态可删除");
        }
        if (head != null) {
            Auths.requireCreatorOrAdmin(head.getCreatedBy());
        }
        outboundItemMapper.delete(new LambdaQueryWrapper<OutboundItem>().eq(OutboundItem::getOutboundId, id));
        removeById(id);
        if (head != null) {
            operationLogService.recordInTx("outbound", "DELETE", id, head.getOutboundNo(), OperatorContext.getOperatorName(), null);
        }
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
        Outbound existed = getById(id);
        if (existed == null) {
            throw new IllegalArgumentException("出库单不存在: " + id);
        }
        if (!"CREATED".equals(existed.getStatus())) {
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

    // ===== 内部方法 =====

    private List<OutboundItem> normalizeItems(OutboundCreateRequest req) {
        List<OutboundItem> items = req.getItems();
        if ((items == null || items.isEmpty()) && StringUtils.hasText(req.getProductName())) {
            OutboundItem single = new OutboundItem();
            single.setOrgId(req.getOrgId());
            single.setProductName(req.getProductName());
            single.setGrade(req.getGrade());
            single.setQty(req.getQty());
            return new ArrayList<>(List.of(single));
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
        vo.setTotalQty(sumQty(items));
        return vo;
    }

    private BigDecimal sumQty(List<OutboundItem> items) {
        if (items == null || items.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return items.stream()
                .map(i -> i.getQty() == null ? BigDecimal.ZERO : i.getQty())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** 生成当天出库单号（DB 原子取号，多实例安全） */
    private String nextDocNo() {
        return docNoSequenceService.next(PREFIX);
    }

    private Page<Outbound> buildPage(PageQuery query) {
        int p = query.getPage() == null || query.getPage() < 1 ? 1 : query.getPage();
        int s = query.getSize() == null || query.getSize() < 1 ? 10 : query.getSize();
        s = Math.min(s, 200);
        return new Page<>(p, s);
    }
}
