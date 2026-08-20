package com.by.ximu.common;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
     * 构造 MyBatis-Plus 分页对象：页码从 1 起、size 默认 10 上限 200。
     *
     * <p>各 Service 私有 buildPage 的归一逻辑统一上收（原全库 8 处重复）。
     */
    public <T> Page<T> buildPage() {
        int p = page == null || page < 1 ? 1 : page;
        int s = size == null || size < 1 ? 10 : size;
        s = Math.min(s, 200);
        return new Page<>(p, s);
    }

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
