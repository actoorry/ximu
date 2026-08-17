package com.by.ximu.inventory.module.batch;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDate;

/**
 * 批号创建请求白名单 DTO。
 *
 * <p>与 update 白名单对齐：屏蔽 {@code id/version/createdAt/updatedAt}。
 */
@Data
public class BatchCreateRequest {

    @NotBlank(message = "批号不能为空")
    private String batchNo;

    private String productName;

    @JsonFormat(pattern = "yyyy-MM-dd", timezone = "GMT+8")
    private LocalDate createDate;

    private String creator;

    private String remark;
}
