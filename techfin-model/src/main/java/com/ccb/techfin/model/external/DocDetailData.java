package com.ccb.techfin.model.external;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * 资料详情接口（doc/get/{doc_id}）返回的 data 结构。
 * extractState 取值：U|I|F|Y — 待执行|执行中|执行失败|执行完成
 *
 * @author qiuhaoquan
 * @since 2026-07-23
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DocDetailData {

    /** 文档名称 */
    private String docName;

    /** 提取状态：U-待执行 / I-执行中 / F-执行失败 / Y-执行完成 */
    private String extractState;
}
