package com.ccb.techfin.service.sxd.support;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 商业计划书**提取素材的统一定义**：用哪些缓存表、按什么顺序、每段叫什么名字。
 *
 * <p>2026-10-08 起，{@link BusinessOverviewSummarizer}（企业主营业务情况）与
 * {@link BusinessProfileSummarizer}（客户基本概况四字段）**共用同一份 7 段素材**（用户要求）。
 * 清单放在这里只有一份，避免两处各写一遍、日后改一处忘一处。
 *
 * @author qiuhaoquan
 * @since 2026-10-08
 */
public final class BpExtractSources {

    private BpExtractSources() {
    }

    /**
     * 一条素材来源：缓存表名 + 拼进提示词时用的中文小标题。
     *
     * @param table 缓存表名（同时也是 {@code extractTextMap} 的键）
     * @param label 材料小标题，仅用于给模型区分来源
     */
    public record Source(String table, String label) {
    }

    /**
     * 素材来源清单，<b>顺序即拼进提示词的顺序</b>，也是调用方置空来源键的顺序。
     *
     * <p>⚠️ 顺序决定提示词里「材料一 / 材料二 / …」的编号，别随意重排。
     */
    public static final List<Source> SOURCES = List.of(
            new Source("dib_manage_company_profile", "公司介绍"),
            new Source("dib_manage_business_and_products", "主营业务和产品"),
            new Source("dib_manage_business_circumstance", "经营情况介绍"),
            new Source("dib_manage_progressiveness_description", "研发成果与转化情况"),
            new Source("dib_manage_competitive_advantages", "竞争优势介绍"),
            new Source("dib_manage_development_strategy", "发展战略情况"),
            new Source("dib_manage_y_industry_analysis", "行业发展情况"));

    /** 材料小标题里的中文序号（最多支持 10 段；再多则退回阿拉伯数字）。 */
    private static final String[] CN_NUM =
            { "一", "二", "三", "四", "五", "六", "七", "八", "九", "十" };

    /**
     * 挑出<b>有内容</b>的来源，保持 {@link #SOURCES} 的顺序。
     *
     * <p>空材料不参与拼装：模型对一个空标题容易硬编，所以宁可让它不出现。
     */
    public static List<Source> presentSources(Map<String, String> extractTextMap) {
        List<Source> present = new ArrayList<>();
        for (Source source : SOURCES) {
            String text = extractTextMap == null ? null : extractTextMap.get(source.table());
            if (StringUtils.hasText(text)) {
                present.add(source);
            }
        }
        return present;
    }

    /** 这些来源的正文总字数（已 trim）。 */
    public static int totalLength(List<Source> present, Map<String, String> extractTextMap) {
        int total = 0;
        for (Source source : present) {
            total += extractTextMap.get(source.table()).trim().length();
        }
        return total;
    }

    /** 来源小标题串起来，仅用于日志。 */
    public static String labels(List<Source> present) {
        return present.stream().map(Source::label).collect(Collectors.joining("、"));
    }

    /**
     * 把材料拼成 {@code 【材料X：标题】} 换行 正文 的连续块。
     *
     * <p>编号是"sources 里实际有内容的那几段"的连续序号（不是原始清单序号）——
     * 材料缺一段时，编出来的是「材料一/材料二」而不是「材料一/材料三」，模型读起来更自然。
     */
    public static String buildMaterialBlock(List<Source> present, Map<String, String> extractTextMap) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < present.size(); i++) {
            Source source = present.get(i);
            sb.append("【材料").append(cnNum(i + 1)).append('：').append(source.label()).append("】\n")
                    .append(extractTextMap.get(source.table()).trim())
                    .append("\n\n");
        }
        return sb.toString();
    }

    /** 1 → 一、2 → 二……超出数组长度时退回阿拉伯数字。 */
    public static String cnNum(int n) {
        return (n >= 1 && n <= CN_NUM.length) ? CN_NUM[n - 1] : String.valueOf(n);
    }
}
