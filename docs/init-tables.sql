-- ============================================================
-- techfin-platform-ai 数据库表初始化脚本
-- 数据库：MySQL 8.0+ (InnoDB, utf8mb4)
-- 说明：MyBatis-Plus 管理数据访问，需手动建表
-- ============================================================

CREATE DATABASE IF NOT EXISTS mydb
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE mydb;

-- ------------------------------------------------------------
-- 1. kjjr_ai_sxd_att — 附件元信息表（独立实体）
--    上传时写入，提交时查询 fileName/fileSize/businessType 用于外部 API
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS kjjr_ai_sxd_att (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    att_id        VARCHAR(64)  NOT NULL                COMMENT '附件上传返回的附件 ID',
    file_name     VARCHAR(255) DEFAULT NULL             COMMENT '上传时的原始文件名',
    file_size     BIGINT       DEFAULT NULL             COMMENT '文件大小（字节）',
    created_at    DATETIME     NOT NULL                COMMENT '创建时间，用于清理孤立附件',
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='附件元信息表';


-- ------------------------------------------------------------
-- 2. kjjr_ai_sxd_record — 申请记录表
--    提交资料时创建，主键为 task_id，无外键约束
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS kjjr_ai_sxd_record (
    task_id      VARCHAR(64)  NOT NULL                COMMENT '任务 ID，格式 TASK-<32位hex>',
    credit_code  VARCHAR(30)  NOT NULL                COMMENT '统一社会信用代码',
    cst_id       VARCHAR(64)  NOT NULL                COMMENT '客户编号',
    status       VARCHAR(10)  NOT NULL DEFAULT '0'     COMMENT '任务状态：0-未完成 / 1-已完成',
    act_cntlr_nm VARCHAR(200) DEFAULT NULL             COMMENT '实际控制人姓名，用户确认后回填',
    has_ownership VARCHAR(10)  DEFAULT NULL             COMMENT '是否有管户权：1-是，0-否',
    created_at   DATETIME     NOT NULL                COMMENT '创建时间',
    updated_at   DATETIME     NOT NULL                COMMENT '更新时间',
    PRIMARY KEY (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='申请记录表';


-- ------------------------------------------------------------
-- 3. kjjr_ai_sxd_doc — 文档明细表（集合表）
--    每条记录对应外部 API 返回的一条文档，DOC_ID 全局唯一
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS kjjr_ai_sxd_doc (
    doc_id        VARCHAR(64)  NOT NULL                COMMENT '资料批量新增返回的文档 ID',
    task_id       VARCHAR(64)  NOT NULL                COMMENT '关联 kjjr_ai_sxd_record.task_id',
    business_type VARCHAR(32)  DEFAULT NULL             COMMENT '业务类型（docTypeId 值），由 financeFiles/businessFile 分类从配置 api.doc-type.* 获取',
    report_date   VARCHAR(10)  DEFAULT NULL             COMMENT '财报报告日期（仅 finance 类型有值）',
    PRIMARY KEY (doc_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文档明细表';


-- ------------------------------------------------------------
-- 4. kjjr_ai_sxd_extract_data — 提取数据缓存表
--    缓存外部 API 返回的提取文本，避免重复调用
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS kjjr_ai_sxd_extract_data (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    task_id      VARCHAR(64)  NOT NULL                COMMENT '关联 kjjr_ai_sxd_record.task_id',
    doc_id       VARCHAR(64)  NOT NULL                COMMENT '关联 kjjr_ai_sxd_doc.doc_id',
    table_name   VARCHAR(128) NOT NULL                COMMENT '提取表名，如 dib_manage_company_profile',
    text         TEXT         DEFAULT NULL             COMMENT '提取文本内容',
    created_at   DATETIME     NOT NULL                COMMENT '创建时间',
    updated_at   DATETIME     NOT NULL                COMMENT '更新时间',
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='提取数据缓存表';


-- ------------------------------------------------------------
-- 5. sxd_institution — 机构信息表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sxd_institution (
    id               INT          NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    tenant_code      VARCHAR(12)  DEFAULT NULL             COMMENT '租户编码',
    institution_no   INT          DEFAULT NULL             COMMENT '机构编号',
    parent_id        INT          DEFAULT NULL             COMMENT '父级机构ID',
    dept_name        VARCHAR(45)  DEFAULT NULL             COMMENT '机构名称',
    full_name        VARCHAR(45)  DEFAULT NULL             COMMENT '机构全称',
    sort             INT          DEFAULT NULL             COMMENT '排序号',
    remark           VARCHAR(255) DEFAULT NULL             COMMENT '备注',
    is_deleted       INT          DEFAULT NULL             COMMENT '是否删除（0-正常，1-删除）',
    service_type     VARCHAR(20)  DEFAULT NULL             COMMENT '服务类型',
    institution_type VARCHAR(30)  DEFAULT NULL             COMMENT '机构类型',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_parent_id (parent_id),
    KEY idx_tenant_code (tenant_code),
    KEY idx_institution_no (institution_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='机构信息表';
