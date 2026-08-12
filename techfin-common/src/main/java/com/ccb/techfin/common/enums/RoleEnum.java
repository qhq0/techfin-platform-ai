package com.ccb.techfin.common.enums;

/**
 * 角色枚举。
 * <p>
 * 对应 msp_role 表的 role_name 字段。
 * role_id(主键 id) 在实际数据库中可能随环境变化，唯一不变的是角色名称，
 * 因此管户权等业务判断统一以数据库查询出的 role_name 为准，这里仅保留角色名称。
 * </p>
 *
 * @author qiuhaoquan
 * @since 2026-07-27
 */
public enum RoleEnum {

    /** 对公客户经理 */
    CORP_ACCOUNT_MANAGER("对公客户经理"),

    /** 网点/团队负责人 */
    BRANCH_TEAM_LEADER("网点/团队负责人"),

    /** 支行经办人员 */
    SUB_BRANCH_HANDLER("支行经办人员"),

    /** 支行科室负责人 */
    SUB_BRANCH_DEPT_HEAD("支行科室负责人"),

    /** 支行主要负责人 */
    SUB_BRANCH_PRINCIPAL("支行主要负责人"),

    /** 分行经办人员 */
    BRANCH_OFFICE_HANDLER("分行经办人员"),

    /** 部门主要负责人 */
    DEPT_PRINCIPAL("部门主要负责人"),

    /** 行领导 */
    BANK_LEADERSHIP("行领导"),

    /** 分行审批人 */
    BRANCH_APPROVER("分行审批人"),

    /** 支行管理员 */
    SUB_BRANCH_ADMIN("支行管理员"),

    /** 部门分管负责人 */
    DEPT_DEPUTY_HEAD("部门分管负责人"),

    /** 分行管理员 */
    BRANCH_ADMIN("分行管理员"),

    /** 支行分管负责人 */
    SUB_BRANCH_DEPUTY_HEAD("支行分管负责人"),

    /** 行长秘书 */
    PRESIDENT_SECRETARY("行长秘书"),

    /** 支行辖内认定牵头人 */
    SUB_BRANCH_IDENTIFY_LEAD("支行辖内认定牵头人");

    private final String roleName;

    RoleEnum(String roleName) {
        this.roleName = roleName;
    }

    public String getRoleName() {
        return roleName;
    }
}