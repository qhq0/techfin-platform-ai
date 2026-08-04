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
    public String getControllerName(String taskId, String cstId) {
        if (!StringUtils.hasText(taskId)) {
            throw new BusinessException("PARAM_MISSING", "任务 ID 不能为空");
        }
        if (!StringUtils.hasText(cstId)) {
            throw new BusinessException("PARAM_MISSING", "客户编号不能为空");
        }

        // 1. 用 cstId 查询 kjjr_ai_sxd_profile 获取实控人姓名
        CustomerProfile profile = customerProfileMapper.selectById(cstId);

        if (profile == null) {
            throw new BusinessException("CUSTOMER_NOT_FOUND",
                    "客户编号 [" + cstId + "] 不存在");
        }

        String name = profile.getActCntlrNm();

        // 2. 用 taskId（主键）精确查询 kjjr_ai_sxd_record.has_ownership，值为 '1' 才返回姓名，否则返回空字符串
        //    无论是否有管户权，都将查询到的实控人姓名回填到 kjjr_ai_sxd_record.act_cntlr_nm
        SxdRecord record = sxdMapper.selectById(taskId);
        if (record == null) {
            log.warn("Task not found for controller name query: taskId={}", taskId);
            throw new BusinessException("TASK_NOT_FOUND",
                    "任务 [" + taskId + "] 不存在");
        }

        record.setActCntlrNm(name);
        sxdMapper.updateById(record);

        if (!"1".equals(record.getHasOwnership())) {
            log.info("Customer controller name query denied: taskId={}, cstId={}, hasOwnership={}",
                    taskId, cstId, record.getHasOwnership());
            return "";
        }

        log.info("Customer controller name query: taskId={}, cstId={}, actCntlrNm={}",
                taskId, cstId, name);
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

        // ==================== 1. 根据 account 查询 staff_code、role_id、dept_id ====================
        MspUser user = mspUserMapper.selectByAccount(userAccount);
        if (user == null) {
            log.warn("User not found by userAccount={}", userAccount);
            updateOwnership(taskId, "0");
            return false;
        }

        String staffCode = user.getStaffCode();
        // role_id 可能为多个，用逗号分隔
        Set<Integer> roleIds = parseCommaSeparated(user.getRoleId());
        // dept_id 只有一个数
        Integer deptId = safeParseInt(user.getDeptId());

        log.debug("User found: userAccount={}, staffCode={}, roleIds={}, deptId={}", userAccount, staffCode, roleIds, deptId);

        // ==================== 2. 通过 dept_id 查找 institution_no ====================
        String institutionNo = null;
        if (deptId != null) {
            MspDept dept = mspDeptMapper.selectActiveById(deptId);
            if (dept != null && dept.getInstitutionNo() != null) {
                institutionNo = dept.getInstitutionNo().toString();
            }
        }
        log.debug("Institution number from department: {}", institutionNo);

        // ==================== 3. 分行经办人员 + 科技金融创新中心(443536363) ====================
        if (roleIds.contains(RoleEnum.BRANCH_OFFICE_HANDLER.getRoleId())
                && "443536363".equals(institutionNo)) {
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

        // 管户支行编号、管户客户经理编号（对应 msp_user.staff_code）
        String custInstNo = profile.getCstMngaccInstSuprInsid();
        String custMgrId = profile.getCstMngaccCstmgrId();
        log.debug("Customer profile: cstId={}, cstMngaccInstSuprInsid={}, cstMngaccCstmgrId={}",
                cstId, custInstNo, custMgrId);

        // ==================== 5. 管户支行编号匹配 + 支行科室负责人 ====================
        // 用 cst_mngacc_inst_supr_insid 匹配 institution_no，一致且 role_id 含支行科室负责人 → true
        boolean instMatched = custInstNo != null && custInstNo.equals(institutionNo);
        if (instMatched && roleIds.contains(RoleEnum.SUB_BRANCH_DEPT_HEAD.getRoleId())) {
            log.info("Cust ownership granted: userAccount={}, staffCode={}, role=支行科室负责人({}), instMatched=true",
                    userAccount, staffCode, RoleEnum.SUB_BRANCH_DEPT_HEAD.getRoleId());
            updateOwnership(taskId, "1");
            return true;
        }
        // 不一致，或一致但非支行科室负责人 → 进入下一步

        // ==================== 6. 管户客户经理编号匹配 staff_code ====================
        if (staffCode != null && staffCode.equals(custMgrId)) {
            log.info("Cust ownership granted: userAccount={}, staffCode={}, matched as account manager", userAccount, staffCode);
            updateOwnership(taskId, "1");
            return true;
        }

        // ==================== 7. 其余情况均为无管户权 ====================
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
