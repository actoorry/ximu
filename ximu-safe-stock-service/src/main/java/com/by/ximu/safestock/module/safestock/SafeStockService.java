package com.by.ximu.safestock.module.safestock;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.by.ximu.common.DimsNormalizer;
import com.by.ximu.common.OperatorContext;
import com.by.ximu.common.PageQuery;
import com.by.ximu.common.web.audit.OperationLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * 安全库存服务：分页查询（支持商品名/物料筛选 + keyword 模糊）与写操作
 * （create/update/delete：幂等回查、撞键处理、白名单字段映射、条件删除+乐观锁、审计、角色校验）。
 */
@Service
@RequiredArgsConstructor
public class SafeStockService extends ServiceImpl<SafeStockMapper, SafeStock> {

    private final OperationLogService operationLogService;

    public Map<String, Object> page(PageQuery query, String productName, String material) {
        LambdaQueryWrapper<SafeStock> wrapper = new LambdaQueryWrapper<>();
        // R2-P2-13：查询侧筛选参数过 DimsNormalizer，与写入侧同一套归一化——
        // 否则「查询 A　铜」（全角空格）匹配不到写入时归一化的「A 铜」，用户查不到刚创建的数据
        if (StringUtils.hasText(productName)) {
            wrapper.like(SafeStock::getProductName, DimsNormalizer.normalize(productName));
        }
        if (StringUtils.hasText(material)) {
            wrapper.like(SafeStock::getMaterial, DimsNormalizer.normalize(material));
        }
        if (StringUtils.hasText(query.getKeyword())) {
            String kw = query.getKeyword();
            wrapper.and(w -> w.like(SafeStock::getProductName, kw)
                    .or().like(SafeStock::getMaterial, kw));
        }
        wrapper.orderByDesc(SafeStock::getCreatedAt);
        return query.toPageMap(baseMapper.selectPage(query.buildPage(), wrapper));
    }

    @Transactional
    public SafeStock create(SafeStockCreateRequest req) {
        // 幂等：requestId 非空时 trim 后按「requestId + 当前操作人」查重（R2-P2-11：trim 防空白前缀/尾缀致幂等失效；V8 复合唯一键兜底）
        String requestId = StringUtils.hasText(req.getRequestId()) ? req.getRequestId().trim() : null;
        if (StringUtils.hasText(requestId)) {
            SafeStock existed = findByIdempotent(requestId);
            if (existed != null) {
                return existed;
            }
        }
        // 白名单赋值：屏蔽 id/version/createdAt/updatedAt；品名/物料与库存五维同一套归一化（P1-4）
        SafeStock entity = new SafeStock();
        entity.setProductName(DimsNormalizer.normalize(req.getProductName()));
        entity.setMaterial(DimsNormalizer.normalize(req.getMaterial()));
        entity.setOrgId(req.getOrgId());
        entity.setServiceLevel(req.getServiceLevel());
        entity.setZValue(req.getZValue());
        entity.setReplenishCycle(req.getReplenishCycle());
        entity.setEconomicQty(req.getEconomicQty());
        entity.setOrderPointQty(req.getOrderPointQty());
        entity.setMaxQty(req.getMaxQty());
        entity.setSafeStock(req.getSafeStock());
        entity.setRequestId(requestId);
        entity.setCreatedBy(OperatorContext.getOperatorId());
        try {
            save(entity);
        } catch (DuplicateKeyException e) {
            // 并发同 requestId 双插：幂等返回已有；否则是 uk_safe_stock_dims 维度重复，明确报 400
            SafeStock existed = StringUtils.hasText(requestId) ? findByIdempotent(requestId) : null;
            if (existed != null) {
                return existed;
            }
            throw new IllegalArgumentException("该组织+品名+物料的安全库存配置已存在，请勿重复创建");
        }
        // R2-P2-12：审计记录生效值（归一化后的 entity）而非原始请求体，detail 可还原字段实际落库值
        operationLogService.recordInTx("safe-stock", "CREATE", entity.getId(), entity.getProductName(), OperatorContext.getOperatorName(), entity);
        return entity;
    }

    @Transactional
    public void update(Long id, SafeStock entity) {
        SafeStock existed = getById(id);
        if (existed == null) {
            throw new IllegalArgumentException("安全库存配置不存在: " + id);
        }
        // 白名单 + 部分更新（P1-5）：字段为 null 表示保持原值，避免漏传字段被静默清空；
        // id/version/createdAt/updatedAt 不可经此修改
        // R2-P2-10：空串视为 null 保持原值——空串会绕过实体 @NotBlank（仅非空串才校验）被 normalize 成 "" 落库，静默清空品名
        if (StringUtils.hasText(entity.getProductName())) {
            existed.setProductName(DimsNormalizer.normalize(entity.getProductName()));
        }
        if (StringUtils.hasText(entity.getMaterial())) {
            existed.setMaterial(DimsNormalizer.normalize(entity.getMaterial()));
        }
        if (entity.getOrgId() != null) {
            existed.setOrgId(entity.getOrgId());
        }
        if (entity.getServiceLevel() != null) {
            existed.setServiceLevel(entity.getServiceLevel());
        }
        if (entity.getZValue() != null) {
            existed.setZValue(entity.getZValue());
        }
        if (entity.getReplenishCycle() != null) {
            existed.setReplenishCycle(entity.getReplenishCycle());
        }
        if (entity.getEconomicQty() != null) {
            existed.setEconomicQty(entity.getEconomicQty());
        }
        if (entity.getOrderPointQty() != null) {
            existed.setOrderPointQty(entity.getOrderPointQty());
        }
        if (entity.getMaxQty() != null) {
            existed.setMaxQty(entity.getMaxQty());
        }
        if (entity.getSafeStock() != null) {
            existed.setSafeStock(entity.getSafeStock());
        }
        try {
            if (!updateById(existed)) {
                throw new IllegalStateException("并发冲突，请刷新后重试");
            }
        } catch (DuplicateKeyException e) {
            // 改成已存在的维度组合时撞 uk_safe_stock_dims，转成明确的业务报错而非裸 500
            throw new IllegalArgumentException("该组织+品名+物料的安全库存配置已存在，无法改为此维度组合");
        }
        // R2-P2-12：审计记录更新后的生效值（existed）而非请求体，detail 可还原字段实际变化
        operationLogService.recordInTx("safe-stock", "UPDATE", id, existed.getProductName(), OperatorContext.getOperatorName(), existed);
    }

    @Transactional
    public void delete(Long id, Integer requestedVersion) {
        SafeStock existed = getById(id);
        if (existed == null) {
            throw new IllegalArgumentException("安全库存配置不存在: " + id);
        }
        // R2-P2-14：条件删除带 version——陈旧上下文（前端持有的 version 已过期）删除不再静默成功，
        // 影响 0 行即抛并发冲突；请求未带 version（兼容旧客户端）时以刚查到的当前值兜底
        Integer expectedVersion = requestedVersion != null ? requestedVersion : existed.getVersion();
        boolean removed = remove(new LambdaQueryWrapper<SafeStock>()
                .eq(SafeStock::getId, id)
                .eq(SafeStock::getVersion, expectedVersion));
        if (!removed) {
            throw new IllegalStateException("并发冲突，配置已被他人修改，请刷新后重试");
        }
        operationLogService.recordInTx("safe-stock", "DELETE", id, existed.getProductName(), OperatorContext.getOperatorName(), null);
    }

    /** 幂等回查：requestId + 当前操作人（操作人缺失时退化为仅 requestId，与建单侧策略一致） */
    private SafeStock findByIdempotent(String requestId) {
        Long operatorId = OperatorContext.getOperatorId();
        return getOne(new LambdaQueryWrapper<SafeStock>()
                .eq(SafeStock::getRequestId, requestId)
                .eq(operatorId != null, SafeStock::getCreatedBy, operatorId), false);
    }
}
