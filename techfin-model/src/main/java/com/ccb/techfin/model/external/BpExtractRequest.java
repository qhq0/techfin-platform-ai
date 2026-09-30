package com.ccb.techfin.model.external;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 商业计划书提取数据查询请求体。
 * POST /api/extract/open/doc/bpQueryData
 *
 * @author qiuhaoquan
 * @since 2026-07-23
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BpExtractRequest {

    /** 文档 ID（来自 sxd_doc.doc_id） */
    private Long docId;

    /** 表名，对应商业计划书提取的不同部分 */
    private String tableName;
}
