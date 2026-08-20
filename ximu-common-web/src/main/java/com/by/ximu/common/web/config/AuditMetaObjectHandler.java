package com.by.ximu.common.web.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.by.ximu.common.Operator;
import com.by.ximu.common.OperatorContext;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

/**
 * 审计字段自动填充（P2-5）：业务表 {@code updated_by}（UPDATE 时）与审计表
 * {@code operation_log.operator_id}（INSERT 时）从可信登录上下文取值，
 * 调用方无需逐点手写——审计链完整性不再依赖每个写点的自觉。
 *
 * <p>strict 填充语义：仅填充实体声明了 {@code @TableField(fill=...)} 且当前值为 null 的字段，
 * 不覆盖显式赋值；无登录上下文（定时任务/系统初始化等非 /api 路径）时不填充，保持 NULL。
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
