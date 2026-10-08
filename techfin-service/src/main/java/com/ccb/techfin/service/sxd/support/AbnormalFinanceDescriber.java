package com.ccb.techfin.service.sxd.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 「异常财务数据说明」段落生成器。
 *
 * <p>把报表里<b>增长率绝对值超过 30%</b> 的关键科目（含资产负债表、利润表两张表）交给大模型，
 * 让它把"科目名称 + 各年数值 + 增长率变化"写成一段文字，落到报告模板的
 * {@code {{abnormal_finance_description}}} 占位符上。
 *
 * <p><b>筛选在 Java 侧完成，模型只负责组织语言</b>：阈值判断、逐期数值、增长率都取自
 * 报表同一套计算函数，保证说明里的数字与表格单元格逐字一致。交给模型做数值筛选会漏项、
 * 会算错，还会出现"表格里增长率 45%、说明里却没有这个科目"的自相矛盾。所以本类拿到的
 * {@code material} 已经是筛好的清单，它只调模型成文。
 *
 * <p><b>清单为空时不调用大模型</b>：没有超阈值的科目就没什么可说明的，白调一次只会让模型
 * 硬编内容。
 *
 * <p>调用与失败降级的公共逻辑见 {@link LlmSectionWriter}：任何失败（提示词不可用 / 调用异常 /
 * 返回空内容）一律让该段落<b>留空</b>并打 ERROR 日志，绝不中断报告生成。
 *
 * @author qiuhaoquan
 * @since 2026-10-08
 */
@Slf4j
@Component
public class AbnormalFinanceDescriber {

    /** 段落名，日志前缀与占位符语义共用。 */
    private static final String SECTION = "异常财务数据说明";

    /** 附在材料末尾的收尾指令，与提示词文件里的写作要求配合。 */
    private static final String USER_INSTRUCTION =
            "请依据上述清单，按写作要求撰写「异常财务数据说明」段落。";

    private final LlmSectionWriter sectionWriter;

    /** 提示词文件路径，仅用于日志与排障。 */
    private final String promptPath;

    /** 系统提示词（写作要求）正文；为 null 表示提示词不可用，整体走降级。 */
    private final String systemPrompt;

    public AbnormalFinanceDescriber(
            LlmSectionWriter sectionWriter,
            @Value("${report.abnormal-finance-prompt-path:classpath:templates/sxd/abnormal-finance-prompt.txt}")
            String promptPath) {
        this.sectionWriter = sectionWriter;
        this.promptPath = promptPath;
        // 启动时读一次并缓存：提示词是构建期产物，不会在运行中变化，没必要每个请求都读一遍文件。
        this.systemPrompt = sectionWriter.loadPrompt(SECTION, promptPath);
    }

    /**
     * 依据已筛好的异常科目清单，生成「异常财务数据说明」段落。
     *
     * @param taskId   任务 ID，仅用于日志
     * @param material 异常科目清单，一行一个科目（由报表侧按 30% 阈值筛出并格式化），可空
     * @param userId   调用方账号，透传给大模型网关（为空则不发身份头），可空
     * @return 说明文字，一个科目一行；任何失败（提示词不可用 / 调用异常 / 返回空内容）或清单为空时，
     * 一律返回<b>空字符串</b>，占位符随之被清空
     */
    public String describe(String taskId, String material, String userId) {
        if (!StringUtils.hasText(material)) {
            log.warn("[{}] 任务 {} 没有增长率超阈值的科目，不调用大模型", SECTION, taskId);
            return "";
        }
        if (systemPrompt == null) {
            log.error("[{}] 任务 {} 提示词不可用（{}），该段落留空", SECTION, taskId, promptPath);
            return "";
        }
        return sectionWriter.writeMultiline(SECTION, taskId, systemPrompt,
                material.trim() + "\n\n" + USER_INSTRUCTION, userId);
    }
}
