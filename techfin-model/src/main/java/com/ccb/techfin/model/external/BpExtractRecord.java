package com.ccb.techfin.model.external;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

/**
 * 商业计划书提取数据查询（POST /api/extract/open/doc/bpQueryData）的 data 数组中单条记录。
 * 不同 tableName 返回不同的文本字段，此处汇总所有可能字段。
 *
 * 该接口返回的 data 字段为 snake_case（如 company_profile_text、strategy_text），
 * 故显式标注 SnakeCaseStrategy 以匹配。
 *
 * @author qiuhaoquan
 * @since 2026-07-23
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public class

BpExtractRecord {

    /** dib_manage_company_profile */
    private String companyProfileText;

    /** dib_director_keyresume — 人员简历 */
    private String resume;

    /** dib_director_keyresume — 人员姓名 */
    private String name;

    /** dib_director_keyresume — 人员职位 */
    private String position;

    /** dib_manage_business_and_products */
    private String businessAndProductsText;

    /** dib_manage_business_circumstance / dib_company_qualification / dib_manage_y_industry_analysis */
    private String text;

    /** dib_manage_progressiveness_description */
    private String progressivenessText;

    /** dib_manage_competitive_advantages */
    private String competitivenessText;

    /** dib_manage_development_strategy */
    private String strategyText;
}