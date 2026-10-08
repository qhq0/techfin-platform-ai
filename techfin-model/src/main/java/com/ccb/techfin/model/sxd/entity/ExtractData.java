package com.ccb.techfin.model.sxd.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 提取数据缓存实体，映射 sxd_extract_data 表。
 *
 * @author qiuhaoquan
 * @since 2026-07-23
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("kjjr_ai_sxd_extract_data")
public class ExtractData {

    /**
     * 主键，雪花 ID，由 MyBatis-Plus 的 {@code IdentifierGenerator} 赋值。
     * <p>
     * 刻意不用数据库自增：InnoDB 自增计数器是实例级的，多主写入或主从切换后
     * 会重复发号，导致主键冲突（1062）。节点标识见
     * {@code mybatis-plus.global-config.sequence.*}（每副本必须一个不同的 worker-id）。
     * </p>
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 关联 sxd_record.task_id */
    @TableField("task_id")
    private String taskId;

    /** 关联 sxd_doc.doc_id */
    @TableField("doc_id")
    private String docId;

    /** 提取表名，如 dib_manage_company_profile */
    @TableField("table_name")
    private String tableName;

    /** 提取文本内容 */
    @TableField("text")
    private String text;

    /** 创建时间 */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新时间 */
    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}