package com.by.ximu.common;

import java.util.List;

/**
 * 当前操作人身份（由网关注入的 X-User-* 头解析而来，服务端可信上下文）。
 *
 * @param id    操作人 ID
 * @param name  操作人名称
 * @param roles 操作人角色列表
 */
public record Operator(Long id, String name, List<String> roles) {
}
