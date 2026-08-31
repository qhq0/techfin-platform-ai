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

 Date: 01/09/2026 04:02:26
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for kjjr_ai_sxd_extract_data
-- ----------------------------
DROP TABLE IF EXISTS `kjjr_ai_sxd_extract_data`;
CREATE TABLE `kjjr_ai_sxd_extract_data`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `task_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '关联 kjjr_ai_sxd_record.task_id',
  `doc_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '关联 kjjr_ai_sxd_doc.doc_id',
  `table_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '提取表名，如 dib_manage_company_profile',
  `text` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '提取文本内容',
  `created_at` datetime NOT NULL COMMENT '创建时间',
  `updated_at` datetime NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 118 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '提取数据缓存表' ROW_FORMAT = Dynamic;

SET FOREIGN_KEY_CHECKS = 1;
