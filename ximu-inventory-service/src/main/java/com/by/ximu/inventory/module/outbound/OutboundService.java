package com.by.ximu.inventory.module.outbound;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.by.ximu.common.PageQuery;
import com.by.ximu.inventory.module.stock.StockOperationService;
import com.by.ximu.inventory.util.DocNoGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
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
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final Object DOCNO_LOCK = new Object();

    private final OutboundItemMapper outboundItemMapper;
    private final StockOperationService stockOperationService;

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
        save(head);
        if (items != null) {
            for (OutboundItem it : items) {
                it.setId(null);
                it.setOutboundId(head.getId());
                outboundItemMapper.insert(it);
            }
        }
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
        if (!"CREATED".equals(outbound.getStatus())) {
            throw new IllegalStateException("当前状态[" + outbound.getStatus() + "]不允许批准，仅 CREATED 状态可批准");
        }
        outbound.setStatus("APPROVED");
        updateById(outbound);
        // 库存联动：按明细逐行扣减
        for (OutboundItem it : listItems(id)) {
            stockOperationService.decreaseStock(it.getProductName(), it.getSpec(), it.getQty());
        }
    }

    /**
     * 级联删除：先删明细，再删头（同事务）。
     */
    @Transactional
    public void deleteWithItems(Long id) {
        if (id == null) {
            return;
        }
        outboundItemMapper.delete(new LambdaQueryWrapper<OutboundItem>().eq(OutboundItem::getOutboundId, id));
        removeById(id);
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
            single.setProductName(req.getProductName());
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

    private String nextDocNo() {
        synchronized (DOCNO_LOCK) {
            List<Outbound> todays = list(new LambdaQueryWrapper<Outbound>()
                    .likeRight(Outbound::getOutboundNo, PREFIX + today())
                    .select(Outbound::getOutboundNo));
            List<String> nos = todays.stream().map(Outbound::getOutboundNo).collect(Collectors.toList());
            long maxSeq = DocNoGenerator.maxSeqOf(nos, PREFIX.length());
            return DocNoGenerator.generate(PREFIX, maxSeq);
        }
    }

    private static String today() {
        return LocalDate.now().format(DATE_FMT);
    }

    private Page<Outbound> buildPage(PageQuery query) {
        int p = query.getPage() == null || query.getPage() < 1 ? 1 : query.getPage();
        int s = query.getSize() == null || query.getSize() < 1 ? 10 : query.getSize();
        s = Math.min(s, 200);
        return new Page<>(p, s);
    }
}
