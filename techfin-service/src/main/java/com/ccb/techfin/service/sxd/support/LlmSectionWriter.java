package com.ccb.techfin.service.sxd.support;

import com.ccb.techfin.service.ai.LlmClient;
import com.ccb.techfin.service.ai.LlmRequest;
import com.ccb.techfin.service.ai.LlmResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.UnaryOperator;

/**
 * 报告「大模型生成段落」的公共执行器。
 *
 * <p>报告里凡是"由大模型把已有材料改写成文字"的段落（企业主营业务情况、异常财务数据说明等），
 * 骨架完全一致：读提示词 → 拼用户消息 → 调模型 → 规整输出 → 任何失败一律留空。差异只在
 * <b>提示词内容</b>与<b>材料怎么拼</b>，那部分留给各自的段落生成器。这里只收敛骨架，避免
 * "失败降级策略"在多个类里各写一遍、时间久了长歪。
 *
 * <p><b>失败一律留空、绝不抛异常</b>：提示词不可用、超时、网关报错、重试耗尽、返回空内容，
 * 全部返回空字符串。报告本身才是交付物，这些段落只是排版增强；为一段文字让整份报告生成失败
 * 不划算。留空在报告里是"静默"的，所以失败都打 <b>ERROR</b> 日志 —— 日志是发现异常的唯一信号。
 *
 * <p><b>不设 {@code maxTokens}</b>：{@code deepseek-flash} 带思考链，思考 token 也计入该上限，
 * 限小了会出现"思考把额度吃满、正文为空"（实测 2000 时必现）。只设温度，生成长度交给服务端默认。
 *
 * <p><b>只走 {@link LlmClient} 门面，不直接碰 Spring AI 类型</b>：这里注入的是<b>接口</b>
 * {@code LlmClient}，运行时由 Spring 装配到 {@code LlmClientImpl}。全项目只有
 * {@code LlmClientImpl} 一个类引用 Spring AI（{@code ChatClient}/{@code OpenAiChatOptions}）。
 * 这是刻意的：这套代码将来要换成调用<b>自定义模型</b>，届时只需换/加一个 {@code LlmClient} 实现
 * （或改 {@code spring.ai.openai.*} 配置指向自定义网关），本类与两个段落生成器<b>一行都不用改</b>。
 * ⚠️ 因此<b>不要</b>在这里直接写 HTTP 调用、也不要直接注入 {@code ChatClient}；新增 AI 调用一律
 * 加方法到 {@code LlmClient}。⚠️ 若将来同时存在多个 {@code LlmClient} 实现，记得给默认那个加
 * {@code @Primary}（或注入处带 {@code @Qualifier}），否则会 {@code NoUniqueBeanDefinitionException}。
 *
 * @author qiuhaoquan
 * @since 2026-10-08
 */
@Slf4j
@Component
public class LlmSectionWriter {

    /**
     * 采样温度。这类段落要的是"忠于材料"，越低越稳；取 0.3 而非 0，是留给措辞衔接一点余地，
     * 避免句式生硬到读不通。
     */
    private static final Double TEMPERATURE = 0.3;

    private final LlmClient llmClient;
    private final ResourceLoader resourceLoader;

    public LlmSectionWriter(LlmClient llmClient, ResourceLoader resourceLoader) {
        this.llmClient = llmClient;
        this.resourceLoader = resourceLoader;
    }

    /**
     * 读取并缓存提示词文件。由各段落生成器在<b>构造期</b>调一次即可 —— 提示词是构建期产物，
     * 不会在运行中变化，没必要每个请求都读盘。
     *
     * <p>任何失败（文件不存在 / 内容为空 / 读取异常）都返回 {@code null}，交由调用方按
     * "该段落留空"处理，<b>不</b>在构造期抛异常：提示词缺失属可降级的增强能力，
     * 不该让整个应用起不来。
     *
     * @param section 段落名，仅用于日志，如「主营业务情况」
     * @param path    提示词文件路径，支持 {@code classpath:} / {@code file:} 前缀
     * @return 提示词正文；不可用时为 {@code null}
     */
    public String loadPrompt(String section, String path) {
        Resource resource = resourceLoader.getResource(path);
        if (!resource.exists()) {
            log.error("[{}] 提示词文件不存在：{}，该段落将留空", section, path);
            return null;
        }
        try (InputStream is = resource.getInputStream()) {
            String text = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            if (!StringUtils.hasText(text)) {
                log.error("[{}] 提示词文件内容为空：{}，该段落将留空", section, path);
                return null;
            }
            return text.trim();
        } catch (Exception e) {
            log.error("[{}] 提示词文件读取失败：{}，该段落将留空", section, path, e);
            return null;
        }
    }

    /**
     * 调用大模型生成一个<b>单段</b>文字：输出中的换行会被折平，整段占一个 Word 段落。
     *
     * @param section      段落名，仅用于日志，如「主营业务情况」
     * @param taskId       任务 ID，仅用于日志
     * @param systemPrompt 系统提示词（写作要求）；为 {@code null} 表示提示词不可用，直接留空
     * @param userMessage  用户消息（材料 + 写作指令），由调用方拼好
     * @param userId       调用方账号，透传给大模型网关（为空则不发身份头），可空
     * @return 规整后的段落文字；任何失败一律返回<b>空字符串</b>
     */
    public String write(String section, String taskId, String systemPrompt, String userMessage, String userId) {
        return generate(section, taskId, systemPrompt, userMessage, userId, LlmSectionWriter::normalizeSingle);
    }

    /**
     * 调用大模型生成<b>多行</b>文字：逐行保留换行（空行会被丢弃），供需要"一个科目一行"的
     * 段落使用。换行在 Word 里由 {@code <w:br/>} 承接，与商业计划书提取文本的呈现方式一致。
     *
     * @see #write(String, String, String, String, String)
     */
    public String writeMultiline(String section, String taskId, String systemPrompt, String userMessage,
                                 String userId) {
        return generate(section, taskId, systemPrompt, userMessage, userId, LlmSectionWriter::normalizeMultiline);
    }

    /**
     * 调模型 + 失败降级的唯一实现：所有段落走同一条路径，保证"任何失败都留空"的口径不会被写歪。
     *
     * @param normalizer 输出规整函数（单段折平 / 多行保留），见 {@link #normalizeSingle}、
     *                   {@link #normalizeMultiline}
     */
    private String generate(String section, String taskId, String systemPrompt, String userMessage,
                            String userId, UnaryOperator<String> normalizer) {
        if (systemPrompt == null) {
            log.error("[{}] 任务 {} 提示词不可用，该段落留空", section, taskId);
            return "";
        }

        try {
            // 刻意不设 maxTokens：deepseek-flash 带思考链，思考 token 也计入该上限，
            // 限小了会出现"思考把额度吃满、正文为空"（实测 2000 时必现）。不传则沿用服务端默认。
            LlmResponse response = llmClient.chat(LlmRequest.builder()
                    .system(systemPrompt)
                    .userMessage(userMessage)
                    .temperature(TEMPERATURE)
                    .userId(userId)
                    .build());

            String text = normalizer.apply(response == null ? null : response.getContent());
            if (text.isEmpty()) {
                // 返回空内容就直接留空，不退化成"原始材料拼接"：空内容大概率是模型判断材料里
                // 没有可写的东西，这时把原始摘录塞进报告，比留空更难看。
                log.error("[{}] 任务 {} 大模型返回空内容，该段落留空", section, taskId);
                return "";
            }
            log.info("[{}] 任务 {} 段落生成完成，输出 {} 字", section, taskId, text.length());
            return text;
        } catch (Exception e) {
            // 含大模型超时、网关 4xx/5xx、重试耗尽等所有异常
            log.error("[{}] 任务 {} 大模型调用失败，该段落留空：{}", section, taskId, e.getMessage(), e);
            return "";
        }
    }

    /**
     * 单段规整：去首尾空白、折平换行、剥掉整体包裹的引号。
     *
     * <p>折平换行是因为模板里该占位符只占一个段落，样式要求也是一段连续文字；中文句子之间
     * 不需要空格，直接删掉换行符即可，不会把字粘错。
     */
    private static String normalizeSingle(String raw) {
        return stripWrappingQuotes(raw == null ? "" : raw.replace("\r", "").replace("\n", "").trim());
    }

    /**
     * 多行规整：逐行去首尾空白、丢掉空行，再拼回换行分隔的文本。
     *
     * <p>只做"每行 trim + 去空行"，不合并行 —— 每个科目独立成行是这份材料要求的形态。
     */
    private static String normalizeMultiline(String raw) {
        if (raw == null) {
            return "";
        }
        String[] lines = raw.replace("\r", "").split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(trimmed);
        }
        return stripWrappingQuotes(sb.toString().trim());
    }

    /**
     * 剥掉模型整体包裹的一对引号（中英文都认）。模型有时会把整段话用引号包起来。
     */
    private static String stripWrappingQuotes(String text) {
        while (text.length() >= 2
                && ((text.startsWith("\"") && text.endsWith("\""))
                || (text.startsWith("“") && text.endsWith("”")))) {
            text = text.substring(1, text.length() - 1).trim();
        }
        return text;
    }
}
