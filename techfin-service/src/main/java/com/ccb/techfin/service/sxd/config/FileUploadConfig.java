package com.ccb.techfin.service.sxd.config;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.Map;

/**
 * 上传文件的大小上限与扩展名白名单（前缀 {@code file.upload}）。
 *
 * <p>{@code allowedExtensions} 刻意<b>不设字段默认值</b>：扩展名白名单是安全边界，
 * 在代码里给一份兜底白名单只会掩盖漏配。改用 {@code @NotEmpty} 在<b>绑定阶段</b>拦下 ——
 * 漏配即启动失败，而不是等到请求期才在校验器里抛 NPE。
 */
@Data
@Validated
@Configuration
@ConfigurationProperties(prefix = "file.upload")
public class FileUploadConfig {

    private long maxFileSize = 50 * 1024 * 1024L;

    @NotEmpty(message = "必须配置 file.upload.allowed-extensions（至少一个文档类型的一组扩展名）")
    private Map<String, List<String>> allowedExtensions;
}
