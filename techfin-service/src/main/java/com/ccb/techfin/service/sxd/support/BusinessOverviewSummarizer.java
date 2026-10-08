package com.ccb.techfin.service.sxd.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 「企业主营业务情况」段落生成器。
 *
 * <p>把商业计划书里 7 段提取文本（见 {@link BpExtractSources#SOURCES}：公司介绍 / 主营业务和产品 /
 * 经营情况介绍 / 研发成果与转化情况 / 竞争优势介绍 / 发展战略情况 / 行业发展情况）交给大模型做一次
 * <b>二次总结编辑</b>，合并成报告模板中 {@code {{dib_manage_business_overview}}} 一个占位符
 * 所需的一段文字，替代原先报告里并列的原始提取文本。
 *
 * <p><b>素材范围（2026-10-08 由 2 段扩到 7 段）</b>：原先只用「主营业务和产品」「经营情况介绍」，
 * 现把另外 5 段（公司介绍、研发成果、竞争优势、发展战略、行业发展）也一并作为参考材料喂给模型。
 * <b>但输出结构不变</b> —— 仍是那七项内容、仍限 600 字，新增材料只作背景参考，服务于原有七项，
 * 不单独成段。理由：这段落的名字与用途就是"主营业务情况"，把材料摊到全段落会偏离主题、也会顶破篇幅。
 *
 * <p>素材清单与拼装逻辑统一放在 {@link BpExtractSources}，与 {@link BusinessProfileSummarizer} 共用。
 *
 * <p><b>为什么放在报告生成时、而不是要素提取时</b>：提取阶段必须保留原文，供人工核对与导出
 * xlsx 使用；总结只服务于 Word 报告，且每份任务的报告只生成一次，没有必要把结果落库。
 *
 * <p><b>所有素材都为空时不调用大模型</b>：空输入下模型只会编造内容，且纯属浪费一次调用。
 *
 * <p>调用与失败降级的公共逻辑见 {@link LlmSectionWriter}：任何失败（提示词不可用 / 调用异常 /
 * 返回空内容）一律让该段落<b>留空</b>并打 ERROR 日志，绝不中断报告生成。
 *
 * @author qiuhaoquan
 * @since 2026-10-08
 */
@Slf4j
@Component
public class BusinessOverviewSummarizer {

    /** 段落名，日志前缀与占位符语义共用。 */
    private static final String SECTION = "主营业务情况";

    private static final String USER_INSTRUCTION = "请依据上述材料，按要求撰写“申报企业主营业务情况”段落。";

    private final LlmSectionWriter sectionWriter;

    /** 提示词文件路径，仅用于日志与排障。 */
    private final String promptPath;

    /** 系统提示词（写作要求）正文；为 null 表示提示词不可用，整体走降级。 */
    private final String systemPrompt;

    public BusinessOverviewSummarizer(
            LlmSectionWriter sectionWriter,
            @Value("${report.business-overview-prompt-path:classpath:templates/sxd/business-overview-prompt.txt}")
            String promptPath) {
        this.sectionWriter = sectionWriter;
        this.promptPath = promptPath;
        // 启动时读一次并缓存：提示词是构建期产物，不会在运行中变化，没必要每个请求都读一遍文件。
        this.systemPrompt = sectionWriter.loadPrompt(SECTION, promptPath);
    }

    /**
     * 把 {@link BpExtractSources#SOURCES} 里各段原始提取文本汇总成「企业主营业务情况」段落。
     *
     * @param taskId         任务 ID，仅用于日志
     * @param extractTextMap 缓存表名 → 提取文本（由 {@code loadExtractDataFromCache} 装配），可空
     * @param userId         调用方账号，透传给大模型网关（为空则不发身份头），可空
     * @return 汇总后的单段文字；任何失败（提示词不可用 / 调用异常 / 返回空内容）或所有素材均为空时，
     * 一律返回<b>空字符串</b>，占位符随之被清空
     */
    public String summarize(String taskId, Map<String, String> extractTextMap, String userId) {
        List<BpExtractSources.Source> present = BpExtractSources.presentSources(extractTextMap);

        if (present.isEmpty()) {
            log.warn("[{}] 任务 {} 的 {} 段素材全部为空，不调用大模型",
                    SECTION, taskId, BpExtractSources.SOURCES.size());
            return "";
        }
        if (systemPrompt == null) {
            log.error("[{}] 任务 {} 提示词不可用（{}），该段落留空", SECTION, taskId, promptPath);
            return "";
        }
        log.info("[{}] 任务 {} 入参 {} 段共 {} 字（{}）", SECTION, taskId, present.size(),
                BpExtractSources.totalLength(present, extractTextMap), BpExtractSources.labels(present));

        return sectionWriter.write(SECTION, taskId, systemPrompt,
                buildUserMessage(present, extractTextMap), userId);
    }

    /** 拼用户消息：各段材料带小标题，尾部接一句总指令。 */
    private static String buildUserMessage(List<BpExtractSources.Source> present,
                                           Map<String, String> extractTextMap) {
        return BpExtractSources.buildMaterialBlock(present, extractTextMap) + USER_INSTRUCTION;
    }
}
