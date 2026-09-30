package com.ccb.techfin.model.external;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

/**
 * 审计报告附注查询（queryData — dib_intervening_y_auditreport_jh）的 data 数组中单条记录。
 * 用于获取"财务报表口径"等附注信息项。
 *
 * queryData 接口返回的 data 字段为 snake_case（如 item_value、report_date），
 * 故显式标注 SnakeCaseStrategy 以匹配。
 *
 * @author qiuhaoquan
 * @since 2026-07-23
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AuditReportItem {

    /** 项目名称，如 财务报表口径、财务报表截止日期 */
    private String item;

    /** 项目值，如 财务报表口径 → "1"(单一) / "2"(合并) */
    private String itemValue;
}
