package com.ccb.techfin.model.external;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * 文档批量新增（doc/batch/add）返回的 data 结构。
 *
 * @author qiuhaoquan
 * @since 2026-07-23
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DocBatchAddData {

    private List<String> invalidDocNames;
    private List<DocInfo> docList;
}
