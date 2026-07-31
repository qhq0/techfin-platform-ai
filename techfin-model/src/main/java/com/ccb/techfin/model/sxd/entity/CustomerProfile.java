package com.ccb.techfin.model.sxd.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;

/**
 * 客户信息表（善新贷材料生成结果表）
 * 映射 sxd_profile，以 cst_id 为主键
 *
 * @author qiuhaoquan
 * @since 2026-07-23
 */
@Data
@TableName("kjjr_ai_sxd_profile")
public class CustomerProfile {

    /** 客户编号（主键） */
    @TableId("cst_id")
    private String cstId;

    /** 客户名称 */
    @TableField("cst_nm")
    private String cstNm;

    /** 成立时间 */
    @TableField("fd_dt")
    private String fdDt;

    /** 法定代表人 */
    @TableField("lgl_rprs_nm")
    private String lglRprsNm;

    /** 实控人姓名 */
    @TableField("act_cntlr_nm")
    private String actCntlrNm;

    /** 注册资本 */
    @TableField("rgst_cpamt")
    private String rgstCpamt;

    /** 实收资本 */
    @TableField("arcptl_cpamt")
    private String arcptlCpamt;

    /** 统一社会信用代码 */
    @TableField("credit_code")
    private String creditCode;

    /** 企业类型 */
    @TableField("CPCT_TPCD")
    private String cpctTpcd;

    /** 企业规模 */
    @TableField("entp_sz_cd")
    private String entpSzCd;

    /** 注册地址 */
    @TableField("dtl_adr")
    private String dtlAdr;

    /** 经营范围 */
    @TableField("org_oprt_scop_dsc")
    private String orgOprtScopDsc;

    /** 所属行业 */
    @TableField("entp_bliy")
    private String entpBliy;

    /** 企业科技资质类型 */
    @TableField("tech_tag")
    private String techTag;

    /** 我行"技术流"评价结果 */
    @TableField("tech_flow")
    private String techFlow;

    /** 我行"科创分"评价结果 */
    @TableField("kc_score")
    private String kcScore;

    /** 企业专利数量 */
    @TableField("ENTP_PTNT_NUM")
    private String entpPtntNum;

    /** 企业实用新型专利数 */
    @TableField("ENTPPRCTNEWTPPTNT_NUM")
    private String entpPrctNewTpPtntNum;

    /** 企业发明专利数 */
    @TableField("ENTP_IVT_PTNT_NUM")
    private String entpIvtPtntNum;

    /** 企业软件著作权数量 */
    @TableField("CLST5YRINNRSWCOPR_NUM")
    private String clst5YrInnRsWcoprNum;

    /** 是否我行贷款客户 */
    @TableField("if_loan")
    private String ifLoan;

    /** 最新贷款品种 */
    @TableField("product_name")
    private String productName;

    /** 贷款金额 */
    @TableField("loan_amount")
    private String loanAmount;

    /** 贷款期限 */
    @TableField("loan_term")
    private String loanTerm;

    /** 贷款当前余额 */
    @TableField("loan_balance")
    private String loanBalance;

    /** 对公存款余额 */
    @TableField("dep_bal")
    private String depBal;

    /** 对公存款余额日期 */
    @TableField("dep_bal_dt")
    private String depBalDt;

    /** 对公存款年日均余额 */
    @TableField("dep_aadbal")
    private String depAadbal;

    /** 开立账户时间 */
    @TableField("acc_start_dt")
    private String accStartDt;

    /** 对公账户类型（基本/一般） */
    @TableField("acc_type")
    private String accType;

    /** 代发人数 */
    @TableField("isug_pnum")
    private String isugPnum;

    /** 月均代发工资 */
    @TableField("avg_12_isug_amt")
    private String avg12IsugAmt;

    /** 近两年是/否存在贷款逾期记录 */
    @TableField("if_yuqi")
    private String ifYuqi;

    /** 是/否存在司法纠纷 */
    @TableField("ltgtrltd_ind")
    private String ltgtrltdInd;

    /** 是/否存在RAD红色预警风险信号 */
    @TableField("if_rad_alarm")
    private String ifRadAlarm;

    /** 管户客户经理编号 */
    @TableField("cst_mngacc_cstmgr_id")
    private String cstMngaccCstmgrId;

    /** 管户支行编号 */
    @TableField("cst_mngacc_inst_supr_insid")
    private String cstMngaccInstSuprInsid;

    /** 数据业务日期 */
    @TableField("data_bsn_dt")
    private LocalDate dataBsnDt;
}
