package com.by.ximu.inventory.module.log;

import com.by.ximu.common.PageQuery;
import com.by.ximu.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 操作日志 Controller（只读列表查询，日志由各业务模块内部写入）。
 */
@RestController
@RequestMapping("/api/inventory/log")
@RequiredArgsConstructor
public class OperationLogController {

    private final OperationLogService operationLogService;

    @GetMapping
    public Result<Map<String, Object>> list(PageQuery pageQuery,
                                            @RequestParam(required = false) String module,
                                            @RequestParam(required = false) String operation,
                                            @RequestParam(required = false) Long targetId) {
        return Result.ok(operationLogService.page(pageQuery, module, operation, targetId));
    }
}
