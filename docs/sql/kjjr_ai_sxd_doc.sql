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

 Date: 01/09/2026 04:02:21
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for kjjr_ai_sxd_doc
-- ----------------------------
DROP TABLE IF EXISTS `kjjr_ai_sxd_doc`;
CREATE TABLE `kjjr_ai_sxd_doc`  (
  `doc_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '资料批量新增返回的文档 ID',
  `task_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '关联 kjjr_ai_sxd_record.task_id',
  `business_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '业务类型（docTypeId 值），由 financeFiles/businessFile 分类从配置 api.doc-type.* 获取',
  `report_date` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '财报报告日期（仅 finance 类型有值）',
  PRIMARY KEY (`doc_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '文档明细表' ROW_FORMAT = Dynamic;

SET FOREIGN_KEY_CHECKS = 1;
