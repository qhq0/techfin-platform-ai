package com.ccb.techfin.service.sxd.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 「实际控制人及团队简介」段落生成器。
 *
 * <p>把商业计划书里 {@code dib_director_keyresume}（人员简历）提取出来的多行文本 ——
 * 形如 {@code 职位：董事长，简介：王美女士，深圳大学商务管理专业……} —— 连同
 * {@code sxd_record.act_cntlr_nm}（实控人姓名）一起交给大模型做一次<b>二次总结编辑</b>，
 * 输出报告模板中 {@code {{dib_director_keyresume}}} 一个占位符所需的固定三段式文字：
 * <pre>
 * 实际控制人情况：张三，本科，毕业于深圳大学，2007-2011 年就职于……，任董事长一职。婚姻状态为已婚（配偶为李四）。获得荣誉情况：……
 * 核心团队情况：
 * 李四，硕士，毕业于……，2015 年至今就职于……，任总经理一职。
 * </pre>
 *
 * <p><b>姓名比对交给模型、不在 Java 侧做</b>：材料里人名可能写成「王美女士」「王美（董事长）」，
 * 甚至只写「王总」，用规范化子串匹配既会漏判（称呼被改写）也会误判（重名）。模型读原文判断更可靠。
 * 实控人若在材料中出现，其信息写成第 1 行，且<b>不再重复出现在团队段</b>；若不在材料中，
 * 第 1 行固定写成「实际控制人情况：{姓名}（材料中未包含其履历信息，待补充）。」。
 *
 * <p><b>为什么放在报告生成时、而不是要素提取时</b>：提取阶段必须保留原文，供人工核对与导出
 * xlsx 使用；总结只服务于 Word 报告，且每份任务的报告只生成一次，没有必要把结果落库。
 *
 * <p><b>材料为空时不调用大模型</b>：空输入下模型只会编造内容，且纯属浪费一次调用。
 *
 * <p>调用与失败降级的公共逻辑见 {@link LlmSectionWriter}：任何失败（提示词不可用 / 调用异常 /
 * 返回空内容）一律让该段落<b>留空</b>并打 ERROR 日志，绝不中断报告生成。输出是多行（每人一段），
 * 走 {@link LlmSectionWriter#writeMultiline}，换行在 Word 里由 {@code <w:br/>} 承接。
 *
 * @author qiuhaoquan
 * @since 2026-10-08
 */
@Slf4j
@Component
public class DirectorResumeSummarizer {

    /** 段落名，日志前缀与占位符语义共用。 */
    private static final String SECTION = "实际控制人及团队简介";

    /** 实控人姓名缺失时传给模型的固定说法，提示词据此输出「（实控人姓名未提供，待补充）」。 */
    private static final String NAME_ABSENT = "未提供";

    private static final String LABEL_NAME = "【实控人姓名】";
    private static final String LABEL_MATERIAL = "【提取材料】";
    private static final String USER_INSTRUCTION = "请依据上述实控人姓名与提取材料，按要求输出「实际控制人及团队简介」。";

    private final LlmSectionWriter sectionWriter;

    /** 提示词文件路径，仅用于日志与排障。 */
    private final String promptPath;

    /** 系统提示词（写作要求）正文；为 null 表示提示词不可用，整体走降级。 */
    private final String systemPrompt;

    public DirectorResumeSummarizer(
            LlmSectionWriter sectionWriter,
            @Value("${report.director-resume-prompt-path:classpath:templates/sxd/director-resume-prompt.txt}")
            String promptPath) {
        this.sectionWriter = sectionWriter;
        this.promptPath = promptPath;
        // 启动时读一次并缓存：提示词是构建期产物，不会在运行中变化，没必要每个请求都读一遍文件。
        this.systemPrompt = sectionWriter.loadPrompt(SECTION, promptPath);
    }

    /**
     * 把人员简历多行文本重写成「实际控制人及团队简介」段落。
     *
     * @param taskId     任务 ID，仅用于日志
     * @param actCntlrNm 实控人姓名（取自 {@code sxd_record.act_cntlr_nm}），可空；为空时按「未提供」处理
     * @param material   {@code dib_director_keyresume} 的提取文本（多行），可空
     * @param userId     调用方账号，透传给大模型网关（为空则不发身份头），可空
     * @return 重写后的多行文字；任何失败（提示词不可用 / 调用异常 / 返回空内容）或材料为空时，
     * 一律返回<b>空字符串</b>，占位符随之被清空
     */
    public String summarize(String taskId, String actCntlrNm, String material, String userId) {
        String name = StringUtils.hasText(actCntlrNm) ? actCntlrNm.trim() : NAME_ABSENT;
        String text = StringUtils.hasText(material) ? material.trim() : "";

        if (text.isEmpty()) {
            log.warn("[{}] 任务 {} 提取文本为空，不调用大模型", SECTION, taskId);
            return "";
        }
        if (systemPrompt == null) {
            log.error("[{}] 任务 {} 提示词不可用（{}），该段落留空", SECTION, taskId, promptPath);
            return "";
        }
        log.info("[{}] 任务 {} 实控人姓名={}，材料 {} 字", SECTION, taskId, name, text.length());
        return sectionWriter.writeMultiline(SECTION, taskId, systemPrompt, buildUserMessage(name, text), userId);
    }

    /**
     * 拼用户消息：先给实控人姓名（姓名缺失给「未提供」，让模型照提示词的兜底写法输出），
     * 再给原始材料，最后一句写作指令。
     */
    private static String buildUserMessage(String name, String material) {
        return LABEL_NAME + name + '\n'
                + LABEL_MATERIAL + '\n'
                + material + "\n\n"
                + USER_INSTRUCTION;
    }
}
