package com.ccb.techfin.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 部门/机构表，映射 msp_dept。
 *
 * @author qiuhaoquan
 * @since 2026-07-27
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("msp_dept")
public class MspDept {

    @TableId(type = IdType.AUTO)
    private Integer id;

    /** 租户编码 */
    @TableField("tenant_code")
    private String tenantCode;

    /** 机构编号 */
    @TableField("institution_no")
    private Integer institutionNo;

    /** 父级部门ID */
    @TableField("parent_id")
    private Integer parentId;

    /** 部门名称 */
    @TableField("dept_name")
    private String deptName;

    /** 部门全称 */
    @TableField("full_name")
    private String fullName;

    /** 排序号 */
    @TableField("sort")
    private Integer sort;

    /** 备注 */
    @TableField("remark")
    private String remark;

    /** 是否删除（0-正常，1-删除） */
    @TableField("is_deleted")
    private Integer isDeleted;

    /** 服务类型 */
    @TableField("service_type")
    private String serviceType;

    /** 机构类型 */
    @TableField("institution_type")
    private String institutionType;
}
