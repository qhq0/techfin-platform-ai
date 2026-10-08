package com.ccb.techfin.service.external.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import java.util.Map;

/**
 * DIB 平台对接配置。
 *
 * <p>只保留两类值：部署方必须给的（{@code dib.*-url} / key / 项目与文档类型），
 * 以及随环境而变的 6 个 HTTP 参数。等池超时 / 复用前校验 / TTL / 总连接上限同样必须显式设置
 * （库默认不可接受），但没有环境差异，作为常量写在 {@code RestClientConfig}。
 *
 * <p>校验在绑定阶段触发，配错直接<b>启动失败</b>（生产配置是独立文件，漏配最该在这里暴露）：
 * 数值项 {@code @Min}（超时类必须挡 0 —— HC5 里 0 = 无限等待）；接口地址 {@code @NotBlank}；
 * 项目 ID {@code @NotNull}；文档类型映射另用 {@link #isDocTypeComplete()} 检查两个必要 key。
 * {@code c1-api-key} 允许为空（代码按需决定带不带鉴权头），只在启动时告警。
 */
@Data
@Validated
@Configuration
@ConfigurationProperties(prefix = "dib")
public class ApiProperties {

    @NotBlank(message = "必须配置 DIB 接口地址")
    private String attachmentUploadUrl;

    @NotBlank(message = "必须配置 DIB 接口地址")
    private String docBatchAddUrl;

    @NotBlank(message = "必须配置 DIB 接口地址")
    private String docDetailUrl;

    @NotBlank(message = "必须配置 DIB 接口地址")
    private String docQueryDataUrl;

    @NotBlank(message = "必须配置 DIB 接口地址")
    private String docExportDataUrl;

    @NotBlank(message = "必须配置 DIB 接口地址")
    private String docTableExtractStateUrl;

    @NotBlank(message = "必须配置 DIB 接口地址")
    private String docDeleteUrl;

    @NotNull(message = "必须配置（资料批量新增要用）")
    private Long sxdProjectId;

    private Long dirId = 0L;

    /** 文档类型 ID 映射，必须同时含 finance 与 business */
    private Map<String, Long> docType;

    private String c1ApiKey = "";

    /**
     * 文档类型映射必须同时含 finance 与 business：代码直接按这两个 key 取 docTypeId
     * （{@code getDocType().get("finance")}），缺一个就是运行时 NPE。
     */
    @AssertTrue(message = "dib.doc-type 必须同时配置 finance 与 business")
    public boolean isDocTypeComplete() {
        return docType != null && docType.get("finance") != null && docType.get("business") != null;
    }

    /** 附件上传的响应超时（秒）：等待响应 / 读响应体的空闲上限；上传的写请求体阶段不受它约束。 */
    @Min(value = 1, message = "必须 >= 1 秒（HC5 里 0 = 无限等待）")
    private int fileTimeoutSeconds = 60;

    /**
     * 导出下载的响应超时（秒）。
     *
     * <p>为什么单独一档：本值原先与 {@link #fileTimeoutSeconds} 共用一个 60s，但两者诉求相反 ——
     * 上传要宽容（等 DIB 收完 multipart），下载要能<b>快速失败</b>（导出是同步 HTTP，最坏耗时会被
     * N 份文档串行放大，越过网关 60s 后用户拿到 504、而服务端仍在空转）。
     * 共用一个值会让二者互相绑架：想压下载超时就会误伤大文件上传。
     *
     * <p>取 30s 的依据：单份下载最坏 = 等池(3s) + 建连(5s) + 本值 = <b>38s</b>，
     * 在网关 60s 下留出 22s 余量给「打包 zip + 写响应 + 网络抖动」。
     * 导出物是几百 KB 量级的 xlsx，正常只有几百毫秒，30s 已极宽松。
     *
     * <p>若将来给导出加「总预算 deadline」，预算须满足
     * {@code 预算 > 等池(3s) + 建连(5s) + 本值(30s) = 38s}，否则闸门会在第一份就拦住自己。
     */
    @Min(value = 1, message = "必须 >= 1 秒（HC5 里 0 = 无限等待）")
    private int downloadTimeoutSeconds = 30;

    /**
     * 轻量接口的响应超时（秒）：实测都是秒级，10s 已远超正常值。
     *
     * <p>从 15s 收到 10s 的理由：这一档是串行放大的重灾区（{@code queryBusinessExtractData} 最多放大 9 倍、
     * {@code generateReport} 放大 5 倍），收窄本值对「最坏总时长」的削减立竿见影；
     * 而正常调用只有几百毫秒，完全不受影响。单份最坏 = 等池(3s) + 建连(5s) + 本值 = <b>18s</b>。
     *
     * <p>它同时约束「轮询」与「查询」两类接口 —— 这也正是它不能再与导出下载共用一档的原因
     * （轮询要短、下载要长，诉求相反）。
     */
    @Min(value = 1, message = "必须 >= 1 秒（HC5 里 0 = 无限等待）")
    private int apiTimeoutSeconds = 10;

    /** 连接池里随环境而变的三个值；其余池参数是 {@code RestClientConfig} 的常量 */
    @Valid
    private Pool pool = new Pool();

    /**
     * 刻意没有「共用基线 + 按 client 覆盖」那层间接：每个值都是最终生效值。
     * （HC5 对 maxTotal / maxPerRoute 取较小者生效，小池上再设更小的 maxTotal 会让调 per-route 静默失效。）
     */
    @Data
    public static class Pool {

        /** 建连超时（秒），两 client 共用：DIB 走 http，这一段只有 TCP + DNS，5s 余量已很足。 */
        @Min(value = 1, message = "必须 >= 1 秒（HC5 里 0 = 无限等待）")
        private int connectTimeoutSeconds = 5;

        /** 上传 client 单主机连接上限 = 并发上传数上限 = 堆占用上限（单文件 50MB，4 并发≈200MB）。 */
        @Min(value = 1, message = "必须 >= 1（0 个连接的池没有意义）")
        private int fileMaxPerRoute = 4;

        /** download client 单主机连接上限；导出是用户点击触发的低频操作，4 个连接已很富余。 */
        @Min(value = 1, message = "必须 >= 1（0 个连接的池没有意义）")
        private int downloadMaxPerRoute = 4;

        /** api client 单主机连接上限；调大前先确认 DIB 的并发配额。 */
        @Min(value = 1, message = "必须 >= 1（0 个连接的池没有意义）")
        private int apiMaxPerRoute = 10;
    }
}
