package com.ccb.techfin.service.ai.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.TrustAllStrategy;
import org.apache.hc.core5.ssl.SSLContextBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.http.client.ClientHttpRequestFactoryBuilderCustomizer;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpComponentsClientHttpRequestFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.net.ssl.SSLContext;

/**
 * 跳过 TLS 证书校验（大模型网关用自签证书、又拿不到证书导出时的兜底方案）。
 *
 * <p><b>默认关闭</b>，只有显式配置 {@code llm.insecure-ssl=true} 才生效。
 * 生效后大模型调用的 HTTP 客户端「不校验服务端证书链、不校验主机名」，
 * 中间人攻击无法被发现 —— 仅限内网自签证书环境临时使用。
 *
 * <h3>优先用配置方案，而不是这里</h3>
 * 如果能拿到网关证书（或签发它的内网 CA 证书），应该走「配置可信证书」这条路，
 * 零 Java 代码且不降低安全性：
 * <pre>
 *   # 1. 导出证书（不用 openssl，keytool 自带）
 *   keytool -printcert -sslserver openapi.ai.jh:443 -rfc -file openapi-ai-jh.crt
 *   # 2. 放进 techfin-controller/src/main/resources/cert/ ，然后配置：
 *   spring.ssl.bundle.pem.gateway.truststore.certificate=classpath:cert/openapi-ai-jh.crt
 *   spring.http.client.ssl.bundle=gateway
 * </pre>
 *
 * <h3>为什么用 {@code ClientHttpRequestFactoryBuilderCustomizer} 而不是 {@code RestClientCustomizer}</h3>
 * 两种写法都能改到 Spring AI 的 HTTP 客户端（它取自自动装配的 {@code RestClient.Builder}），
 * 但代价完全不同：
 * <ul>
 *   <li>{@code RestClientCustomizer} + {@code builder.requestFactory(...)} 是<b>整体覆盖</b> ——
 *       Boot 已按 {@code spring.http.client.*} 建好、装进 builder 的那份 requestFactory
 *       连同超时一起被丢弃，配置随之失效。而 {@code RestClient.Builder} 只保留最后一次设置的
 *       factory，不会合并。<b>实测</b>：改前读超时 2s，覆盖后请求等满 5s 仍成功返回。</li>
 *   <li>本类走的 {@code ClientHttpRequestFactoryBuilderCustomizer} 是 Boot 3.5 的扩展点，
 *       它在 Boot <b>构建</b> requestFactory 的过程中被调用，只替换连接管理器上的 TLS 策略，
 *       超时仍由 Boot 按 {@code spring.http.client.*} 施加。<b>实测</b>：TLS 放行成功，
 *       且读超时 2s 依旧在 ~2s 切断请求。</li>
 * </ul>
 * 简单说：覆盖 requestFactory 会连超时一起丢掉，进而使「最坏阻塞 = max-attempts × read-timeout」
 * 里的 read-timeout 变成无限；本写法没有这个副作用。
 *
 * <h3>作用范围</h3>
 * <b>只影响 Spring AI 这条链路</b>（大模型调用）。项目里 DIB 那两组自建 RestClient 走的是
 * 静态 {@code RestClient.builder()} + 自建 HC5 客户端，与本扩展点无关，不受影响，也不会被顺带放行。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "llm.insecure-ssl", havingValue = "true")
public class LlmInsecureSslConfig {

    /**
     * 往自动装配的 HC5 requestFactory 构建器上挂「信任所有证书 + 不校验主机名」的 TLS 策略。
     */
    @Bean
    ClientHttpRequestFactoryBuilderCustomizer<ClientHttpRequestFactoryBuilder<?>> llmInsecureTlsCustomizer() {
        log.warn("[LLM] llm.insecure-ssl=true 已生效：大模型调用的 TLS 证书链与主机名校验都被跳过，"
                + "请仅在内网自签证书环境使用。若已能拿到网关证书，建议改用 "
                + "spring.ssl.bundle.pem.<name>.truststore.certificate + spring.http.client.ssl.bundle 配置真实证书。");

        return builder -> {
            if (builder instanceof HttpComponentsClientHttpRequestFactoryBuilder httpComponents) {
                return httpComponents.withConnectionManagerCustomizer(LlmInsecureSslConfig::applyTrustAllTls);
            }
            // classpath 上没有 httpclient5 时 Boot 会选别的客户端（JDK / Jetty），此时本方案不适用
            log.error("[LLM] llm.insecure-ssl=true 未生效：当前 HTTP 客户端实现是 {}，本方案仅支持 Apache HttpClient 5",
                    builder.getClass().getName());
            return builder;
        };
    }

    /** 只替换连接管理器上的 TLS 策略，不碰超时 —— 超时仍由 Boot 按 spring.http.client.* 施加。 */
    private static void applyTrustAllTls(PoolingHttpClientConnectionManagerBuilder connectionManager) {
        try {
            SSLContext sslContext = SSLContextBuilder.create()
                    .loadTrustMaterial(TrustAllStrategy.INSTANCE)
                    .build();
            connectionManager.setTlsSocketStrategy(ClientTlsStrategyBuilder.create()
                    .setSslContext(sslContext)
                    // 主机名也不校验：自签证书通常 CN / SAN 与访问域名对不上
                    .setHostnameVerifier(NoopHostnameVerifier.INSTANCE)
                    .buildClassic());
        } catch (Exception e) {
            throw new IllegalStateException("构造「信任所有证书」的 TLS 策略失败", e);
        }
    }
}
