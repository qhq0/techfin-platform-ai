package com.ccb.techfin.service.sxd;

import com.ccb.techfin.model.sxd.entity.CustomerProfile;

/**
 * 客户信息查询服务
 *
 * @author qiuhaoquan
 * @since 2026-07-23
 */
public interface CustomerService {

    /**
     * 查询实控人姓名
     *
     * @param taskId 任务 ID，用于从 kjjr_ai_sxd_record 校验管户权（has_ownership = '1'）
     * @param cstId  客户编号，用于查询 kjjr_ai_sxd_profile 实控人姓名
     * @return 有管户权时返回实控人姓名，无管户权时返回空字符串
     */
    String getControllerName(String taskId, String cstId);

    /**
     * 根据客户编号查询客户完整信息
     *
     * @param cstId 客户编号
     * @return 客户信息（最新记录）
     */
    CustomerProfile getCustomerProfile(String cstId);

    /**
     * 校验当前用户是否拥有该客户的管户权。
     * 从 sxd_profile 查询管户信息，与员工编号比对。
     *
     * @param taskId 任务 ID
     * @param cstId  客户编号
     * @param userAccount 登录账号（从请求头 Token 解密得到，对应 msp_user.account）
     * @return 是否拥有管户权
     */
    boolean getCustOwnership(String taskId, String cstId, String userAccount);
}
