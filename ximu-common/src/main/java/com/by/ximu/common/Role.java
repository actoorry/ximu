package com.by.ximu.common;

/**
 * 内置角色（与网关 JWT 的 roles 声明、X-User-Roles 头取值一致，统一大写枚举名）。
 */
public enum Role {
    /** 系统管理员：全部权限，可绕过职责分离 */
    ADMIN,
    /** 制单员：制单、编辑/删除本人 CREATED 单据 */
    CREATOR,
    /** 批准人：单据批准（CREATED→APPROVED） */
    APPROVER,
    /** 审核人/保管员：入库/盘点审核、调拨完成，兼库存/批号/安全库存维护 */
    CHECKER,
    /** 只读：仅查询 */
    VIEWER
}
