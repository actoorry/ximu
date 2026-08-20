package com.by.ximu.safestock.module.safestock;

import lombok.Data;

/**
 * 安全库存配置删除请求（R2-P2-14：带 version 条件删除，防陈旧上下文删除静默成功）。
 *
 * <p>仅接收 version；id 走路径参数。body 可选（兼容旧客户端不带 body 时，
 * 由 Controller 以刚查询到的当前 version 兜底——此时等价于无版本校验，仍受「行不存在即冲突」兜底）。
 */
@Data
public class SafeStockDeleteRequest {

    /** 乐观锁版本号：与库内 version 不一致时删除影响 0 行，抛并发冲突（可空） */
    private Integer version;
}
