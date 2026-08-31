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

 Date: 01/09/2026 04:02:00
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for msp_role
-- ----------------------------
DROP TABLE IF EXISTS `msp_role`;
CREATE TABLE `msp_role`  (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `tenant_code` varchar(12) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '租户编码',
  `parent_id` int NULL DEFAULT NULL COMMENT '父级角色ID',
  `role_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '角色名称',
  `sort` int NULL DEFAULT NULL COMMENT '排序号',
  `role_alias` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '角色别名',
  `is_deleted` int NULL DEFAULT NULL COMMENT '是否删除（0-正常，1-删除）',
  `app_id` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '应用ID',
  `service_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '服务类型',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 104 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '角色表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of msp_role
-- ----------------------------
INSERT INTO `msp_role` VALUES (89, '000000', 0, '对公客户经理', 1, '对公客户经理', 0, '22', 'station');
INSERT INTO `msp_role` VALUES (90, '000000', 0, '网点/团队负责人', 2, '网点/团队负责人', 0, '22', 'station');
INSERT INTO `msp_role` VALUES (91, '000000', 0, '支行经办人员', 3, '支行经办人员', 0, '22', 'station');
INSERT INTO `msp_role` VALUES (92, '000000', 0, '支行科室负责人', 4, '支行科室负责人', 0, '22', 'station');
INSERT INTO `msp_role` VALUES (93, '000000', 0, '支行主要负责人', 5, '支行主要负责人', 0, '22', 'station');
INSERT INTO `msp_role` VALUES (94, '000000', 0, '分行经办人员', 6, '分行经办人员', 0, '22', 'station');
INSERT INTO `msp_role` VALUES (95, '000000', 0, '部门主要负责人', 7, '部门主要负责人', 0, '22', 'station');
INSERT INTO `msp_role` VALUES (96, '000000', 0, '行领导', 8, '行领导', 0, '22', 'station');
INSERT INTO `msp_role` VALUES (97, '000000', 0, '分行审批人', 9, '分行审批人', 0, '22', 'station');
INSERT INTO `msp_role` VALUES (98, '000000', 0, '支行管理员', 10, '支行管理员', 0, '0', 'station');
INSERT INTO `msp_role` VALUES (99, '000000', 0, '部门分管负责人', 11, '部门分管负责人', 0, '22', 'station');
INSERT INTO `msp_role` VALUES (100, '000000', 0, '分行管理员', 12, '分行管理员', 0, '0', 'station');
INSERT INTO `msp_role` VALUES (101, '000000', 0, '支行分管负责人', 15, '支行分管负责人', 0, '22', 'station');
INSERT INTO `msp_role` VALUES (102, '000000', 0, '行长秘书', 14, '行长秘书', 0, '22', 'station');
INSERT INTO `msp_role` VALUES (103, '000000', 0, '支行辖内认定牵头人', 15, '支行辖内认定牵头人', 0, '0', 'station');

SET FOREIGN_KEY_CHECKS = 1;
