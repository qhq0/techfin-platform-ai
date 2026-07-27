-- 角色表
-- 用于存储 MSP 系统角色信息

CREATE TABLE IF NOT EXISTS msp_role (
    id           INT          NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    tenant_code  VARCHAR(12)  DEFAULT NULL             COMMENT '租户编码',
    parent_id    INT          DEFAULT NULL             COMMENT '父级角色ID',
    role_name    VARCHAR(255) DEFAULT NULL             COMMENT '角色名称',
    sort         INT          DEFAULT NULL             COMMENT '排序号',
    role_alias   VARCHAR(255) DEFAULT NULL             COMMENT '角色别名',
    is_deleted   INT          DEFAULT NULL             COMMENT '是否删除（0-正常，1-删除）',
    app_id       VARCHAR(20)  DEFAULT NULL             COMMENT '应用ID',
    service_type VARCHAR(20)  DEFAULT NULL             COMMENT '服务类型',
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色表';