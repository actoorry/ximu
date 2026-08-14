package com.by.ximu.common;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * 分页查询基类。
 *
 * <p>{@code page} 从 1 开始，{@code size} 默认 10，{@code keyword} 为模糊搜索关键字。
 */
@Data
public class PageQuery {

    /** 页码，从 1 开始 */
    private Integer page = 1;

    /** 每页条数，默认 10 */
    private Integer size = 10;

    /** 模糊搜索关键字 */
    private String keyword;

    /**
     * 将 MyBatis-Plus 分页结果转换为前端契约结构。
     *
     * @param pageResult MyBatis-Plus 分页结果
     * @return {@code { "list": [...], "total": N }}
     */
    public Map<String, Object> toPageMap(IPage<?> pageResult) {
        Map<String, Object> map = new HashMap<>();
        map.put("list", pageResult.getRecords());
        map.put("total", pageResult.getTotal());
        return map;
    }
}
