package com.by.ximu.inventory.module.check;

import lombok.Data;

/**
 * 盘点单编辑请求（白名单 DTO，防过度绑定）。
 *
 * <p>仅暴露业务可编辑字段；{@code id/status/version/createdBy/createdAt/updatedAt}
 * 不在此声明，即使前端传入也会被忽略。
 * <p>部分更新语义：字段为 null 表示保持原值不变。
 */
@Data
public class CheckUpdateRequest {

    /** 关联批号 */
    private String batchNo;
}
