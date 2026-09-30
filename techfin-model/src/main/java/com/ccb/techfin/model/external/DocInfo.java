package com.ccb.techfin.model.external;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * 文档批量新增返回的文档信息。
 *
 * @author qiuhaoquan
 * @since 2026-07-23
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DocInfo {

    private String id;
    private String docName;
    private String projectId;
    private String attId;
}
