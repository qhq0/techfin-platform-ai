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

 Date: 01/09/2026 04:02:16
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for kjjr_ai_sxd_att
-- ----------------------------
DROP TABLE IF EXISTS `kjjr_ai_sxd_att`;
CREATE TABLE `kjjr_ai_sxd_att`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `att_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '附件上传返回的附件 ID',
  `file_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '上传时的原始文件名',
  `file_size` bigint NULL DEFAULT NULL COMMENT '文件大小（字节）',
  `created_at` datetime NOT NULL COMMENT '创建时间，用于清理孤立附件',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 102 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '附件元信息表' ROW_FORMAT = Dynamic;

SET FOREIGN_KEY_CHECKS = 1;
