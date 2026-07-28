package com.ccb.techfin.service.sxd.util;

import org.apache.poi.xwpf.usermodel.*;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * 初始化 Word 报告模板，写入占位符。
 * 运行此 main 方法生成 {@code techfin-controller/src/main/resources/templates/sxd/report-template.docx}。
 * <p>
 * 占位符规则：
 * <ul>
 *   <li>{@code {{字段名}}} — 客户基本信息字段（如 {@code {{cst_nm}}}），由代码替换为实际值</li>
 *   <li>{@code {{dib_manage_*}}} — 商业计划书提取文本，由代码替换为缓存表读取的文本</li>
 *   <li>{@code {{balance_sheet_key_items}}} — 替换为资产负债表表格</li>
 *   <li>{@code {{profit_sheet_key_items}}} — 替换为利润表表格</li>
 * </ul>
 * 用户可在此模板基础上调整格式、布局，只要保留占位符即可。
 *
 * @author qiuhaoquan
 * @since 2026-07-23
 */
public class TemplateInitializer {

    /** 客户基本信息字段占位符列表（按展示顺序） */
    private static final List<String> PROFILE_PLACEHOLDERS = Arrays.asList(
            "cst_nm",       // 客户名称
            "credit_code",  // 统一社会信用代码
            "fd_dt",        // 成立时间
            "lgl_rprs_nm",  // 法定代表人
            "act_cntlr_nm", // 实控人姓名
            "rgst_cpamt",   // 注册资本
            "arcptl_cpamt", // 实收资本
            "cpct_tpcd",    // 企业类型
            "entp_sz_cd",   // 企业规模
            "entp_bliy",    // 所属行业
            "dtl_adr",      // 注册地址
            "org_oprt_scop_dsc", // 经营范围
            "tech_tag",      // 企业科技资质类型
            "tech_flow",     // 技术流评价结果
            "kc_score",      // 科创分评价结果
            "entp_ptnt_num", // 企业专利数量
            "entp_prct_new_tp_ptnt_num", // 实用新型专利数
            "entp_ivt_ptnt_num", // 发明专利数
            "clst_5yr_inn_rs_wcopr_num", // 软件著作权数量
            "if_loan",       // 是否我行贷款客户
            "product_name",  // 最新贷款品种
            "loan_amount",   // 贷款金额
            "loan_term",     // 贷款期限
            "loan_balance",  // 贷款当前余额
            "dep_bal",       // 对公存款余额
            "dep_bal_dt",    // 对公存款余额日期
            "dep_aadbal",    // 对公存款年日均余额
            "acc_start_dt",  // 开立账户时间
            "acc_type",      // 对公账户类型
            "isug_pnum",     // 代发人数
            "avg_12_isug_amt", // 月均代发工资
            "if_yuqi",       // 近两年存在贷款逾期
            "ltgtrltd_ind",  // 存在司法纠纷
            "if_rad_alarm"   // 存在RAD红色预警
    );

    /** 商业计划书提取文本占位符列表（按展示顺序） */
    private static final List<String> BUSINESS_PLAN_PLACEHOLDERS = Arrays.asList(
            "dib_director_keyresume",                // 实际控制人及团队简介
            "dib_manage_business_and_products",      // 主营业务和产品
            "dib_manage_business_circumstance"        // 经营情况介绍
    );

    public static void main(String[] args) throws IOException {
        String outputPath = args.length > 0 ? args[0]
                : "techfin-controller/src/main/resources/templates/sxd/report-template.docx";

        try (XWPFDocument doc = new XWPFDocument()) {
            // 标题
            XWPFParagraph title = doc.createParagraph();
            title.setAlignment(ParagraphAlignment.CENTER);
            XWPFRun titleRun = title.createRun();
            titleRun.setText("善新贷客户关键信息调查报告");
            titleRun.setBold(true);
            titleRun.setFontSize(22);
            titleRun.setFontFamily("微软雅黑");

            doc.createParagraph();

            // ========== 一、企业基本信息占位符 ==========
            XWPFParagraph section1 = doc.createParagraph();
            XWPFRun s1Run = section1.createRun();
            s1Run.setText("一、企业基本信息");
            s1Run.setBold(true);
            s1Run.setFontSize(14);
            s1Run.setFontFamily("微软雅黑");
            s1Run.setColor("1F4E79");

            doc.createParagraph();

            // 每个字段一个占位符段落
            for (String field : PROFILE_PLACEHOLDERS) {
                XWPFParagraph p = doc.createParagraph();
                XWPFRun r = p.createRun();
                r.setText("{{" + field + "}}");
                r.setFontSize(10);
                r.setFontFamily("微软雅黑");
            }

            doc.createParagraph();

            // ========== 二、商业计划书提取文本 ==========
            XWPFParagraph section2 = doc.createParagraph();
            XWPFRun s2Run = section2.createRun();
            s2Run.setText("二、商业计划书提取文本");
            s2Run.setBold(true);
            s2Run.setFontSize(14);
            s2Run.setFontFamily("微软雅黑");
            s2Run.setColor("1F4E79");

            doc.createParagraph();

            // 每个 tableName 一个占位符段落
            for (String tableName : BUSINESS_PLAN_PLACEHOLDERS) {
                XWPFParagraph p = doc.createParagraph();
                XWPFRun r = p.createRun();
                r.setText("{{" + tableName + "}}");
                r.setFontSize(10);
                r.setFontFamily("微软雅黑");
            }

            doc.createParagraph();

            // ========== 三、资产负债表关键科目 ==========
            XWPFParagraph section3 = doc.createParagraph();
            XWPFRun s3Run = section3.createRun();
            s3Run.setText("三、资产负债表关键科目");
            s3Run.setBold(true);
            s3Run.setFontSize(14);
            s3Run.setFontFamily("微软雅黑");
            s3Run.setColor("1F4E79");

            doc.createParagraph();

            XWPFParagraph bsPlaceholder = doc.createParagraph();
            XWPFRun bsRun = bsPlaceholder.createRun();
            bsRun.setText("{{balance_sheet_key_items}}");
            bsRun.setFontSize(10);
            bsRun.setFontFamily("微软雅黑");

            doc.createParagraph();

            // ========== 四、利润表关键科目 ==========
            XWPFParagraph section4 = doc.createParagraph();
            XWPFRun s4Run = section4.createRun();
            s4Run.setText("四、利润表关键科目");
            s4Run.setBold(true);
            s4Run.setFontSize(14);
            s4Run.setFontFamily("微软雅黑");
            s4Run.setColor("1F4E79");

            doc.createParagraph();

            XWPFParagraph psPlaceholder = doc.createParagraph();
            XWPFRun psRun = psPlaceholder.createRun();
            psRun.setText("{{profit_sheet_key_items}}");
            psRun.setFontSize(10);
            psRun.setFontFamily("微软雅黑");

            try (FileOutputStream out = new FileOutputStream(outputPath)) {
                doc.write(out);
            }
        }

        System.out.println("Template initialized with placeholders: " + outputPath);
    }
}
