package com.ccb.techfin.model.sxd.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 申请记录实体，映射 sxd_record 表。
 *
 * @author qiuhaoquan
 * @since 2026-07-23
 */
@Data
@TableName("kjjr_ai_sxd_record")
public class SxdRecord {

    @TableId(value = "task_id", type = IdType.INPUT)
    private String taskId;

    @TableField("credit_code")
    private String creditCode;

    @TableField("cst_id")
    private String cstId;

    /** 任务状态：0-未完成，1-已完成 */
    @TableField("status")
    private String status;

    /** 实际控制人姓名，用户确认后回填 */
    @TableField("act_cntlr_nm")
    private String actCntlrNm;

    /** 是否有管户权：1-是，0-否 */
    @TableField("has_ownership")
    private String hasOwnership;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
