package com.ccb.techfin.service.ai;

/**
 * 通用大模型调用门面。
 *
 * <p>底层是 Spring AI 的 OpenAI 兼容协议实现，由官方 starter 自动装配。网关地址、鉴权、
 * 模型名、超时、重试全部通过配置驱动：
 * <ul>
 *     <li>{@code spring.ai.openai.*} —— 连接与模型参数</li>
 *     <li>{@code spring.ai.retry.*} —— 重试策略</li>
 *     <li>{@code spring.http.client.*} —— 连接/读超时</li>
 *     <li>{@code llm.user-id-header} —— 调用方身份透传用的请求头名</li>
 * </ul>
 * 业务侧只依赖本接口，不接触任何 Spring AI 类型，换模型/换网关只改配置。
 *
 * <p>失败统一抛 {@link com.ccb.techfin.common.exception.BusinessException}，由
 * {@code RestExceptionHandler} 转换为统一响应：
 * <ul>
 *     <li>{@code LLM_PARAM_MISSING} — 入参缺失</li>
 *     <li>{@code LLM_CALL_FAILED} — 网关调用失败或返回空内容</li>
 * </ul>
 *
 * @author qiuhaoquan
 * @since 2026-09-29
 */
public interface LlmClient {

    /**
     * 使用配置的默认模型完成一次单轮问答，不透传调用方身份。
     *
     * @param userMessage 用户输入
     * @return 模型返回的文本
     */
    String chat(String userMessage);

    /**
     * 使用配置的默认模型完成一次单轮问答，并把调用方身份透传给网关。
     *
     * <p>身份值会写入 {@code llm.user-id-header} 指定的请求头（默认 {@code X-User-ID}）。
     * 典型用法是在 Controller 里取出拦截器放好的账号：
     * <pre>{@code
     * String userAccount = (String) servletRequest.getAttribute("userAccount");
     * String answer = llmClient.chat("请介绍一下你自己", userAccount);
     * }</pre>
     *
     * @param userMessage 用户输入
     * @param userId      调用方身份，为空时不发该请求头
     * @return 模型返回的文本
     */
    String chat(String userMessage, String userId);

    /**
     * 完整参数的调用，返回文本与 token 用量。
     *
     * @param request 调用请求
     * @return 调用结果
     */
    LlmResponse chat(LlmRequest request);
}
