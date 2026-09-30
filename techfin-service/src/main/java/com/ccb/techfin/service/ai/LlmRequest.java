package com.ccb.techfin.service.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 大模型调用请求。
 *
 * <p>除 {@code userMessage} 外的字段都可空，为空表示沿用配置文件里的默认值。
 *
 * @author qiuhaoquan
 * @since 2026-09-29
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmRequest {

    /**
     * 模型名，为空时使用配置的默认模型（{@code spring.ai.openai.chat.options.model}）。
     * 这里填的是网关上的真实模型名，走同一个网关连接。
     */
    private String model;

    /** 调用方身份，透传到 llm.user-id-header 指定的请求头（如 X-User-ID）。 */
    private String userId;

    /** 系统提示词，可空。 */
    private String system;

    /** 用户输入，必填。 */
    private String userMessage;

    /** 采样温度，可空；覆盖配置里的默认值。 */
    private Double temperature;

    /** 最大生成 token 数，可空；覆盖配置里的默认值。 */
    private Integer maxTokens;

    /** 额外请求头，可空；同名时覆盖全局固定头与身份头。 */
    private Map<String, String> headers;
}
