package com.by.ximu.common;

import lombok.Data;

/**
 * 统一返回结构。
 *
 * <p>前后端契约：{@code { "code": 0, "message": "ok", "data": {} }}，code = 0 表示成功，非 0 表示失败。
 */
@Data
public class Result<T> {

    /** 状态码，0 = 成功 */
    private Integer code;

    /** 提示信息 */
    private String message;

    /** 业务数据 */
    private T data;

    public static <T> Result<T> ok() {
        Result<T> result = new Result<>();
        result.setCode(0);
        result.setMessage("ok");
        return result;
    }

    public static <T> Result<T> ok(T data) {
        Result<T> result = ok();
        result.setData(data);
        return result;
    }

    public static <T> Result<T> error(String message) {
        return error(500, message);
    }

    public static <T> Result<T> error(int code, String message) {
        Result<T> result = new Result<>();
        result.setCode(code);
        result.setMessage(message);
        return result;
    }
}
