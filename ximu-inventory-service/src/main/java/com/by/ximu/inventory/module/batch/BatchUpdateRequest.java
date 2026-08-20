package com.by.ximu.inventory.module.batch;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

/**
 * 批号更新请求白名单 DTO（R2-P2-15）。
 *
 * <p>部分更新语义：字段为 {@code null} 或空白串表示保持原值，避免漏传字段被静默清空；
 * 禁止直接接收 {@code Batch} 实体——原始实体全量覆盖，传 {@code {"id":5}} 会把其余字段清空，
 * batchNo 被清空后唯一索引失效。屏蔽 {@code id/version/createdAt/updatedAt}。
 */
@Data
public class BatchUpdateRequest {

    @Size(max = 64, message = "批号长度不能超过 64")
    private String batchNo;

    private String productName;

    @JsonFormat(pattern = "yyyy-MM-dd", timezone = "GMT+8")
    private LocalDate createDate;

    private String creator;

    private String remark;
}
