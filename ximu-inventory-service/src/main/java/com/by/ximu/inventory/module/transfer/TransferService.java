package com.by.ximu.inventory.module.transfer;

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
import com.by.ximu.inventory.util.DocNoSequenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
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

    private final TransferItemMapper transferItemMapper;
    private final OperationLogService operationLogService;
    private final DocNoSequenceService docNoSequenceService;

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
            // R2-P2-26：keyword 走 LIKE '%kw%'（前导通配）无法命中 V7 的 status/created_at 索引，
            // 数据量大时全表扫——当前单量级可接受；若成瓶颈改前缀索引/全文检索或对账侧拉数据
            String kw = query.getKeyword();
            wrapper.and(w -> w.like(Transfer::getTransferNo, kw)
                    .or().like(Transfer::getBatchNo, kw));
        }
        wrapper.orderByDesc(Transfer::getCreatedAt);
        Page<Transfer> p = baseMapper.selectPage(query.buildPage(), wrapper);
        Page<TransferDetailVO> voPage = new Page<>(p.getCurrent(), p.getSize(), p.getTotal());
        voPage.setRecords(toVoList(p.getRecords()));
        return query.toPageMap(voPage);
    }

    /**
     * 创建调拨单（头 + 明细 + 单号自动生成）。
     *
     * <p>单号：前端不传则按 {@code TR + yyyyMMdd + 3位序号} 自动生成；传了则校验唯一。
     * <p>兼容：{@code items} 为空但传了旧单品字段（{@code productName/qty/targetLocation}）时自动转成一条明细。
     */
    @Transactional
    public TransferDetailVO create(TransferCreateRequest req) {
        // 幂等：requestId 非空时按「requestId + 当前操作人」查重（P1-7：复合幂等键）
        String requestId = req.getRequestId();
        if (StringUtils.hasText(requestId)) {
            Transfer existed = findByIdempotent(requestId);
            if (existed != null) {
                return toVo(existed, listItems(existed.getId()));
            }
        }
        String docNo = req.getTransferNo();
        if (!StringUtils.hasText(docNo)) {
            docNo = nextDocNo();
        } else if (count(new LambdaQueryWrapper<Transfer>().eq(Transfer::getTransferNo, docNo)) > 0) {
            throw new IllegalArgumentException("调拨单号已存在: " + docNo);
        }
        List<TransferItem> items = normalizeItems(req);
        // 明细至少一行（R2-P1-2）：normalizeItems 已把兼容单品转成明细，此处 items 为空即二者皆空，拒绝建空单
        ItemValidators.requireNonEmpty(items, "调拨");
        Transfer head = new Transfer();
        head.setTransferNo(docNo);
        head.setBatchNo(req.getBatchNo());
        head.setStatus(DocStatus.CREATED.name());
        head.setCreatedBy(OperatorContext.getOperatorId());
        head.setRequestId(requestId);
        try {
            save(head);
        } catch (DuplicateKeyException e) {
            // 并发下同 requestId 同时插入，唯一索引兜底：R2-P2-23 撞键后回查 → sleep 退避 → 再回查，
            // 命中返回已存在单据，仍查不到才按并发冲突拒绝（不再裸抛 DuplicateKey 落 400 固定文案）
            Transfer existed = RetrySupport.retryIdempotent(() -> findByIdempotent(requestId));
            return toVo(existed, listItems(existed.getId()));
        }
        if (items != null) {
            for (TransferItem it : items) {
                it.setId(null);
                it.setTransferId(head.getId());
                transferItemMapper.insert(it);
            }
        }
        operationLogService.recordInTx("transfer", "CREATE", head.getId(), head.getTransferNo(), OperatorContext.getOperatorName(), req);
        return toVo(head, items);
    }

    /**
     * 批准：CREATED → APPROVED。
     */
    @Transactional
    public void approve(Long id) {
        Transfer transfer = DocGuard.requireExists(getById(id), "调拨单", id);
        Auths.requireRole(Role.APPROVER, Role.ADMIN);
        Auths.requireNotSelfOrAdmin(transfer.getCreatedBy());
        DocGuard.requireTransitionStatus(transfer.getStatus(), DocStatus.CREATED.name(), "批准");
        transfer.setStatus(DocStatus.APPROVED.name());
        DocGuard.requireUpdateSucceeded(updateById(transfer));
        operationLogService.recordInTx("transfer", "APPROVE", id, transfer.getTransferNo(), OperatorContext.getOperatorName(), null);
    }

    /**
     * 完成：APPROVED → COMPLETED。
     *
     * <p>调拨为库位间转移，总量不变，不联动库存数量。
     */
    @Transactional
    public void complete(Long id) {
        Transfer transfer = DocGuard.requireExists(getById(id), "调拨单", id);
        Auths.requireRole(Role.CHECKER, Role.ADMIN);
        Auths.requireNotSelfOrAdmin(transfer.getCreatedBy());
        DocGuard.requireTransitionStatus(transfer.getStatus(), DocStatus.APPROVED.name(), "完成");
        transfer.setStatus(DocStatus.COMPLETED.name());
        DocGuard.requireUpdateSucceeded(updateById(transfer));
        operationLogService.recordInTx("transfer", "COMPLETE", id, transfer.getTransferNo(), OperatorContext.getOperatorName(), null);
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
        Transfer head = getById(id);
        if (head == null) {
            return;
        }
        DocGuard.requireTransitionStatus(head.getStatus(), DocStatus.CREATED.name(), "删除");
        Auths.requireCreatorOrAdmin(head.getCreatedBy());
        int deleted = baseMapper.delete(new LambdaQueryWrapper<Transfer>()
                .eq(Transfer::getId, id)
                .eq(Transfer::getStatus, DocStatus.CREATED.name()));
        if (deleted == 0) {
            throw new IllegalStateException("单据状态已变化或已被他人操作，删除失败，请刷新重试");
        }
        transferItemMapper.delete(new LambdaQueryWrapper<TransferItem>().eq(TransferItem::getTransferId, id));
        operationLogService.recordInTx("transfer", "DELETE", id, head.getTransferNo(), OperatorContext.getOperatorName(), null);
    }

    /**
     * 编辑调拨单头（白名单字段）：仅本人 CREATED 单据可编辑（ADMIN 不限）。
     *
     * <p>请求经 {@link TransferUpdateRequest} 白名单绑定，{@code id/status/version/createdBy/时间戳} 不可经此修改；
     * 部分更新语义：DTO 字段为 null 表示保持原值。
     * <p>编辑与审计同事务；乐观锁冲突时抛异常提示刷新重试。
     */
    @Transactional
    public void updateHead(Long id, TransferUpdateRequest req) {
        Transfer existed = DocGuard.requireExists(getById(id), "调拨单", id);
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
        operationLogService.recordInTx("transfer", "UPDATE", id, existed.getTransferNo(), OperatorContext.getOperatorName(), req);
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

    /** 幂等回查：requestId + 当前操作人（P1-7 复合幂等键；操作人缺失时退化为仅 requestId，与历史行为一致） */
    private Transfer findByIdempotent(String requestId) {
        Long operatorId = OperatorContext.getOperatorId();
        return getOne(new LambdaQueryWrapper<Transfer>()
                .eq(Transfer::getRequestId, requestId)
                .eq(operatorId != null, Transfer::getCreatedBy, operatorId), false);
    }

    /**
     * 兼容旧单品字段：items 为空但传了旧单品字段时，转成一条明细。
     *
     * <p>出口统一做五维归一化（P1-4）：品名/物料/规格/等级 trim + 全角转半角后落库，
     * 保持与库存账本同一套归一规则（调拨明细虽不联动库存，但品名维度仍是后续对账依据）。
     */
    private List<TransferItem> normalizeItems(TransferCreateRequest req) {
        List<TransferItem> items = req.getItems();
        // P2-19：hasLegacy 收窄为「品名非空」——原「qty 或 targetLocation 非空」过宽，仅传 qty 会构造出
        // productName=null 的缺维度明细；只传数量/库位而无品名时按「无兼容字段」处理，由 requireNonEmpty 统一拒绝
        boolean hasLegacy = StringUtils.hasText(req.getProductName());
        if ((items == null || items.isEmpty()) && hasLegacy) {
            if (req.getOrgId() == null) {
                throw new IllegalArgumentException("组织(orgId)不能为空");
            }
            TransferItem single = new TransferItem();
            single.setOrgId(req.getOrgId());
            single.setProductName(req.getProductName());
            single.setGrade(req.getGrade());
            single.setQty(req.getQty());
            single.setTargetLocation(req.getTargetLocation());
            items = new ArrayList<>(List.of(single));
        }
        if (items != null) {
            for (TransferItem it : items) {
                it.setProductName(DimsNormalizer.normalize(it.getProductName()));
                it.setMaterial(DimsNormalizer.normalize(it.getMaterial()));
                it.setSpec(DimsNormalizer.normalize(it.getSpec()));
                it.setGrade(DimsNormalizer.normalize(it.getGrade()));
                // R2-P1-1：调拨数量必填且 >0（null/0 会构造无意义的调拨行）
                ItemValidators.requireQtyPositive(it.getQty(), "调拨明细数量(qty)");
                // P2-19：目标库位必填（调拨无目标库位即无意义）
                ItemValidators.requireHasText(it.getTargetLocation(), "目标库位(targetLocation)");
            }
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
        vo.setTotalQty(QuantitySupport.sumQty(items, TransferItem::getQty));
        return vo;
    }

    /** 生成当天调拨单号（DB 原子取号，多实例安全） */
    private String nextDocNo() {
        return docNoSequenceService.next(PREFIX);
    }

}
