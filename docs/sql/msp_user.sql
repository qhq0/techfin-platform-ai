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

 Date: 01/09/2026 04:02:07
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for msp_user
-- ----------------------------
DROP TABLE IF EXISTS `msp_user`;
CREATE TABLE `msp_user`  (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `tenant_code` varchar(12) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '租户编码',
  `account` varchar(45) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '账号',
  `password` varchar(45) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '密码',
  `name` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '昵称',
  `real_name` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '真实姓名',
  `email` varchar(45) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '邮箱',
  `phone` varchar(45) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '手机号',
  `birthday` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '生日',
  `sex` smallint NULL DEFAULT NULL COMMENT '性别',
  `role_id` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '角色ID（逗号分隔）',
  `dept_id` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '部门ID（逗号分隔）',
  `create_user` int NULL DEFAULT NULL COMMENT '创建人',
  `create_time` datetime NULL DEFAULT NULL COMMENT '创建时间',
  `update_user` int NULL DEFAULT NULL COMMENT '更新人',
  `update_time` datetime NULL DEFAULT NULL COMMENT '更新时间',
  `status` int NULL DEFAULT NULL COMMENT '状态',
  `is_deleted` int NULL DEFAULT NULL COMMENT '是否删除（0-正常，1-删除）',
  `filepath` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '头像路径',
  `sigh` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '签名',
  `service_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '服务类型',
  `staff_code` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '员工编号',
  `landline_num` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '座机号码',
  `vac_sts` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '休假状态',
  `lock_time` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '锁定时间',
  `lock_reason` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '锁定原因',
  `channel` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '渠道',
  `secretary` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '秘书',
  `is_branch_xnrd_header` int NULL DEFAULT NULL COMMENT '是否分行信审主任（0-否，1-是）',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 11 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '用户表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of msp_user
-- ----------------------------
INSERT INTO `msp_user` VALUES (1, '000000', 'zhangsan.sz', '123456', '张三', '张三', 'zhangsan@ccb.com', '13800000001', '1990-01-15', 1, '89,92,94', '1', 1, '2025-07-01 09:00:00', 1, '2025-07-01 09:00:00', 1, 0, '/avatar/01.png', '业精于勤', 'station', '10000679', '0755-88101001', '0', NULL, NULL, 'web', NULL, 0);
INSERT INTO `msp_user` VALUES (2, '000000', 'lisi.sz', '123456', '李四', '李四', 'lisi@ccb.com', '13800000002', '1988-06-20', 1, '90', '3', 1, '2025-07-01 09:00:00', 1, '2025-07-01 09:00:00', 1, 0, '/avatar/02.png', '天道酬勤', 'station', '10000002', '0755-88101002', '0', NULL, NULL, 'web', NULL, 0);
INSERT INTO `msp_user` VALUES (3, '000000', 'wangwu.sz', '123456', '王五', '王五', 'wangwu@ccb.com', '13800000003', '1992-03-10', 1, '91', '3', 1, '2025-07-01 09:00:00', 1, '2025-07-01 09:00:00', 1, 0, '/avatar/03.png', '客户至上', 'station', '10000003', '0755-88101003', '0', NULL, NULL, 'web', NULL, 0);
INSERT INTO `msp_user` VALUES (4, '000000', 'zhaoliu.sz', '123456', '赵六', '赵六', 'zhaoliu@ccb.com', '13800000004', '1985-11-05', 1, '92', '4', 1, '2025-07-01 09:00:00', 1, '2025-07-01 09:00:00', 1, 0, '/avatar/04.png', '精益求精', 'station', '10000004', '0755-88101004', '0', NULL, NULL, 'web', NULL, 0);
INSERT INTO `msp_user` VALUES (5, '000000', 'sunqi.sz', '123456', '孙七', '孙七', 'sunqi@ccb.com', '13800000005', '1987-08-25', 1, '93', '5', 1, '2025-07-01 09:00:00', 1, '2025-07-01 09:00:00', 1, 0, '/avatar/05.png', '责任担当', 'station', '10000005', '0755-88101005', '0', NULL, NULL, 'web', NULL, 0);
INSERT INTO `msp_user` VALUES (6, '000000', 'zhouba.sz', '123456', '周八', '周八', 'zhouba@ccb.com', '13800000006', '1991-04-18', 0, '94', '1', 1, '2025-07-01 09:00:00', 1, '2025-07-01 09:00:00', 1, 0, '/avatar/06.png', '创新驱动', 'station', '10000006', '0755-88101006', '0', NULL, NULL, 'web', NULL, 0);
INSERT INTO `msp_user` VALUES (7, '000000', 'wujiu.sz', '123456', '吴九', '吴九', 'wujiu@ccb.com', '13800000007', '1989-09-12', 0, '95', '2', 1, '2025-07-01 09:00:00', 1, '2025-07-01 09:00:00', 1, 0, '/avatar/07.png', '诚信为本', 'station', '10000007', '0755-88101007', '0', NULL, NULL, 'web', NULL, 0);
INSERT INTO `msp_user` VALUES (8, '000000', 'zhengshi.sz', '123456', '郑十', '郑十', 'zhengshi@ccb.com', '13800000008', '1986-02-28', 1, '97', '6', 1, '2025-07-01 09:00:00', 1, '2025-07-01 09:00:00', 1, 0, '/avatar/08.png', '审慎致远', 'station', '10000008', '0755-88101008', '0', NULL, NULL, 'web', NULL, 0);
INSERT INTO `msp_user` VALUES (9, '000000', 'fenglei.sz', '123456', '冯雷', '冯雷', 'fenglei@ccb.com', '13800000009', '1993-07-08', 1, '99', '7', 1, '2025-07-01 09:00:00', 1, '2025-07-01 09:00:00', 1, 0, '/avatar/09.png', '行稳致远', 'station', '10000009', '0755-88101009', '0', NULL, NULL, 'web', NULL, 0);
INSERT INTO `msp_user` VALUES (10, '000000', 'chenjie.sz', '123456', '陈杰', '陈杰', 'chenjie@ccb.com', '13800000010', '1990-12-01', 1, '94,89', '1', 1, '2025-07-01 09:00:00', 1, '2025-07-01 09:00:00', 1, 0, '/avatar/10.png', '双岗履职', 'station', '10000010', '0755-88101010', '0', NULL, NULL, 'web', NULL, 0);

SET FOREIGN_KEY_CHECKS = 1;
