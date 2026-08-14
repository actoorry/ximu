package com.by.ximu.inventory.module.transfer;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.by.ximu.common.PageQuery;
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
 * 调拨服务：分页查询 + 单据创建（头 + 明细 + 单号自动生成） + 状态机流转（CREATED → APPROVED → COMPLETED）。
 *
 * <p>流转前置校验：仅 CREATED 可批准；仅 APPROVED 可完成；非法迁移抛 {@link IllegalStateException}。
 * <p>调拨是库位间转移，总量不变；完成（COMPLETED）不联动库存数量（stock 表无库位维度），仅记录明细。
 */
@Service
@RequiredArgsConstructor
public class TransferService extends ServiceImpl<TransferMapper, Transfer> {

    private static final String PREFIX = "TR";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final Object DOCNO_LOCK = new Object();

    private final TransferItemMapper transferItemMapper;

    /**
     * 分页查询（支持按状态/批号筛选 + keyword 模糊搜索调拨单号/批号）。
     * 返回头字段 + totalQty（明细数量汇总）。
     */
    public Map<String, Object> page(PageQuery query, String status, String batchNo) {
        LambdaQueryWrapper<Transfer> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(status)) {
            wrapper.eq(Transfer::getStatus, status);
        }
        if (StringUtils.hasText(batchNo)) {
            wrapper.eq(Transfer::getBatchNo, batchNo);
        }
        if (StringUtils.hasText(query.getKeyword())) {
            String kw = query.getKeyword();
            wrapper.and(w -> w.like(Transfer::getTransferNo, kw)
                    .or().like(Transfer::getBatchNo, kw));
        }
        wrapper.orderByDesc(Transfer::getCreatedAt);
        Page<Transfer> p = baseMapper.selectPage(buildPage(query), wrapper);
        Map<String, Object> map = new HashMap<>();
        map.put("list", toVoList(p.getRecords()));
        map.put("total", p.getTotal());
        return map;
    }

    /**
     * 创建调拨单（头 + 明细 + 单号自动生成）。
     *
     * <p>单号：前端不传则按 {@code TR + yyyyMMdd + 3位序号} 自动生成；传了则校验唯一。
     * <p>兼容：{@code items} 为空但传了旧单品字段（{@code productName/qty/targetLocation}）时自动转成一条明细。
     */
    @Transactional
    public TransferDetailVO create(TransferCreateRequest req) {
        String docNo = req.getTransferNo();
        if (!StringUtils.hasText(docNo)) {
            docNo = nextDocNo();
        } else if (count(new LambdaQueryWrapper<Transfer>().eq(Transfer::getTransferNo, docNo)) > 0) {
            throw new IllegalArgumentException("调拨单号已存在: " + docNo);
        }
        List<TransferItem> items = normalizeItems(req);
        Transfer head = new Transfer();
        head.setTransferNo(docNo);
        head.setBatchNo(req.getBatchNo());
        head.setStatus("CREATED");
        save(head);
        if (items != null) {
            for (TransferItem it : items) {
                it.setId(null);
                it.setTransferId(head.getId());
                transferItemMapper.insert(it);
            }
        }
        return toVo(head, items);
    }

    /**
     * 批准：CREATED → APPROVED。
     */
    @Transactional
    public void approve(Long id) {
        Transfer transfer = getById(id);
        if (transfer == null) {
            throw new IllegalArgumentException("调拨单不存在: " + id);
        }
        if (!"CREATED".equals(transfer.getStatus())) {
            throw new IllegalStateException("当前状态[" + transfer.getStatus() + "]不允许批准，仅 CREATED 状态可批准");
        }
        transfer.setStatus("APPROVED");
        updateById(transfer);
    }

    /**
     * 完成：APPROVED → COMPLETED。
     *
     * <p>调拨为库位间转移，总量不变，不联动库存数量。
     */
    @Transactional
    public void complete(Long id) {
        Transfer transfer = getById(id);
        if (transfer == null) {
            throw new IllegalArgumentException("调拨单不存在: " + id);
        }
        if (!"APPROVED".equals(transfer.getStatus())) {
            throw new IllegalStateException("当前状态[" + transfer.getStatus() + "]不允许完成，仅 APPROVED 状态可完成");
        }
        transfer.setStatus("COMPLETED");
        updateById(transfer);
    }

    /**
     * 级联删除：先删明细，再删头（同事务）。
     */
    @Transactional
    public void deleteWithItems(Long id) {
        if (id == null) {
            return;
        }
        transferItemMapper.delete(new LambdaQueryWrapper<TransferItem>().eq(TransferItem::getTransferId, id));
        removeById(id);
    }

    /** 查询头 + 明细，组装 VO（GET /{id}） */
    public TransferDetailVO getDetail(Long id) {
        Transfer head = getById(id);
        if (head == null) {
            return null;
        }
        return toVo(head, listItems(id));
    }

    /** 查询某头下的明细 */
    public List<TransferItem> listItems(Long transferId) {
        if (transferId == null) {
            return Collections.emptyList();
        }
        return transferItemMapper.selectList(
                new LambdaQueryWrapper<TransferItem>().eq(TransferItem::getTransferId, transferId));
    }

    // ===== 内部方法 =====

    private List<TransferItem> normalizeItems(TransferCreateRequest req) {
        List<TransferItem> items = req.getItems();
        boolean hasLegacy = StringUtils.hasText(req.getProductName())
                || req.getQty() != null
                || StringUtils.hasText(req.getTargetLocation());
        if ((items == null || items.isEmpty()) && hasLegacy) {
            TransferItem single = new TransferItem();
            single.setProductName(req.getProductName());
            single.setQty(req.getQty());
            single.setTargetLocation(req.getTargetLocation());
            return new ArrayList<>(List.of(single));
        }
        return items;
    }

    private List<TransferDetailVO> toVoList(List<Transfer> heads) {
        if (heads == null || heads.isEmpty()) {
            return Collections.emptyList();
        }
        Set<Long> ids = heads.stream().map(Transfer::getId).collect(Collectors.toSet());
        List<TransferItem> all = transferItemMapper.selectList(
                new LambdaQueryWrapper<TransferItem>().in(TransferItem::getTransferId, ids));
        Map<Long, List<TransferItem>> grouped = all.stream()
                .collect(Collectors.groupingBy(TransferItem::getTransferId));
        List<TransferDetailVO> vos = new ArrayList<>(heads.size());
        for (Transfer h : heads) {
            vos.add(toVo(h, grouped.getOrDefault(h.getId(), Collections.emptyList())));
        }
        return vos;
    }

    private TransferDetailVO toVo(Transfer head, List<TransferItem> items) {
        TransferDetailVO vo = new TransferDetailVO();
        vo.setId(head.getId());
        vo.setTransferNo(head.getTransferNo());
        vo.setBatchNo(head.getBatchNo());
        vo.setStatus(head.getStatus());
        vo.setCreatedAt(head.getCreatedAt());
        vo.setUpdatedAt(head.getUpdatedAt());
        vo.setVersion(head.getVersion());
        vo.setItems(items == null ? Collections.emptyList() : items);
        vo.setTotalQty(sumQty(items));
        return vo;
    }

    private BigDecimal sumQty(List<TransferItem> items) {
        if (items == null || items.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return items.stream()
                .map(i -> i.getQty() == null ? BigDecimal.ZERO : i.getQty())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private String nextDocNo() {
        synchronized (DOCNO_LOCK) {
            List<Transfer> todays = list(new LambdaQueryWrapper<Transfer>()
                    .likeRight(Transfer::getTransferNo, PREFIX + today())
                    .select(Transfer::getTransferNo));
            List<String> nos = todays.stream().map(Transfer::getTransferNo).collect(Collectors.toList());
            long maxSeq = DocNoGenerator.maxSeqOf(nos, PREFIX.length());
            return DocNoGenerator.generate(PREFIX, maxSeq);
        }
    }

    private static String today() {
        return LocalDate.now().format(DATE_FMT);
    }

    private Page<Transfer> buildPage(PageQuery query) {
        int p = query.getPage() == null || query.getPage() < 1 ? 1 : query.getPage();
        int s = query.getSize() == null || query.getSize() < 1 ? 10 : query.getSize();
        s = Math.min(s, 200);
        return new Page<>(p, s);
    }
}
