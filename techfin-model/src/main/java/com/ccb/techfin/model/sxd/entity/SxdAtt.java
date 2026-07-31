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

    @TableId(type = IdType.AUTO)
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
