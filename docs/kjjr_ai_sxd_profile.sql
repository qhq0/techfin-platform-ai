/*
 Navicat Premium Data Transfer

 Source Server         : wsl_mysql
 Source Server Type    : MySQL
 Source Server Version : 80046 (8.0.46-0ubuntu0.24.04.3)
 Source Host           : localhost:3306
 Source Schema         : mydb

 Target Server Type    : MySQL
 Target Server Version : 80046 (8.0.46-0ubuntu0.24.04.3)
 File Encoding         : 65001

 Date: 01/09/2026 04:03:49
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for kjjr_ai_sxd_profile
-- ----------------------------
DROP TABLE IF EXISTS `kjjr_ai_sxd_profile`;
CREATE TABLE `kjjr_ai_sxd_profile`  (
  `data_bsn_dt` date NOT NULL COMMENT '数据业务日期',
  `cst_id` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '客户编号',
  `cst_nm` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '客户名称',
  `fd_dt` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '成立时间',
  `lgl_rprs_nm` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '法定代表人',
  `act_cntlr_nm` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '实控人姓名',
  `rgst_cpamt` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '注册资本',
  `arcptl_cpamt` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '实收资本',
  `credit_code` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '统一社会信用代码',
  `CPCT_TPCD` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '企业类型',
  `entp_sz_cd` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '企业规模',
  `dtl_adr` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '注册地址',
  `org_oprt_scop_dsc` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '经营范围',
  `entp_bliy` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '所属行业',
  `tech_tag` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '企业科技资质类型',
  `tech_flow` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '我行\"技术流\"评价结果',
  `kc_score` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '我行\"科创分\"评价结果',
  `ENTP_PTNT_NUM` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '企业专利数量',
  `ENTPPRCTNEWTPPTNT_NUM` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '企业实用新型专利数',
  `ENTP_IVT_PTNT_NUM` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '企业发明专利数',
  `CLST5YRINNRSWCOPR_NUM` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '企业软件著作权数量',
  `if_loan` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '是否我行贷款客户（0：否，1：是）',
  `product_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '最新贷款品种',
  `loan_amount` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '贷款金额',
  `loan_term` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '贷款期限',
  `loan_balance` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '贷款当前余额',
  `dep_bal` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '对公存款余额',
  `dep_bal_dt` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '对公存款余额日期',
  `dep_aadbal` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '对公存款年日均余额',
  `acc_start_dt` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '开立账户时间',
  `acc_type` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '对公账户类型（基本/一般）',
  `isug_pnum` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '代发人数',
  `avg_12_isug_amt` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '月均代发工资',
  `if_yuqi` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '近两年是/否存在贷款逾期记录（0：否，1：是）',
  `ltgtrltd_ind` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '是/否存在司法纠纷（0：否，1：是）',
  `if_rad_alarm` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '是/否存在RAD红色预警风险信号（0：否，1：是）',
  `cst_mngacc_cstmgr_id` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '管户客户经理编号',
  `cst_mngacc_inst_supr_insid` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '管户支行编号',
  `byzd1` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '备用字段1',
  `byzd2` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '备用字段2',
  `byzd3` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '备用字段3',
  `byzd4` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '备用字段4',
  `byzd5` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '备用字段5',
  `byzd6` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '备用字段6',
  `byzd7` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '备用字段7',
  `byzd8` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '备用字段8',
  `byzd9` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '备用字段9',
  `byzd10` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '备用字段10',
  PRIMARY KEY (`cst_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '善新贷材料生成结果表' ROW_FORMAT = Dynamic;

SET FOREIGN_KEY_CHECKS = 1;
