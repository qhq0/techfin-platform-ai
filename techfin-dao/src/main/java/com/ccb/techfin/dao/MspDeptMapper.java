package com.ccb.techfin.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ccb.techfin.model.entity.MspDept;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * MSP 部门/机构表 Mapper。
 *
 * @author qiuhaoquan
 * @since 2026-07-27
 */
@Mapper
public interface MspDeptMapper extends BaseMapper<MspDept> {

    /**
     * 根据主键查询未删除的部门记录。
     *
     * @param id 主键
     * @return 部门信息，已删除或不存在返回 null
     */
    @Select("SELECT * FROM msp_dept WHERE id = #{id} AND is_deleted = 0")
    MspDept selectActiveById(@Param("id") Integer id);
}
