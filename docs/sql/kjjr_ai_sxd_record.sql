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

 Date: 01/09/2026 04:02:33
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for kjjr_ai_sxd_record
-- ----------------------------
DROP TABLE IF EXISTS `kjjr_ai_sxd_record`;
CREATE TABLE `kjjr_ai_sxd_record`  (
  `task_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '任务 ID，格式 TASK-<32位hex>',
  `credit_code` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '统一社会信用代码',
  `cst_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '客户编号',
  `status` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '0' COMMENT '任务状态：0-未完成 / 1-已完成',
  `act_cntlr_nm` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '实际控制人姓名，用户确认后回填',
  `has_ownership` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '是否有管户权：1-是，0-否',
  `created_at` datetime NOT NULL COMMENT '创建时间',
  `updated_at` datetime NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`task_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '申请记录表' ROW_FORMAT = Dynamic;

SET FOREIGN_KEY_CHECKS = 1;
