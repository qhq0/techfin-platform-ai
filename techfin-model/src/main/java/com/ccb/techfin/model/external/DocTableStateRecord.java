package com.ccb.techfin.model.external;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * 资料表格提取状态接口（docTableExtractState/{doc_id}）返回的 data 数组元素。
 * 每个元素表示一个提取表格的状态。
 *
 * @author qiuhaoquan
 * @since 2026-07-23
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DocTableStateRecord {

    /** 表名，如 dib_fin_balance */
    private String tableName;

    /** 表中文名，如 合并资产负债表 */
    private String tableCnName;

    /** 提取状态：U|I|F|Y */
    private String extractState;

    /** 审核状态 */
    private String auditState;

    /** 页码 */
    private String pageNum;
}
