package com.ccb.techfin.model.external;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 资料批量新增请求参数。
 *
 * @author qiuhaoquan
 * @since 2026-07-23
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocBatchAddItem {

    private String attId;
    private Long dirId;
    private String docName;
    private Long docSize;
    private Long docTypeId;
    private String extraInfo;
    private Long projectId;
    private String reportDate;
}
