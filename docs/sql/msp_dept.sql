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

 Date: 01/09/2026 04:01:53
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for msp_dept
-- ----------------------------
DROP TABLE IF EXISTS `msp_dept`;
CREATE TABLE `msp_dept`  (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `tenant_code` varchar(12) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '租户编码',
  `institution_no` int NULL DEFAULT NULL COMMENT '机构编号',
  `parent_id` int NULL DEFAULT NULL COMMENT '父级部门ID',
  `dept_name` varchar(45) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '部门名称',
  `full_name` varchar(45) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '部门全称',
  `sort` int NULL DEFAULT NULL COMMENT '排序号',
  `remark` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '备注',
  `is_deleted` int NULL DEFAULT NULL COMMENT '是否删除（0-正常，1-删除）',
  `service_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '服务类型',
  `institution_type` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '机构类型',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 11 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '部门表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of msp_dept
-- ----------------------------
INSERT INTO `msp_dept` VALUES (1, '000000', 443536363, 0, '科技金融创新中心', '科技金融创新中心', 1, '分行级特色部门', 0, 'station', 'branch');
INSERT INTO `msp_dept` VALUES (2, '000000', 443536100, 0, '深圳分行', '深圳分行', 2, '一级分行', 0, 'station', 'branch');
INSERT INTO `msp_dept` VALUES (3, '000000', 443536392, 2, '南山支行', '深圳分行南山支行', 1, '一级支行', 0, 'station', 'sub_branch');
INSERT INTO `msp_dept` VALUES (4, '000000', 443536202, 2, '福田支行', '深圳分行福田支行', 2, '一级支行', 0, 'station', 'sub_branch');
INSERT INTO `msp_dept` VALUES (5, '000000', 443536203, 2, '罗湖支行', '深圳分行罗湖支行', 3, '一级支行', 0, 'station', 'sub_branch');
INSERT INTO `msp_dept` VALUES (6, '000000', 443536204, 2, '宝安支行', '深圳分行宝安支行', 4, '一级支行', 0, 'station', 'sub_branch');
INSERT INTO `msp_dept` VALUES (7, '000000', 443536205, 2, '龙岗支行', '深圳分行龙岗支行', 5, '一级支行', 0, 'station', 'sub_branch');
INSERT INTO `msp_dept` VALUES (8, '000000', 443536206, 2, '龙华支行', '深圳分行龙华支行', 6, '一级支行', 0, 'station', 'sub_branch');
INSERT INTO `msp_dept` VALUES (9, '000000', 443536101, 0, '广州分行', '广州分行', 3, '一级分行', 0, 'station', 'branch');
INSERT INTO `msp_dept` VALUES (10, '000000', 443536301, 9, '天河支行', '广州分行天河支行', 1, '一级支行', 0, 'station', 'sub_branch');

SET FOREIGN_KEY_CHECKS = 1;
