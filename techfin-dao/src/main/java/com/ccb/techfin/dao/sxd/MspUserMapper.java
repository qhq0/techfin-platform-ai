package com.ccb.techfin.dao.sxd;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ccb.techfin.model.entity.MspUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * MSP 用户表 Mapper。
 *
 * @author qiuhaoquan
 * @since 2026-07-27
 */
@Mapper
public interface MspUserMapper extends BaseMapper<MspUser> {

    /**
     * 根据登录账号（对应 msp_user.account）查询用户。
     *
     * @param userAccount 登录账号
     * @return 用户信息
     */
    @Select("SELECT * FROM msp_user WHERE account = #{userAccount} AND is_deleted = 0 LIMIT 1")
    MspUser selectByAccount(@Param("userAccount") String userAccount);
}
