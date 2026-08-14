package com.by.ximu.inventory.module.inbound;

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
 * 入库服务：分页查询 + 单据创建（头 + 明细 + 单号自动生成） + 状态机流转（CREATED → APPROVED → CHECKED）。
 *
 * <p>流转前置校验：仅 CREATED 可批准；仅 APPROVED 可审核；非法迁移抛 {@link IllegalStateException}。
 * <p>审核（CHECKED）后按明细逐行联动增加 {@code inventory_stock} 库存，与状态流转同事务，任一步失败整体回滚。
 */
@Service
@RequiredArgsConstructor
public class InboundService extends ServiceImpl<InboundMapper, Inbound> {

    private static final String PREFIX = "IN";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** 单号生成单机并发锁 */
    private static final Object DOCNO_LOCK = new Object();

    private final InboundItemMapper inboundItemMapper;
    private final StockOperationService stockOperationService;

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
        save(head);
        // 4. 保存明细
        if (items != null) {
            for (InboundItem it : items) {
                it.setId(null);
                it.setInboundId(head.getId());
                inboundItemMapper.insert(it);
            }
        }
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
        if (!"CREATED".equals(inbound.getStatus())) {
            throw new IllegalStateException("当前状态[" + inbound.getStatus() + "]不允许批准，仅 CREATED 状态可批准");
        }
        inbound.setStatus("APPROVED");
        inbound.setAuditLevel(auditLevel);
        updateById(inbound);
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
        if (!"APPROVED".equals(inbound.getStatus())) {
            throw new IllegalStateException("当前状态[" + inbound.getStatus() + "]不允许审核，仅 APPROVED 状态可审核");
        }
        inbound.setStatus("CHECKED");
        inbound.setChecker(checker);
        updateById(inbound);
        // 库存联动：按明细逐行增加库存（settle_qty 优先，无则用 qty）
        for (InboundItem it : listItems(id)) {
            BigDecimal qty = it.getSettleQty() != null ? it.getSettleQty() : it.getQty();
            stockOperationService.increaseStock(it.getProductName(), it.getSpec(), qty);
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
        inboundItemMapper.delete(new LambdaQueryWrapper<InboundItem>().eq(InboundItem::getInboundId, id));
        removeById(id);
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
            InboundItem single = new InboundItem();
            single.setProductName(req.getProductName());
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

    /** 单机并发安全生成当天单号（查询当天最大序号 + 1，唯一索引兜底） */
    private String nextDocNo() {
        synchronized (DOCNO_LOCK) {
            List<Inbound> todays = list(new LambdaQueryWrapper<Inbound>()
                    .likeRight(Inbound::getInboundNo, PREFIX + today())
                    .select(Inbound::getInboundNo));
            List<String> nos = todays.stream().map(Inbound::getInboundNo).collect(Collectors.toList());
            long maxSeq = DocNoGenerator.maxSeqOf(nos, PREFIX.length());
            return DocNoGenerator.generate(PREFIX, maxSeq);
        }
    }

    private static String today() {
        return LocalDate.now().format(DATE_FMT);
    }

    private Page<Inbound> buildPage(PageQuery query) {
        int p = query.getPage() == null || query.getPage() < 1 ? 1 : query.getPage();
        int s = query.getSize() == null || query.getSize() < 1 ? 10 : query.getSize();
        s = Math.min(s, 200);
        return new Page<>(p, s);
    }
}
