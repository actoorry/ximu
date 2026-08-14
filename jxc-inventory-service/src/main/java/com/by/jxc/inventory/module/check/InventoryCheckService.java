package com.by.jxc.inventory.module.check;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.by.jxc.common.PageQuery;
import com.by.jxc.inventory.module.stock.StockOperationService;
import com.by.jxc.inventory.util.DocNoGenerator;
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
 * 盘点服务：分页查询 + 单据创建（头 + 明细 + 单号自动生成） + 状态机流转（CREATED → APPROVED → CHECKED）。
 *
 * <p>流转前置校验：仅 CREATED 可批准；仅 APPROVED 可审核；非法迁移抛 {@link IllegalStateException}。
 * <p>审核（CHECKED）后按明细逐行联动校正 {@code inventory_stock}（{@code actual_qty = 明细实盘值}）。
 */
@Service
@RequiredArgsConstructor
public class InventoryCheckService extends ServiceImpl<InventoryCheckMapper, InventoryCheck> {

    private static final String PREFIX = "CK";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final Object DOCNO_LOCK = new Object();

    private final CheckItemMapper checkItemMapper;
    private final StockOperationService stockOperationService;

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
            String kw = query.getKeyword();
            wrapper.and(w -> w.like(InventoryCheck::getCheckNo, kw)
                    .or().like(InventoryCheck::getBatchNo, kw));
        }
        wrapper.orderByDesc(InventoryCheck::getCreatedAt);
        Page<InventoryCheck> p = baseMapper.selectPage(buildPage(query), wrapper);
        Map<String, Object> map = new HashMap<>();
        map.put("list", toVoList(p.getRecords()));
        map.put("total", p.getTotal());
        return map;
    }

    /**
     * 创建盘点单（头 + 明细 + 单号自动生成）。
     *
     * <p>单号：前端不传则按 {@code CK + yyyyMMdd + 3位序号} 自动生成；传了则校验唯一。
     * <p>兼容：{@code items} 为空但传了旧单品字段（{@code actualQty}）时自动转成一条明细。
     */
    @Transactional
    public CheckDetailVO create(CheckCreateRequest req) {
        String docNo = req.getCheckNo();
        if (!StringUtils.hasText(docNo)) {
            docNo = nextDocNo();
        } else if (count(new LambdaQueryWrapper<InventoryCheck>().eq(InventoryCheck::getCheckNo, docNo)) > 0) {
            throw new IllegalArgumentException("盘点单号已存在: " + docNo);
        }
        List<CheckItem> items = normalizeItems(req);
        InventoryCheck head = new InventoryCheck();
        head.setCheckNo(docNo);
        head.setBatchNo(req.getBatchNo());
        head.setStatus("CREATED");
        save(head);
        if (items != null) {
            for (CheckItem it : items) {
                it.setId(null);
                it.setCheckId(head.getId());
                checkItemMapper.insert(it);
            }
        }
        return toVo(head, items);
    }

    /**
     * 批准：CREATED → APPROVED。
     */
    @Transactional
    public void approve(Long id) {
        InventoryCheck check = getById(id);
        if (check == null) {
            throw new IllegalArgumentException("盘点单不存在: " + id);
        }
        if (!"CREATED".equals(check.getStatus())) {
            throw new IllegalStateException("当前状态[" + check.getStatus() + "]不允许批准，仅 CREATED 状态可批准");
        }
        check.setStatus("APPROVED");
        updateById(check);
    }

    /**
     * 审核：APPROVED → CHECKED；按明细逐行联动校正库存（{@code actual_qty = 明细实盘值}）。
     */
    @Transactional
    public void check(Long id) {
        InventoryCheck check = getById(id);
        if (check == null) {
            throw new IllegalArgumentException("盘点单不存在: " + id);
        }
        if (!"APPROVED".equals(check.getStatus())) {
            throw new IllegalStateException("当前状态[" + check.getStatus() + "]不允许审核，仅 APPROVED 状态可审核");
        }
        check.setStatus("CHECKED");
        updateById(check);
        // 库存联动：按明细逐行校正到实盘数量
        for (CheckItem it : listItems(id)) {
            stockOperationService.adjustStock(it.getProductName(), it.getSpec(), it.getActualQty());
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
        checkItemMapper.delete(new LambdaQueryWrapper<CheckItem>().eq(CheckItem::getCheckId, id));
        removeById(id);
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

    /** 兼容旧单品字段：items 为空且传了 actualQty 时，转成一条明细 */
    private List<CheckItem> normalizeItems(CheckCreateRequest req) {
        List<CheckItem> items = req.getItems();
        if ((items == null || items.isEmpty()) && req.getActualQty() != null) {
            CheckItem single = new CheckItem();
            single.setProductName(req.getProductName());
            single.setSpec(req.getSpec());
            single.setActualQty(req.getActualQty());
            return new ArrayList<>(List.of(single));
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
        vo.setTotalQty(sumActualQty(items));
        return vo;
    }

    /** 盘点汇总取实盘数量 actual_qty */
    private BigDecimal sumActualQty(List<CheckItem> items) {
        if (items == null || items.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return items.stream()
                .map(i -> i.getActualQty() == null ? BigDecimal.ZERO : i.getActualQty())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private String nextDocNo() {
        synchronized (DOCNO_LOCK) {
            List<InventoryCheck> todays = list(new LambdaQueryWrapper<InventoryCheck>()
                    .likeRight(InventoryCheck::getCheckNo, PREFIX + today())
                    .select(InventoryCheck::getCheckNo));
            List<String> nos = todays.stream().map(InventoryCheck::getCheckNo).collect(Collectors.toList());
            long maxSeq = DocNoGenerator.maxSeqOf(nos, PREFIX.length());
            return DocNoGenerator.generate(PREFIX, maxSeq);
        }
    }

    private static String today() {
        return LocalDate.now().format(DATE_FMT);
    }

    private Page<InventoryCheck> buildPage(PageQuery query) {
        int p = query.getPage() == null || query.getPage() < 1 ? 1 : query.getPage();
        int s = query.getSize() == null || query.getSize() < 1 ? 10 : query.getSize();
        s = Math.min(s, 200);
        return new Page<>(p, s);
    }
}
