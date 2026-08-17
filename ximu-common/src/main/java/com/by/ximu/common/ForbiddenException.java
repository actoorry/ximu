package com.by.ximu.common;

/**
 * 越权异常：权限不足 / 职责分离冲突，由各服务全局异常处理器映射为 403。
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
