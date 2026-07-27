-- 部门表
-- 用于存储 MSP 系统部门/机构信息

CREATE TABLE IF NOT EXISTS msp_dept (
    id               INT          NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    tenant_code      VARCHAR(12)  DEFAULT NULL             COMMENT '租户编码',
    institution_no   INT          DEFAULT NULL             COMMENT '机构编号',
    parent_id        INT          DEFAULT NULL             COMMENT '父级部门ID',
    dept_name        VARCHAR(45)  DEFAULT NULL             COMMENT '部门名称',
    full_name        VARCHAR(45)  DEFAULT NULL             COMMENT '部门全称',
    sort             INT          DEFAULT NULL             COMMENT '排序号',
    remark           VARCHAR(255) DEFAULT NULL             COMMENT '备注',
    is_deleted       INT          DEFAULT NULL             COMMENT '是否删除（0-正常，1-删除）',
    service_type     VARCHAR(20)  DEFAULT NULL             COMMENT '服务类型',
    institution_type VARCHAR(30)  DEFAULT NULL             COMMENT '机构类型',
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='部门表';
