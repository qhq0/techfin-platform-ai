package com.ccb.techfin.service.sxd.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Data
@Configuration
@ConfigurationProperties(prefix = "dib")
public class ApiProperties {

    private String attachmentUploadUrl;
    private String docBatchAddUrl;
    private String docDetailUrl;
    private String docQueryDataUrl;
    private String docExportDataUrl;
    private String docTableExtractStateUrl;
    private String docDeleteUrl;
    private Long sxdProjectId;
    private Long dirId = 0L;
    private Map<String, Long> docType;
    private String c1ApiKey = "";
}
