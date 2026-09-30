package com.ccb.techfin.service.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 大模型调用结果。
 *
 * @author qiuhaoquan
 * @since 2026-09-29
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmResponse {

    /** 模型返回的文本内容。 */
    private String content;

    /** 请求里指定的模型名；未指定时为 null，此时以 {@link #actualModel} 为准。 */
    private String model;

    /** 网关实际返回的模型名，排查问题时使用。 */
    private String actualModel;

    /** 网关返回的响应 id，排查问题时使用。 */
    private String responseId;

    private Integer promptTokens;

    private Integer completionTokens;

    private Integer totalTokens;

    /** 本次调用耗时（毫秒），含重试耗时。 */
    private Long durationMs;
}
