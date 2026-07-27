package com.ccb.techfin.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 角色表，映射 msp_role。
 *
 * @author qiuhaoquan
 * @since 2026-07-27
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("msp_role")
public class MspRole {

    @TableId(type = IdType.AUTO)
    private Integer id;

    /** 租户编码 */
    @TableField("tenant_code")
    private String tenantCode;

    /** 父级角色ID */
    @TableField("parent_id")
    private Integer parentId;

    /** 角色名称 */
    @TableField("role_name")
    private String roleName;

    /** 排序号 */
    @TableField("sort")
    private Integer sort;

    /** 角色别名 */
    @TableField("role_alias")
    private String roleAlias;

    /** 是否删除（0-正常，1-删除） */
    @TableField("is_deleted")
    private Integer isDeleted;

    /** 应用ID */
    @TableField("app_id")
    private String appId;

    /** 服务类型 */
    @TableField("service_type")
    private String serviceType;
}
