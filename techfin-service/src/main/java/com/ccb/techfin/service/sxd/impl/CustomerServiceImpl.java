package com.ccb.techfin.service.sxd.impl;

import com.ccb.techfin.common.enums.RoleEnum;
import com.ccb.techfin.common.exception.BusinessException;
import com.ccb.techfin.dao.sxd.CustomerProfileMapper;
import com.ccb.techfin.dao.sxd.MspDeptMapper;
import com.ccb.techfin.dao.sxd.MspUserMapper;
import com.ccb.techfin.dao.sxd.SxdMapper;
import com.ccb.techfin.model.entity.MspDept;
import com.ccb.techfin.model.entity.MspUser;
import com.ccb.techfin.model.sxd.entity.SxdRecord;
import com.ccb.techfin.model.sxd.entity.CustomerProfile;
import com.ccb.techfin.service.sxd.CustomerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 客户信息服务实现。
 *
 * @author qiuhaoquan
 * @since 2026-07-23
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerServiceImpl implements CustomerService {

    private final CustomerProfileMapper customerProfileMapper;
    private final SxdMapper sxdMapper;
    private final MspUserMapper mspUserMapper;
    private final MspDeptMapper mspDeptMapper;

    @Override
    public String getControllerName(String cstId) {
        if (!StringUtils.hasText(cstId)) {
            throw new BusinessException("PARAM_MISSING", "客户编号不能为空");
        }

        // 1. 查询 sxd_profile 获取实控人姓名
        CustomerProfile profile = customerProfileMapper.selectById(cstId);

        if (profile == null) {
            throw new BusinessException("CUSTOMER_NOT_FOUND",
                    "客户编号 [" + cstId + "] 不存在");
        }

        String name = profile.getActCntlrNm();

        // 2. 从 sxd_record 查询管户权：存在 has_ownership = '1' 的记录才返回实控人姓名
        boolean hasOwnership = sxdMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SxdRecord>()
                        .eq(SxdRecord::getCstId, cstId)
                        .eq(SxdRecord::getHasOwnership, "1"))
                .stream()
                .anyMatch(record -> "1".equals(record.getHasOwnership()));

        if (!hasOwnership) {
            log.info("Customer controller name query denied: cstId={}, no ownership record found", cstId);
            return "";
        }

        log.info("Customer controller name query: cstId={}, actCntlrNm={}", cstId, name);
        return name;
    }

    @Override
    public CustomerProfile getCustomerProfile(String cstId) {
        if (!StringUtils.hasText(cstId)) {
            throw new BusinessException("PARAM_MISSING", "客户编号不能为空");
        }

        CustomerProfile profile = customerProfileMapper.selectById(cstId);

        if (profile == null) {
            throw new BusinessException("CUSTOMER_NOT_FOUND",
                    "客户编号 [" + cstId + "] 不存在");
        }

        sanitizeProfile(profile);

        return profile;
    }

    /**
     * 清洗 sxd_profile 中特定字段的占位值：
     * tech_flow 的 "-" → ""，其余 5 个字段的 "-99" → ""。
     */
    private void sanitizeProfile(CustomerProfile profile) {
        if ("-".equals(profile.getTechFlow())) {
            profile.setTechFlow("");
        }
        if ("-99".equals(profile.getKcScore())) {
            profile.setKcScore("");
        }
        if ("-99".equals(profile.getEntpPtntNum())) {
            profile.setEntpPtntNum("");
        }
        if ("-99".equals(profile.getEntpPrctNewTpPtntNum())) {
            profile.setEntpPrctNewTpPtntNum("");
        }
        if ("-99".equals(profile.getEntpIvtPtntNum())) {
            profile.setEntpIvtPtntNum("");
        }
        if ("-99".equals(profile.getClst5YrInnRsWcoprNum())) {
            profile.setClst5YrInnRsWcoprNum("");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean getCustOwnership(String taskId, String cstId, String userAccount) {
        if (!StringUtils.hasText(taskId)) {
            throw new BusinessException("PARAM_MISSING", "任务 ID 不能为空");
        }
        if (!StringUtils.hasText(cstId)) {
            throw new BusinessException("PARAM_MISSING", "客户编号不能为空");
        }
        if (!StringUtils.hasText(userAccount)) {
            throw new BusinessException("PARAM_MISSING", "登录账号不能为空");
        }

        // ==================== 1. 查找用户角色和部门 ====================
        MspUser user = mspUserMapper.selectByAccount(userAccount);
        if (user == null) {
            log.warn("User not found by userAccount={}", userAccount);
            updateOwnership(taskId, "0");
            return false;
        }

        String staffCode = user.getStaffCode();
        Set<Integer> roleIds = parseCommaSeparated(user.getRoleId());
        List<Integer> deptIds = parseCommaSeparatedToList(user.getDeptId());

        log.debug("User found: userAccount={}, staffCode={}, roleIds={}, deptIds={}", userAccount, staffCode, roleIds, deptIds);

        // ==================== 2. 通过 dept_id 查找 institution_no ====================
        List<String> institutionNos = deptIds.stream()
                .map(id -> mspDeptMapper.selectActiveById(id))
                .filter(Objects::nonNull)
                .map(dept -> dept.getInstitutionNo() != null ? dept.getInstitutionNo().toString() : null)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        log.debug("Institution numbers from departments: {}", institutionNos);

        // ==================== 3. 判断是否为分行经办人员（科技金融创新中心）====================
        if (roleIds.contains(RoleEnum.BRANCH_OFFICE_HANDLER.getRoleId())
                && institutionNos.contains("443536363")) {
            log.info("Cust ownership granted: userAccount={}, staffCode={}, role=分行经办人员({}), institutionNo=443536363",
                    userAccount, staffCode, RoleEnum.BRANCH_OFFICE_HANDLER.getRoleId());
            updateOwnership(taskId, "1");
            return true;
        }

        // ==================== 4. 查询客户管户信息 ====================
        CustomerProfile profile = customerProfileMapper.selectById(cstId);
        if (profile == null) {
            log.warn("Customer not found: cstId={}", cstId);
            updateOwnership(taskId, "0");
            return false;
        }

        String custInstNo = profile.getCstMngaccInstSuprInsid();   // 管户支行编号
        String custMgrId = profile.getCstMngaccCstmgrId();         // 管户客户经理编号（对应 msp_user.staff_code）

        log.debug("Customer profile: cstId={}, cstMngaccInstSuprInsid={}, cstMngaccCstmgrId={}",
                cstId, custInstNo, custMgrId);

        // ==================== 5. 匹配管户支行编号 ====================
        boolean instMatched = custInstNo != null && institutionNos.contains(custInstNo);

        if (instMatched && roleIds.contains(RoleEnum.SUB_BRANCH_DEPT_HEAD.getRoleId())) {
            log.info("Cust ownership granted: userAccount={}, staffCode={}, role=支行科室负责人({}), instMatched=true",
                    userAccount, staffCode, RoleEnum.SUB_BRANCH_DEPT_HEAD.getRoleId());
            updateOwnership(taskId, "1");
            return true;
        }

        // ==================== 6. 匹配管户客户经理编号（cst_mngacc_cstmgr_id 对应 msp_user.staff_code）====================
        if (staffCode != null && staffCode.equals(custMgrId)) {
            log.info("Cust ownership granted: userAccount={}, staffCode={}, matched as account manager", userAccount, staffCode);
            updateOwnership(taskId, "1");
            return true;
        }

        // ==================== 7. 无管户权 ====================
        log.info("Cust ownership denied: userAccount={}, staffCode={}, cstId={}", userAccount, staffCode, cstId);
        updateOwnership(taskId, "0");
        return false;
    }

    /**
     * 将逗号分隔的 ID 字符串解析为 Integer 集合。
     */
    private Set<Integer> parseCommaSeparated(String ids) {
        if (!StringUtils.hasText(ids)) {
            return Set.of();
        }
        return Arrays.stream(ids.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(this::safeParseInt)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    /**
     * 将逗号分隔的 ID 字符串解析为 Integer 列表（保留顺序）。
     */
    private List<Integer> parseCommaSeparatedToList(String ids) {
        if (!StringUtils.hasText(ids)) {
            return List.of();
        }
        return Arrays.stream(ids.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(this::safeParseInt)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private Integer safeParseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            log.warn("Failed to parse ID: {}", s);
            return null;
        }
    }

    /**
     * 更新 sxd_record 中的管户权标识。
     */
    private void updateOwnership(String taskId, String hasOwnership) {
        SxdRecord record = sxdMapper.selectById(taskId);
        if (record != null) {
            record.setHasOwnership(hasOwnership);
            sxdMapper.updateById(record);
        } else {
            log.warn("Task not found for ownership update: taskId={}", taskId);
        }
    }
}
