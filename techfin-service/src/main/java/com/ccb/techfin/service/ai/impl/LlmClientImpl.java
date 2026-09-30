package com.ccb.techfin.service.ai.impl;

import com.ccb.techfin.common.exception.BusinessException;
import com.ccb.techfin.service.ai.LlmClient;
import com.ccb.techfin.service.ai.LlmRequest;
import com.ccb.techfin.service.ai.LlmResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link LlmClient} 默认实现：调用 Spring AI 自动装配的 {@link ChatClient}。
 *
 * <p>本类刻意做得很薄——连接、鉴权、模型名、超时、重试全部交给配置与自动装配，这里只保留
 * 三件框架不会替我们做的事：
 * <ol>
 *     <li>组装 system + user 消息；</li>
 *     <li>把调用方身份（{@code X-User-ID}）作为<b>请求级</b>请求头透传——它是每次调用都变的，
 *         写不进配置文件；</li>
 *     <li>把响应与异常收敛成项目统一的 {@link LlmResponse} / {@link BusinessException}。</li>
 * </ol>
 *
 * @author qiuhaoquan
 * @since 2026-09-29
 */
@Slf4j
@Service
public class LlmClientImpl implements LlmClient {

    public static final String CODE_PARAM_MISSING = "LLM_PARAM_MISSING";
    public static final String CODE_CALL_FAILED = "LLM_CALL_FAILED";

    private static final String DEFAULT_USER_ID_HEADER = "X-User-ID";

    private final ChatClient chatClient;

    /** 官方配置的默认 options，仅用于读取全局固定请求头，见 {@link #buildHeaders}。 */
    private final ObjectProvider<OpenAiChatProperties> chatProperties;

    private final String userIdHeader;

    public LlmClientImpl(ChatClient.Builder chatClientBuilder,
                         ObjectProvider<OpenAiChatProperties> chatProperties,
                         @Value("${llm.user-id-header:X-User-ID}") String userIdHeader) {
        this.chatClient = chatClientBuilder.build();
        this.chatProperties = chatProperties;
        this.userIdHeader = StringUtils.hasText(userIdHeader) ? userIdHeader : DEFAULT_USER_ID_HEADER;
    }

    @Override
    public String chat(String userMessage) {
        return chat(LlmRequest.builder().userMessage(userMessage).build()).getContent();
    }

    @Override
    public String chat(String userMessage, String userId) {
        return chat(LlmRequest.builder().userMessage(userMessage).userId(userId).build()).getContent();
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
        if (request == null) {
            throw new BusinessException(CODE_PARAM_MISSING, "大模型调用请求不能为空");
        }
        if (!StringUtils.hasText(request.getUserMessage())) {
            throw new BusinessException(CODE_PARAM_MISSING, "userMessage 不能为空");
        }

        long start = System.currentTimeMillis();
        ChatResponse response;
        try {
            response = chatClient.prompt()
                    .messages(buildMessages(request))
                    .options(buildOptions(request))
                    .call()
                    .chatResponse();
        } catch (Exception e) {
            log.error("[LLM] 调用失败, model={}, userId={}", request.getModel(), request.getUserId(), e);
            throw new BusinessException(CODE_CALL_FAILED, "大模型调用失败: " + e.getMessage(), e);
        }
        long durationMs = System.currentTimeMillis() - start;

        LlmResponse result = toResponse(request.getModel(), response, durationMs);
        log.info("[LLM] 调用完成, model={}, userId={}, 耗时={}ms, tokens in/out={}/{}",
                result.getActualModel() != null ? result.getActualModel() : result.getModel(),
                request.getUserId(), durationMs,
                result.getPromptTokens(), result.getCompletionTokens());
        return result;
    }

    private List<Message> buildMessages(LlmRequest request) {
        List<Message> messages = new ArrayList<>(2);
        if (StringUtils.hasText(request.getSystem())) {
            messages.add(new SystemMessage(request.getSystem()));
        }
        messages.add(new UserMessage(request.getUserMessage()));
        return messages;
    }

    /**
     * 只设置需要覆盖的项，其余字段交给 {@code spring.ai.openai.chat.options.*} 的默认值。
     *
     * <p>{@link OpenAiChatOptions#builder()} 不会注入任何默认值（温度等只在
     * {@code OpenAiChatProperties} 里设），所以这里漏设的字段是「继承配置」，
     * 不会被悄悄改成框架默认值。
     */
    private OpenAiChatOptions buildOptions(LlmRequest request) {
        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder();

        if (StringUtils.hasText(request.getModel())) {
            options.model(request.getModel());
        }
        Map<String, String> headers = buildHeaders(request);
        if (!headers.isEmpty()) {
            options.httpHeaders(headers);
        }
        if (request.getTemperature() != null) {
            options.temperature(request.getTemperature());
        }
        if (request.getMaxTokens() != null) {
            options.maxTokens(request.getMaxTokens());
        }
        return options.build();
    }

    /**
     * 组装请求头，优先级从低到高：全局固定头 &lt; 调用方身份 &lt; 调用方自定义头。
     *
     * <p>必须自己合、不能只塞身份头：Spring AI 合并请求级 options 与默认 options 时，
     * 对 Map 字段是<b>整体替换</b>而非逐项合并——只写 {@code X-User-ID} 会把
     * {@code spring.ai.openai.chat.options.http-headers} 里配的固定头一起冲掉。
     */
    private Map<String, String> buildHeaders(LlmRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();

        OpenAiChatProperties properties = chatProperties.getIfAvailable();
        if (properties != null && properties.getOptions() != null
                && properties.getOptions().getHttpHeaders() != null) {
            headers.putAll(properties.getOptions().getHttpHeaders());
        }
        if (StringUtils.hasText(request.getUserId())) {
            headers.put(userIdHeader, request.getUserId());
        }
        if (request.getHeaders() != null) {
            headers.putAll(request.getHeaders());
        }
        return headers;
    }

    private LlmResponse toResponse(String requestedModel, ChatResponse response, long durationMs) {
        String content = null;
        if (response != null && response.getResult() != null) {
            Generation generation = response.getResult();
            content = generation.getOutput() == null ? null : generation.getOutput().getText();
        }
        if (content == null) {
            throw new BusinessException(CODE_CALL_FAILED, "大模型返回内容为空");
        }

        LlmResponse.LlmResponseBuilder builder = LlmResponse.builder()
                .content(content)
                .model(requestedModel)
                .durationMs(durationMs);

        ChatResponseMetadata metadata = response.getMetadata();
        if (metadata != null) {
            builder.actualModel(metadata.getModel()).responseId(metadata.getId());
            Usage usage = metadata.getUsage();
            if (usage != null) {
                builder.promptTokens(usage.getPromptTokens())
                        .completionTokens(usage.getCompletionTokens())
                        .totalTokens(usage.getTotalTokens());
            }
        }
        return builder.build();
    }
}
