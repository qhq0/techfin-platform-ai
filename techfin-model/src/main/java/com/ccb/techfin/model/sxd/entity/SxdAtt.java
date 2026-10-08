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
 * 附件上传记录，独立实体映射 sxd_att 表。
 * 上传时记录文件元信息，提交时根据 attId 查找匹配。
 *
 * @author qiuhaoquan
 * @since 2026-07-23
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("kjjr_ai_sxd_att")
public class SxdAtt {

    /**
     * 主键，雪花 ID，由 MyBatis-Plus 的 {@code IdentifierGenerator} 赋值。
     * <p>
     * 刻意不用数据库自增：InnoDB 自增计数器是实例级的，多主写入或主从切换后
     * 会重复发号，导致主键冲突（1062）。节点标识见
     * {@code mybatis-plus.global-config.sequence.*}（每副本必须一个不同的 worker-id）。
     * </p>
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 附件上传返回的附件 ID */
    @TableField("att_id")
    private String attId;

    /** 上传时的原始文件名 */
    @TableField("file_name")
    private String fileName;

    /** 文件大小（字节） */
    @TableField("file_size")
    private Long fileSize;

    /** 创建时间，用于定时清理未关联的孤立附件 */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
