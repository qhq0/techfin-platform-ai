package com.ccb.techfin.service.sxd.config;

import com.ccb.techfin.common.util.UrlSecurityUtils;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Data
@Configuration
@ConfigurationProperties(prefix = "api")
public class ApiProperties {

    private String attachmentUploadUrl;
    private String docBatchAddUrl;
    private String docDetailUrl;
    private String docQueryDataUrl;
    private String docExportDataUrl;
    private String docTableExtractStateUrl;
    private String docDeleteUrl;
    private Long projectId;
    private Long dirId = 0L;
    private Map<String, Long> docType;
    private String defaultToken = "";

    /**
     * 配置加载后校验 defaultToken 不含 CR/LF 控制字符。
     * 该 token 会被写入对外请求的 c1-token 头，含控制字符可造成 HTTP 头注入，
     * 配置错误应在启动时立即暴露，而非运行期才失败。
     */
    @PostConstruct
    public void validateConfig() {
        UrlSecurityUtils.assertNoCrlf(defaultToken, "INVALID_TOKEN",
                "配置 api.default-token 含非法控制字符，请检查配置");
    }
}
