package com.ccb.techfin.model.external;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 财务报表提取数据查询（queryData）的 data 数组中单条记录。
 * 同时用于资产负债表和利润表（两者返回结构一致，仅 tableName 参数不同）。
 *
 * queryData 接口（POST /api/extract/open/do
 * c/queryData）返回的 data 字段为 snake_case
 * （如 item_standard、current_amount、report_date），故显式标注 SnakeCaseStrategy 以匹配。
 *
 * @author qiuhaoquan
 * @since 2026-07-23
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public class FinanceRecord {

    /** 标准科目名称，如 营业总收入、营业成本（用于精确匹配） */
    private String itemStandard;

    /** 科目名称，如 货币资金 */
    private String item;

    /** 本期金额 */
    private BigDecimal currentAmount;

    /** 上期金额 */
    private BigDecimal lastAmount;

    /** 报表日期，如 2025-06-30 */
    private String reportDate;
}
