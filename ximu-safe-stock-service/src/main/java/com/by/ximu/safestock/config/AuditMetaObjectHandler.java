package com.by.ximu.safestock.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.by.ximu.common.Operator;
import com.by.ximu.common.OperatorContext;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

/**
 * 审计字段自动填充（P2-5）：{@code inventory_safe_stock.updated_by}（UPDATE 时）与
 * {@code operation_log.operator_id}（INSERT 时）从可信登录上下文取值。
 * 与 inventory-service 的同名类保持同构（两服务共写同库 operation_log 表，P2-9 契约约束）。
 *
 * <p>strict 填充语义：仅填充实体声明了 {@code @TableField(fill=...)} 且当前值为 null 的字段，
 * 不覆盖显式赋值；无登录上下文时不填充，保持 NULL。
 */
@Component
public class AuditMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        Long operatorId = currentOperatorId();
        if (operatorId != null) {
            strictInsertFill(metaObject, "operatorId", Long.class, operatorId);
        }
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        Long operatorId = currentOperatorId();
        if (operatorId != null) {
            strictUpdateFill(metaObject, "updatedBy", Long.class, operatorId);
        }
    }

    private Long currentOperatorId() {
        Operator operator = OperatorContext.get();
        return operator == null ? null : operator.id();
    }
}
