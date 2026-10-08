package com.ccb.techfin.service.sxd.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 「客户基本概况」各字段的生成器：主营业务概况 / 下游主要服务 / 企业亮点描述 /
 * 专利技术应用情况及创投投资情况。
 *
 * <p>素材与「企业主营业务情况」（{@link BusinessOverviewSummarizer}）完全相同 —— 都是
 * {@link BpExtractSources#SOURCES} 列出的 <b>7 段</b>提取文本（公司介绍、主营业务和产品、
 * 经营情况介绍、研发成果与转化情况、竞争优势介绍、发展战略情况、行业发展情况；2026-10-08 由 2 段扩到 7 段）。
 * 区别只在<b>用途与篇幅</b>：那边是报告正文里的一大段详述，这边是「一、企业基本信息」表格里的几个精简字段，
 * 因此单独出一份提示词、单独一次调用。
 *
 * <p>扩到 7 段对这些字段的收益：企业亮点描述多出「研发成果 / 竞争优势 / 公司介绍」等素材，
 * 下游客户企业名也更可能出现在「竞争优势介绍」里，专利应用情况与创投投资情况同样主要落在
 * 「研发成果与转化情况」「竞争优势介绍」「公司介绍」这几段。
 *
 * <p><b>一次调用出五行</b>（用户 2026-10-09 定：新字段并入同一次调用）：素材同源，拆成多次调用只会让
 * 报告接口多等几轮网络往返、且把同一份材料重复发给模型。提示词要求模型按
 * {@code MAIN#}/{@code DOWN#}/{@code HIGH#}/{@code APPLY#}/{@code INVEST#} 五个标签逐行输出，
 * Java 侧再按标签切开、补齐句首引导词，落到四个占位符上：
 * <ul>
 *   <li>{@code {{profile_main_business}}} ← {@code MAIN#}</li>
 *   <li>{@code {{profile_downstream}}} ← {@code DOWN#}</li>
 *   <li>{@code {{profile_highlights}}} ← {@code HIGH#}</li>
 *   <li>{@code {{profile_patent_application}}} ← {@code APPLY#} + {@code INVEST#} <b>两句拼一句</b>
 *       （见 {@link #buildPatentApplication}）</li>
 * </ul>
 *
 * <p><b>解析失败不报错</b>：某一行缺失、标签被改写、整行留空，都只让<b>那一个</b>字段回落成
 * 模板句式（见 {@link #DEFAULT_MAIN_BUSINESS} / {@link #DEFAULT_DOWNSTREAM} /
 * {@link #DEFAULT_HIGHLIGHTS} / {@link #DEFAULT_PATENT_APPLY} / {@link #DEFAULT_PATENT_INVEST}），
 * 不影响其余字段。五行都空时才打 WARN。
 *
 * <p><b>特殊情况回落成模板句式，而不是留空</b>（用户 2026-10-08 定）：材料为空、提示词不可用、
 * 调用失败、模型某行没写出内容 —— 一律把该字段填成「主营业务为 XX」这类<b>留了空格的模板句式</b>，
 * 让报告里始终看得见"这里原本该写什么、由人工补 XX"，而不是留一个不知所云的空段。
 * 注意 {@link LlmSectionWriter} 的"失败留空"仍然成立，只是这里在它之后再兜一层默认值。
 *
 * <p><b>句末标点的两套规则</b>（用户 2026-10-08 / 10-09 定）：
 * <ul>
 *   <li>前三个字段（主营业务概况 / 下游主要服务 / 企业亮点描述）填进报告的文本
 *       <b>一律不以标点结尾</b> —— 标点交给模板排版决定；</li>
 *   <li>专利技术应用情况那个字段<b>保留句末「。」</b>，且两句各带一个。这一条不靠模型自觉：
 *       {@link #buildPatentApplication} 先把模型给的两段各自剥净句末标点，再统一补上「。」，
 *       所以既不会出现「。。」，也不会漏掉句号。</li>
 * </ul>
 *
 * <p>调用与失败降级的公共逻辑见 {@link LlmSectionWriter}；这里走 {@code writeMultiline} 保留换行。
 *
 * @author qiuhaoquan
 * @since 2026-10-08
 */
@Slf4j
@Component
public class BusinessProfileSummarizer {

    /** 段落名，日志前缀用。 */
    private static final String SECTION = "客户基本概况";

    /** 占位符 1：主营业务概况（报告模板里的 {@code {{profile_main_business}}}）。 */
    public static final String PLACEHOLDER_MAIN_BUSINESS = "profile_main_business";

    /** 占位符 2：下游主要服务（报告模板里的 {@code {{profile_downstream}}}）。 */
    public static final String PLACEHOLDER_DOWNSTREAM = "profile_downstream";

    /** 占位符 3：企业亮点描述（报告模板里的 {@code {{profile_highlights}}}）。 */
    public static final String PLACEHOLDER_HIGHLIGHTS = "profile_highlights";

    /**
     * 占位符 4：专利技术在生产中的应用情况 + 创投投资情况
     * （报告模板里的 {@code {{profile_patent_application}}}）。
     *
     * <p>2026-10-09 新增。模板句式：{@code 主要应用情况：XXX。是/否有创投机构投资，创投机构名称为XXX。}
     * —— 由 {@code APPLY#}、{@code INVEST#} 两行拼成，详见 {@link #buildPatentApplication}。
     */
    public static final String PLACEHOLDER_PATENT_APPLICATION = "profile_patent_application";

    /**
     * 兜底句式 1：主营业务概况。生成不出来（材料空 / 调用失败 / 模型没写）时用这句顶上，
     * 把「XX」留成人工补写的空格。
     *
     * <p>⚠️ 这三条兜底句式<b>句末一律不带标点</b>（用户 2026-10-08 定）：占位符在模板里是裸的，
     * 标点由模板排版决定，不由这里带。
     */
    public static final String DEFAULT_MAIN_BUSINESS = "主营业务为 XX";

    /** 兜底句式 2：下游主要服务。语义同上（XX 同样是留给人工的下游客户企业名），句末不带标点。 */
    public static final String DEFAULT_DOWNSTREAM = "下游主要服务 XX 等行业头部企业";

    /** 兜底句式 3：企业亮点描述。这一句本身就带「如有」说明，天然适合当"没有亮点"时的占位；句末不带标点。 */
    public static final String DEFAULT_HIGHLIGHTS =
            "企业亮点描述（如有，包括但不限于创投投资、人才、技术国产替代等情况）";

    /**
     * 兜底句式 4a：专利技术应用情况那一句（不含句号，句号由 {@link #buildPatentApplication} 统一补）。
     * 材料里没有可用的应用描述时，留「XXX」给人工补。
     */
    public static final String DEFAULT_PATENT_APPLY = "主要应用情况：XXX";

    /**
     * 兜底句式 4b：创投投资结论那一句（不含句号）。
     *
     * <p>注意这里<b>刻意保留「是/否」</b>：材料里既没写有投资方、也没写没有投资方时，模型无从判断，
     * 与其让它猜，不如把选择题原样留给人工勾选 —— 与「XX」系列兜底句式一个思路。
     */
    public static final String DEFAULT_PATENT_INVEST = "是/否有创投机构投资，创投机构名称为XXX";

    private static final String USER_INSTRUCTION = "请依据上述材料，按要求输出五行字段。";

    private final LlmSectionWriter sectionWriter;

    /** 提示词文件路径，仅用于日志与排障。 */
    private final String promptPath;

    /** 系统提示词（写作要求）正文；为 null 表示提示词不可用，整体走降级。 */
    private final String systemPrompt;

    public BusinessProfileSummarizer(
            LlmSectionWriter sectionWriter,
            @Value("${report.business-profile-prompt-path:classpath:templates/sxd/business-profile-prompt.txt}")
            String promptPath) {
        this.sectionWriter = sectionWriter;
        this.promptPath = promptPath;
        // 启动时读一次并缓存：提示词是构建期产物，不会在运行中变化，没必要每个请求都读一遍文件。
        this.systemPrompt = sectionWriter.loadPrompt(SECTION, promptPath);
    }

    /**
     * 依据 BP 各段提取文本生成「客户基本概况」的四个字段。
     *
     * @param taskId         任务 ID，仅用于日志
     * @param extractTextMap 缓存表名 → 提取文本（由 {@code loadExtractDataFromCache} 装配），可空
     * @param userId         调用方账号，透传给大模型网关（为空则不发身份头），可空
     * @return 占位符名 → 内容。<b>四个键恒定存在，且值不会为空</b> —— 生成不出来的字段回落成
     * 模板句式（{@link #DEFAULT_MAIN_BUSINESS} 等）。替换是"按键查表"，键缺失会让模板里的
     * {@code {{...}}} 以字面量原样留在报告中，所以宁可给兜底句子也不能缺键。
     */
    public Map<String, String> summarize(String taskId, Map<String, String> extractTextMap, String userId) {
        List<BpExtractSources.Source> present = BpExtractSources.presentSources(extractTextMap);

        if (present.isEmpty()) {
            log.warn("[{}] 任务 {} 的 {} 段素材全部为空，不调用大模型，各字段回落模板句式",
                    SECTION, taskId, BpExtractSources.SOURCES.size());
            return defaults();
        }
        if (systemPrompt == null) {
            log.error("[{}] 任务 {} 提示词不可用（{}），各字段回落模板句式", SECTION, taskId, promptPath);
            return defaults();
        }
        log.info("[{}] 任务 {} 入参 {} 段共 {} 字（{}）", SECTION, taskId, present.size(),
                BpExtractSources.totalLength(present, extractTextMap), BpExtractSources.labels(present));

        String raw = sectionWriter.writeMultiline(SECTION, taskId, systemPrompt,
                buildUserMessage(present, extractTextMap), userId);
        Map<String, String> parsed = parse(raw);
        if (isAllBlank(parsed)) {
            log.warn("[{}] 任务 {} 五行字段解析后均为空（原始输出 {} 字），回落模板句式",
                    SECTION, taskId, raw == null ? 0 : raw.length());
        }
        return fillDefaults(parsed);
    }

    /** 解析结果的暂存键（只在本类内部流转，不会落进 {@code extractTextMap}）。 */
    private static final String PARSED_APPLY = "parsed_patent_apply";
    private static final String PARSED_INVEST = "parsed_patent_invest";

    /** 各字段的值是否都为空（判断用原始解析结果，此时兜底句式还没填上去）。 */
    private static boolean isAllBlank(Map<String, String> parsed) {
        return !StringUtils.hasText(parsed.get(PLACEHOLDER_MAIN_BUSINESS))
                && !StringUtils.hasText(parsed.get(PLACEHOLDER_DOWNSTREAM))
                && !StringUtils.hasText(parsed.get(PLACEHOLDER_HIGHLIGHTS))
                && !StringUtils.hasText(parsed.get(PARSED_APPLY))
                && !StringUtils.hasText(parsed.get(PARSED_INVEST));
    }

    /**
     * 收口：空白的字段换成对应兜底句式，前三个字段再统一剥掉句末标点。
     *
     * <p>放在 {@link #isAllBlank} 判定<b>之后</b>执行：先按"模型到底有没有产出"打日志，
     * 再兜底，否则日志永远看不到"五行全空"这个信号。
     *
     * <p>⚠️ <b>句末标点两套规则</b>（见类注释）：前三个字段不带标点；专利技术应用情况那个字段
     * 由 {@link #buildPatentApplication} 统一补「。」，所以它<b>不走</b> {@link #finish}。
     */
    private static Map<String, String> fillDefaults(Map<String, String> parsed) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put(PLACEHOLDER_MAIN_BUSINESS,
                finish(parsed.get(PLACEHOLDER_MAIN_BUSINESS), DEFAULT_MAIN_BUSINESS));
        map.put(PLACEHOLDER_DOWNSTREAM,
                finish(parsed.get(PLACEHOLDER_DOWNSTREAM), DEFAULT_DOWNSTREAM));
        map.put(PLACEHOLDER_HIGHLIGHTS,
                finish(parsed.get(PLACEHOLDER_HIGHLIGHTS), DEFAULT_HIGHLIGHTS));
        map.put(PLACEHOLDER_PATENT_APPLICATION,
                buildPatentApplication(parsed.get(PARSED_APPLY), parsed.get(PARSED_INVEST)));
        return map;
    }

    /** 空值换兜底句式，再统一剥掉句末标点。 */
    private static String finish(String value, String fallback) {
        return stripTrailingPunct(blankTo(value, fallback));
    }

    /**
     * 把 {@code APPLY#}、{@code INVEST#} 两行拼成 {@code {{profile_patent_application}}} 的最终文本：
     * {@code 主要应用情况：{应用情况}。{创投结论}。}
     *
     * <p><b>为什么标点由这里补、而不是让模型写</b>（用户 2026-10-09 定"保留句末句号"）：
     * 模型时而写句号、时而不写，靠提示词约束不可靠。这里先把两段各自剥净句末标点，再统一补一个「。」，
     * 结果就是"两句各带一个句号、绝不多也绝不少"，与模型听不听话无关。
     *
     * <p>两段各自独立兜底：应用情况缺失时只把那一句换成 {@link #DEFAULT_PATENT_APPLY}，
     * 不影响创投那一句；反之亦然。两段都空时结果即为用户给的模板句式
     * {@code 主要应用情况：XXX。是/否有创投机构投资，创投机构名称为XXX。}
     */
    private static String buildPatentApplication(String apply, String invest) {
        String applyPart = stripTrailingPunct(blankTo(apply, DEFAULT_PATENT_APPLY));
        String investPart = stripTrailingPunct(blankTo(invest, DEFAULT_PATENT_INVEST));
        return applyPart + "。" + investPart + "。";
    }

    private static String blankTo(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    /**
     * 句末标点：中文句号/顿号/逗号/分号/冒号/感叹号/问号/省略号（含中英半角与空白）。
     *
     * <p>⚠️ 刻意<b>不含</b>右括号类（`）`/`】`/`」`）—— 兜底句式 3 正是以「）」收尾，要保留。
     * 只剥"结尾处的纯标点"，正文中间的标点（如「企业亮点描述：」的那个冒号）不动。
     */
    private static final Pattern TRAILING_PUNCT =
            Pattern.compile("[。．.！!？?；;，,、：:…~～　\\s]+$");

    private static String stripTrailingPunct(String s) {
        return s == null ? null : TRAILING_PUNCT.matcher(s).replaceFirst("");
    }

    /**
     * 行标签。提示词要求模型按这五个标签逐行输出。
     *
     * <p>⚠️ <b>标签刻意与正文零重叠</b>：前三个字段的"字段名"（下游主要服务、企业亮点描述）恰好
     * 也是各自句子的开头词，若拿字段名当标签，就分不清"标签"和"正文"—— 实测出现过模型输出
     * {@code 下游主要服务：家电等行业头部企业。}，剥掉标签会把正文开头的「下游主要服务」一起吃掉。
     * 换成 {@code MAIN#}/{@code DOWN#}/{@code HIGH#} 后语义唯一。
     *
     * <p>2026-10-09 追加 {@code APPLY#}（主要应用情况）、{@code INVEST#}（创投投资结论）。
     * 注意 {@code APPLY#} 的正文<b>允许</b>自带「主要应用情况：」引导词（缺了 Java 会补），
     * 而 {@code INVEST#} 的正文以「有/无」开头，没有固定引导词。
     */
    private static final Pattern TAG_ANY =
            Pattern.compile("(?i)^(MAIN|DOWN|HIGH|APPLY|INVEST)\\s*#?\\s*[:：]?\\s*");
    private static final Pattern TAG_MAIN = Pattern.compile("(?i)^MAIN\\b");
    private static final Pattern TAG_DOWN = Pattern.compile("(?i)^DOWN\\b");
    private static final Pattern TAG_HIGH = Pattern.compile("(?i)^HIGH\\b");
    private static final Pattern TAG_APPLY = Pattern.compile("(?i)^APPLY\\b");
    private static final Pattern TAG_INVEST = Pattern.compile("(?i)^INVEST\\b");

    /** 各段正文的句首引导词，用于"缺引导词就补齐"。 */
    private static final String LEAD_MAIN = "主营业务为";
    private static final String LEAD_DOWN = "下游主要服务";
    private static final String LEAD_HIGH = "企业亮点描述：";
    private static final String LEAD_APPLY = "主要应用情况：";

    /**
     * 按行标签切分模型输出，并对每段做一次"引导词补齐"。
     *
     * <p><b>宽容解析 + 确定性补齐，而不是严格失败</b>：模型偶尔会把标签后的冒号写成半角、
     * 或把"字段名"和"句首引导词"混为一谈（只写「家电等行业头部企业。」而漏了「下游主要服务」）。
     * 这类小偏差只该影响那一行，且行内容本身是有价值的 —— 所以这里按标签取值、按引导词补齐，
     * 而不是把整段作废。真正带不上标签的行才忽略。
     *
     * <p>补齐只会发生在"缺引导词"时，已经带引导词的原文一字不动。
     *
     * <p>返回的是<b>暂存 map</b>：前三个键就是最终占位符名，专利那两句用 {@link #PARSED_APPLY} /
     * {@link #PARSED_INVEST} 暂存，等 {@link #fillDefaults} 拼成一句后才换成正式占位符名。
     */
    private static Map<String, String> parse(String raw) {
        String mainBusiness = "";
        String downstream = "";
        String highlights = "";
        String apply = "";
        String invest = "";
        for (String line : (raw == null ? "" : raw).split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (TAG_MAIN.matcher(trimmed).find()) {
                mainBusiness = afterTag(trimmed);
            } else if (TAG_DOWN.matcher(trimmed).find()) {
                downstream = afterTag(trimmed);
            } else if (TAG_HIGH.matcher(trimmed).find()) {
                highlights = afterTag(trimmed);
            } else if (TAG_APPLY.matcher(trimmed).find()) {
                apply = afterTag(trimmed);
            } else if (TAG_INVEST.matcher(trimmed).find()) {
                // 创投结论以「有/无」开头，没有固定引导词，原样取用。
                invest = afterTag(trimmed);
            }
        }
        Map<String, String> map = new LinkedHashMap<>();
        map.put(PLACEHOLDER_MAIN_BUSINESS, withLead(mainBusiness, "主营业务", LEAD_MAIN));
        map.put(PLACEHOLDER_DOWNSTREAM, withLead(downstream, LEAD_DOWN, LEAD_DOWN));
        map.put(PLACEHOLDER_HIGHLIGHTS, withLead(highlights, "企业亮点描述", LEAD_HIGH));
        map.put(PARSED_APPLY, withLead(apply, "主要应用情况", LEAD_APPLY));
        map.put(PARSED_INVEST, invest);
        return map;
    }

    /** 剥掉行首的 MAIN#/DOWN#/HIGH#/APPLY#/INVEST# 标签（含其后可能出现的冒号与空白）。 */
    private static String afterTag(String line) {
        return TAG_ANY.matcher(line).replaceFirst("").trim();
    }

    /**
     * 保证正文带上句首引导词。已经带上就原样返回；只写了引导词而没写内容（或内容为空）时返回空串，
     * 表示"这一行没产出" —— 空串随后会被 {@link #fillDefaults} 换成对应的模板句式，
     * 避免在报告里留一句只有引导词的半截话。
     *
     * @param value 模型给出的正文（已剥标签）
     * @param word  引导词的核心部分（用于识别"有引导词但缺冒号"）
     * @param lead  完整引导词（含应有的冒号）
     */
    private static String withLead(String value, String word, String lead) {
        if (value.isEmpty()) {
            return value;
        }
        if (value.startsWith(lead)) {
            return value;
        }
        if (value.startsWith(word)) {
            String rest = value.substring(word.length()).trim();
            if (rest.startsWith("：") || rest.startsWith(":")) {
                rest = rest.substring(1).trim();
            }
            return rest.isEmpty() ? "" : lead + rest;
        }
        return lead + value;
    }

    /** 压根没走到模型这一步时的返回值：四个键都在，值全是模板句式（供人工补 XX）。 */
    private static Map<String, String> defaults() {
        return fillDefaults(new LinkedHashMap<>());
    }

    /** 拼用户消息：各段材料带小标题（只拼有内容的），尾部接一句总指令。 */
    private static String buildUserMessage(List<BpExtractSources.Source> present,
                                           Map<String, String> extractTextMap) {
        return BpExtractSources.buildMaterialBlock(present, extractTextMap) + USER_INSTRUCTION;
    }
}
