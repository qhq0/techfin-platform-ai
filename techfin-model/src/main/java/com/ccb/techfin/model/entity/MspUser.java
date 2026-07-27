package com.ccb.techfin.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户表，映射 msp_user。
 *
 * @author qiuhaoquan
 * @since 2026-07-27
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("msp_user")
public class MspUser {

    @TableId(type = IdType.AUTO)
    private Integer id;

    /** 租户编码 */
    @TableField("tenant_code")
    private String tenantCode;

    /** 账号 */
    @TableField("account")
    private String account;

    /** 密码 */
    @TableField("password")
    private String password;

    /** 昵称 */
    @TableField("name")
    private String name;

    /** 真实姓名 */
    @TableField("real_name")
    private String realName;

    /** 邮箱 */
    @TableField("email")
    private String email;

    /** 手机号 */
    @TableField("phone")
    private String phone;

    /** 生日 */
    @TableField("birthday")
    private String birthday;

    /** 性别 */
    @TableField("sex")
    private Integer sex;

    /** 角色ID（逗号分隔） */
    @TableField("role_id")
    private String roleId;

    /** 部门ID（逗号分隔） */
    @TableField("dept_id")
    private String deptId;

    /** 创建人 */
    @TableField("create_user")
    private Integer createUser;

    /** 创建时间 */
    @TableField("create_time")
    private LocalDateTime createTime;

    /** 更新人 */
    @TableField("update_user")
    private Integer updateUser;

    /** 更新时间 */
    @TableField("update_time")
    private LocalDateTime updateTime;

    /** 状态 */
    @TableField("status")
    private Integer status;

    /** 是否删除（0-正常，1-删除） */
    @TableField("is_deleted")
    private Integer isDeleted;

    /** 头像路径 */
    @TableField("filepath")
    private String filepath;

    /** 签名 */
    @TableField("sigh")
    private String sigh;

    /** 服务类型 */
    @TableField("service_type")
    private String serviceType;

    /** 员工编号 */
    @TableField("staff_code")
    private String staffCode;

    /** 座机号码 */
    @TableField("landline_num")
    private String landlineNum;

    /** 休假状态 */
    @TableField("vac_sts")
    private String vacSts;

    /** 锁定时间 */
    @TableField("lock_time")
    private String lockTime;

    /** 锁定原因 */
    @TableField("lock_reason")
    private String lockReason;

    /** 渠道 */
    @TableField("channel")
    private String channel;

    /** 秘书 */
    @TableField("secretary")
    private String secretary;

    /** 是否分行信审主任（0-否，1-是） */
    @TableField("is_branch_xnrd_header")
    private Integer isBranchXnrdHeader;
}
