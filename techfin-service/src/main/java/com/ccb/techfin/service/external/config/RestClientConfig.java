package com.ccb.techfin.service.external.config;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * 调用外部平台（DIB 要素提取）专用的 RestClient，按「请求/响应体量」拆成三组 bean。
 *
 * <table border="1">
 *   <caption>三组 bean 的分工</caption>
 *   <tr><th>bean</th><th>用途</th><th>响应超时</th></tr>
 *   <tr>
 *     <td>{@link #FILE_HTTP_CLIENT} / {@link #FILE_REST_CLIENT}</td>
 *     <td>附件上传（multipart，单文件可达 50MB，经 {@code file.getBytes()} 整体进内存）</td>
 *     <td>{@code dib.file-timeout-seconds}（60s）</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #DOWNLOAD_HTTP_CLIENT} / {@link #DOWNLOAD_REST_CLIENT}</td>
 *     <td>导出下载（{@code byte[]} xlsx；同步等 DIB 生成，且会被 N 份文档串行放大）</td>
 *     <td>{@code dib.download-timeout-seconds}（30s）</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #API_HTTP_CLIENT} / {@link #API_REST_CLIENT}</td>
 *     <td>轻量接口：批量新增、资料详情、数据查询、状态轮询、删除（实测都是秒级）</td>
 *     <td>{@code dib.api-timeout-seconds}（10s）</td>
 *   </tr>
 * </table>
 *
 * <p>为什么上传与下载要分档：二者诉求相反 —— 上传要宽容（等 DIB 收完 multipart），
 * 下载要能<b>快速失败</b>（导出是同步 HTTP，串行 N 份会把最坏耗时放大 N 倍，
 * 越过网关 60s 后用户只拿到 504、服务端却仍在空转）。共用一个值会让二者互相绑架。
 * 故上传与下载取值不同：上传 60s / 下载 30s（下载单份最坏 = 等池 3 + 建连 5 + 30 = 38s，
 * 网关 60s 下留 22s 余量）。
 *
 * <p>建连是连接级参数、按请求改不了，三个 client 共用 {@code dib.pool.connect-timeout-seconds}：
 * 响应可以耐心等，「连不上」不该等。
 *
 * <p>池：三组 bean 各持一个独立 {@link PoolingHttpClientConnectionManager}（慢上传不挤占轮询与下载配额）。
 * 可配的只有建连超时 + 各自的单主机连接上限（{@link ApiProperties.Pool}）；
 * 等池超时 / 复用前校验 / TTL / 总连接上限是本类常量 —— 库默认都不可接受
 * （3 分钟 / 不校验 / 永不过期），但没有环境差异、也没有可验证的调参判据。
 * 空闲连接不设 maxIdleTime：过期由 TTL 兜底，复用安全性由复用前探测保证。
 *
 * <p>注入必须带 {@code @Qualifier}（同类型 bean 各三个），配合项目根 {@code lombok.config} 的
 * {@code lombok.copyableAnnotations += ...Qualifier}，否则启动报 {@code NoUniqueBeanDefinitionException}。
 *
 * <p>此处完全自建 HTTP 客户端，<b>不读</b> {@code spring.http.client.*}
 * （那只作用于 Boot 自动装配的 Builder 与 Spring AI 链路）。两套超时并存是有意为之。
 */
@Slf4j
@Configuration
public class RestClientConfig {

    /**
     * 池满时等待可用连接的上限（秒）。HC5 默认 3 分钟，对「快速失败」太长；无环境差异，不做配置项。
     *
     * <p>取 3s 而不是更长：<b>池满时几乎不可能在几秒内等到连接</b> —— 正在跑的那些请求
     * （导出 / 轮询 / 查询）本身就要几十秒才会归还连接。所以「等 10s」等不到的东西
     * 「等 3s」同样等不到，多出来的 7s 只是白占 Tomcat 线程。
     * <p>池不满时本超时完全不参与（立即拿到连接），因此调小它对正常路径零影响。
     */
    private static final int CONNECTION_REQUEST_TIMEOUT_SECONDS = 3;

    /** 复用前的存活校验阈值（秒）。HC5 默认不校验，陈旧连接只能靠失败后重试兜。 */
    private static final int VALIDATE_AFTER_INACTIVITY_SECONDS = 5;

    /** 连接最长存活（秒）。HC5 默认永不过期；本项兜底被防火墙 / LB 静默掐断的长连接。 */
    private static final int CONNECTION_TTL_SECONDS = 300;

    /** 池的总连接上限；单 host 时真正生效的是 per-route。做成常量，避免"设得比 per-route 小"让调参静默失效。 */
    private static final int MAX_CONN_TOTAL = 20;

    /** 附件上传底层 HTTP 客户端 bean 名 */
    public static final String FILE_HTTP_CLIENT = "fileHttpClient";

    /** 导出下载底层 HTTP 客户端 bean 名 */
    public static final String DOWNLOAD_HTTP_CLIENT = "downloadHttpClient";

    /** 轻量接口调用底层 HTTP 客户端 bean 名 */
    public static final String API_HTTP_CLIENT = "apiHttpClient";

    /** 附件上传专用 bean 名 */
    public static final String FILE_REST_CLIENT = "fileRestClient";

    /** 导出下载专用 bean 名 */
    public static final String DOWNLOAD_REST_CLIENT = "downloadRestClient";

    /** 轻量接口调用专用 bean 名 */
    public static final String API_REST_CLIENT = "apiRestClient";

    private final ApiProperties apiProperties;

    public RestClientConfig(ApiProperties apiProperties) {
        this.apiProperties = apiProperties;
        // 空 key 不阻塞启动（接口未必要求鉴权），但很可能是配置漏了，明确提示一下
        if (!StringUtils.hasText(apiProperties.getC1ApiKey())) {
            log.warn("[DIB] dib.c1-api-key 为空：调用 DIB 时不会带鉴权头，接口若要求鉴权会返回 401");
        }
    }

    /**
     * 附件上传用 HTTP 客户端。连接上限 = {@code dib.pool.file-max-per-route}（= 并发上传数 = 堆占用上限）。
     * <p>{@code destroyMethod = "close"} 释放连接池并停掉过期回收线程。
     */
    @Bean(name = FILE_HTTP_CLIENT, destroyMethod = "close")
    public CloseableHttpClient fileHttpClient() {
        return buildHttpClient(apiProperties.getFileTimeoutSeconds(),
                apiProperties.getPool().getFileMaxPerRoute());
    }

    /** 导出下载用 HTTP 客户端。连接上限 = {@code dib.pool.download-max-per-route}。 */
    @Bean(name = DOWNLOAD_HTTP_CLIENT, destroyMethod = "close")
    public CloseableHttpClient downloadHttpClient() {
        return buildHttpClient(apiProperties.getDownloadTimeoutSeconds(),
                apiProperties.getPool().getDownloadMaxPerRoute());
    }

    /** 轻量接口用 HTTP 客户端。连接上限 = {@code dib.pool.api-max-per-route}。 */
    @Bean(name = API_HTTP_CLIENT, destroyMethod = "close")
    public CloseableHttpClient apiHttpClient() {
        return buildHttpClient(apiProperties.getApiTimeoutSeconds(),
                apiProperties.getPool().getApiMaxPerRoute());
    }

    @Bean(FILE_REST_CLIENT)
    public RestClient fileRestClient(@Qualifier(FILE_HTTP_CLIENT) CloseableHttpClient httpClient) {
        return RestClient.builder()
                .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
                .requestInterceptor(apiKeyInterceptor())
                .build();
    }

    @Bean(DOWNLOAD_REST_CLIENT)
    public RestClient downloadRestClient(@Qualifier(DOWNLOAD_HTTP_CLIENT) CloseableHttpClient httpClient) {
        return RestClient.builder()
                .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
                .requestInterceptor(apiKeyInterceptor())
                .build();
    }

    @Bean(API_REST_CLIENT)
    public RestClient apiRestClient(@Qualifier(API_HTTP_CLIENT) CloseableHttpClient httpClient) {
        return RestClient.builder()
                .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
                .requestInterceptor(apiKeyInterceptor())
                .build();
    }

    /**
     * 统一注入 DIB 鉴权头 {@code c1-api-key}，替掉原先散落在 7 个调用点的手写
     * {@code headers.set("c1-api-key", ...)}。
     *
     * <p>收敛的意义不只是少写几行：原先每新增一个外部接口就要记得补一次鉴权头，
     * 漏了只会在联调时表现为 401，属于「靠人记」的约定；现在由客户端统一保证。
     *
     * <p>key 为空时不发该头（DIB 未必要求鉴权），与收敛前的判断一致；
     * 空 key 的告警在构造器里已经打过一次。
     *
     * <p>用 {@code set} 而非 {@code add}：万一将来某个调用点又自行设了同名头，
     * 也以这里的统一值为准，不会出现「两个来源各设一次、改配置只生效一半」的状态。
     */
    private ClientHttpRequestInterceptor apiKeyInterceptor() {
        return (request, body, execution) -> {
            String apiKey = apiProperties.getC1ApiKey();
            if (StringUtils.hasText(apiKey)) {
                request.getHeaders().set("c1-api-key", apiKey);
            }
            return execution.execute(request, body);
        };
    }

    /**
     * @param responseTimeoutSeconds 响应超时（秒）：等到响应 / 读完响应体的上限；建连超时不在其中
     * @param maxPerRoute            本池对单主机最大连接数（= 本 client 的并发上限）
     */
    private CloseableHttpClient buildHttpClient(int responseTimeoutSeconds, int maxPerRoute) {
        Timeout responseTimeout = Timeout.ofSeconds(responseTimeoutSeconds);

        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofSeconds(
                        apiProperties.getPool().getConnectTimeoutSeconds()))
                // 连接层基线；消息交换期间会被下面的 responseTimeout 覆盖
                .setSocketTimeout(responseTimeout)
                .setTimeToLive(TimeValue.ofSeconds(CONNECTION_TTL_SECONDS))
                .setValidateAfterInactivity(
                        TimeValue.ofSeconds(VALIDATE_AFTER_INACTIVITY_SECONDS))
                .build();

        PoolingHttpClientConnectionManager connectionManager =
                PoolingHttpClientConnectionManagerBuilder.create()
                        .setMaxConnTotal(MAX_CONN_TOTAL)
                        .setMaxConnPerRoute(maxPerRoute)
                        .setDefaultConnectionConfig(connectionConfig)
                        .build();

        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(
                        Timeout.ofSeconds(CONNECTION_REQUEST_TIMEOUT_SECONDS))
                .setResponseTimeout(responseTimeout)
                .build();

        return HttpClientBuilder.create()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                // 只管过期回收；空闲回收交给 TTL（见类注释），不设 maxIdleTime
                .evictExpiredConnections()
                .build();
    }
}
