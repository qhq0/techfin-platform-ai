package com.ccb.techfin.service.external.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * 通用 {@link RestTemplate} 配置：建连与读取超时取同一个值，来源 {@code dib.file-timeout-seconds}（默认 60s）。
 *
 * <p><b>本类当前没有调用点。</b>DIB 的上传 / 下载 / 轻量接口都走 {@link RestClientConfig} 产出的三个
 * {@link org.springframework.web.client.RestClient}（各自独立连接池 + 调过的超时 + {@code c1-api-key} 拦截器）。
 * <b>不要拿它发 DIB 请求</b>：这里的池参数全是 HC5 默认值（等池超时 <b>3 分钟</b>，不是 3 秒），也没有鉴权拦截器。
 * 新增外部 HTTP 调用请加到 {@link RestClientConfig} 的某一档。
 */
@Configuration
public class RestTemplateConfig {

    private final ApiProperties apiProperties;

    public RestTemplateConfig(ApiProperties apiProperties) {
        this.apiProperties = apiProperties;
    }

    /** 通用 {@link RestTemplate}：connectTimeout 与 readTimeout 同为 {@code dib.file-timeout-seconds}。 */
    @Bean
    public RestTemplate restTemplate() {
        int timeout = apiProperties.getFileTimeoutSeconds() * 1000;
        return new RestTemplateBuilder()
                .connectTimeout(Duration.ofMillis(timeout))
                .readTimeout(Duration.ofMillis(timeout))
                .build();
    }
}
