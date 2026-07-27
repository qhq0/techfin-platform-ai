package com.ccb.techfin.common.enums;

/**
 * 角色枚举。
 * <p>
 * 对应 msp_role 表中的角色定义，映射 roleId → roleName。
 * </p>
 *
 * @author qiuhaoquan
 * @since 2026-07-27
 */
public enum RoleEnum {

    /** 对公客户经理 */
    CORP_ACCOUNT_MANAGER(89, "对公客户经理"),
    /** 网点/团队负责人 */
    BRANCH_TEAM_LEADER(90, "网点/团队负责人"),
    /** 支行经办人员 */
    SUB_BRANCH_HANDLER(91, "支行经办人员"),
    /** 支行科室负责人 */
    SUB_BRANCH_DEPT_HEAD(92, "支行科室负责人"),
    /** 支行主要负责人 */
    SUB_BRANCH_PRINCIPAL(93, "支行主要负责人"),
    /** 分行经办人员 */
    BRANCH_OFFICE_HANDLER(94, "分行经办人员"),
    /** 部门主要负责人 */
    DEPT_PRINCIPAL(95, "部门主要负责人"),
    /** 行领导 */
    BANK_LEADERSHIP(96, "行领导"),
    /** 分行审批人 */
    BRANCH_APPROVER(97, "分行审批人"),
    /** 支行管理员 */
    SUB_BRANCH_ADMIN(98, "支行管理员"),
    /** 部门分管负责人 */
    DEPT_DEPUTY_HEAD(99, "部门分管负责人"),
    /** 分行管理员 */
    BRANCH_ADMIN(100, "分行管理员"),
    /** 支行分管负责人 */
    SUB_BRANCH_DEPUTY_HEAD(101, "支行分管负责人"),
    /** 行长秘书 */
    PRESIDENT_SECRETARY(102, "行长秘书"),
    /** 支行辖内认定牵头人 */
    SUB_BRANCH_IDENTIFY_LEAD(103, "支行辖内认定牵头人");

    private final int roleId;
    private final String roleName;

    RoleEnum(int roleId, String roleName) {
        this.roleId = roleId;
        this.roleName = roleName;
    }

    public int getRoleId() {
        return roleId;
    }

    public String getRoleName() {
        return roleName;
    }

    /**
     * 根据 roleId 获取角色枚举。
     *
     * @param roleId 角色 ID
     * @return 角色枚举，未找到返回 null
     */
    public static RoleEnum fromId(int roleId) {
        for (RoleEnum role : values()) {
            if (role.roleId == roleId) {
                return role;
            }
        }
        return null;
    }

    /**
     * 根据 roleName 获取角色枚举。
     *
     * @param roleName 角色名称
     * @return 角色枚举，未找到返回 null
     */
    public static RoleEnum fromName(String roleName) {
        if (roleName == null) {
            return null;
        }
        for (RoleEnum role : values()) {
            if (role.roleName.equals(roleName)) {
                return role;
            }
        }
        return null;
    }
}
