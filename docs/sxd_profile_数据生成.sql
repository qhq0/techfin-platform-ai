-- ======================================================================
-- 文件名：sxd_profile_数据生成.sql
-- 目标表：kjjr_ai_sxd_profile（善新贷材料生成结果表）
-- 功能：随机生成测试数据，不依赖源表
-- 说明：
--   - cst_id：442 开头的 18 位数字编号
--   - fd_dt：格式与 dep_bal_dt 一致（如 2015-03-15）
--   - 金额字段：纯数字带两位小数（如 38000.00）
--   - cst_mngacc_cstmgr_id：8 位数字员工编号
--   - cst_mngacc_inst_supr_insid：9 位数字机构编号（如 443536363）
-- 使用：
--   先执行 docs/sxd_profile.sql 建表，再执行本文件
--   CALL generate_sxd_profile(100);  -- 可改条数
-- ======================================================================

-- 清空旧数据（按需保留或注释掉）
TRUNCATE TABLE kjjr_ai_sxd_profile;

-- ---------------------------------------------------------------------
-- 生成数据的存储过程
-- 参数 p_count：生成多少条记录（NULL 或 0 表示默认 100 条）
-- ---------------------------------------------------------------------
DROP PROCEDURE IF EXISTS `generate_sxd_profile`;
DELIMITER $$
CREATE PROCEDURE `generate_sxd_profile`(IN p_count INT)
BEGIN
    DECLARE v_total INT DEFAULT 100;
    DECLARE v_i INT DEFAULT 0;
    DECLARE v_cst_id VARCHAR(200);
    DECLARE v_cst_nm VARCHAR(500);
    DECLARE v_fd_dt VARCHAR(50);
    DECLARE v_dep_bal_dt VARCHAR(50);
    DECLARE v_acc_start_dt VARCHAR(50);
    DECLARE v_credit_code VARCHAR(200);
    DECLARE v_rgst_cpamt VARCHAR(50);
    DECLARE v_arcptl_cpamt VARCHAR(50);
    DECLARE v_loan_amount VARCHAR(100);
    DECLARE v_loan_balance VARCHAR(100);
    DECLARE v_dep_bal VARCHAR(100);
    DECLARE v_dep_aadbal VARCHAR(100);
    DECLARE v_avg_12_isug_amt VARCHAR(100);
    DECLARE v_kc_score VARCHAR(50);
    DECLARE v_staff_code VARCHAR(50);
    DECLARE v_inst_no VARCHAR(50);
    DECLARE v_loan_flag INT;

    IF p_count IS NOT NULL AND p_count > 0 THEN
        SET v_total = p_count;
    END IF;

    WHILE v_i < v_total DO
        -- cst_id：442 开头 + 15 位随机数字，共 18 位
        SET v_cst_id = CONCAT('442', LPAD(FLOOR(RAND() * 1000000000000000), 15, '0'));

        -- 客户名称
        SET v_cst_nm = ELT(1 + FLOOR(RAND() * 20),
            '深圳市创新科技有限公司', '广州智汇数据有限公司', '北京星辰科技股份有限公司',
            '上海华芯半导体有限公司', '杭州云帆信息技术有限公司', '成都绿能环保科技有限公司',
            '武汉光谷生物医药有限公司', '南京智能装备制造有限公司', '苏州工业园区新材料有限公司',
            '珠海横琴金融科技股份有限公司', '深圳市前海云计算有限公司', '广州天河人工智能有限公司',
            '东莞智能制造有限公司', '佛山新能源科技有限公司', '中山生物科技有限公司',
            '惠州电子信息有限公司', '珠海现代物流有限公司', '深圳南山区文化创意有限公司',
            '广州黄埔区集成电路有限公司', '深圳宝安区精密仪器有限公司');

        -- 日期格式：yyyy-mm-dd（如 2015-03-15），fd_dt 与 dep_bal_dt 格式一致
        SET v_fd_dt = CONCAT(
            1990 + FLOOR(RAND() * 30), '-',
            LPAD(1 + FLOOR(RAND() * 12), 2, '0'), '-',
            LPAD(1 + FLOOR(RAND() * 28), 2, '0'));
        SET v_dep_bal_dt = CONCAT(
            2020 + FLOOR(RAND() * 6), '-',
            LPAD(1 + FLOOR(RAND() * 12), 2, '0'), '-',
            LPAD(1 + FLOOR(RAND() * 28), 2, '0'));
        SET v_acc_start_dt = CONCAT(
            2020 + FLOOR(RAND() * 6), '-',
            LPAD(1 + FLOOR(RAND() * 12), 2, '0'), '-',
            LPAD(1 + FLOOR(RAND() * 28), 2, '0'));

        -- 统一社会信用代码：18 位
        -- 结构：登记管理部门(1位) + 机构类别(1位) + 行政区划码(6位) + 组织机构代码(9位) + 校验码(1位)
        -- 企业示例：91 + 440300(深圳) + 9位字母数字 + 1位校验码
        SET v_credit_code = CONCAT(
            '91',
            ELT(1 + FLOOR(RAND() * 5), '440300', '440100', '110108', '310115', '330106'),
            UPPER(SUBSTR(MD5(RAND()), 1, 9)),
            SUBSTR('0123456789ABCDEFGHJKLMNPQRTUWXY', 1 + FLOOR(RAND() * 31), 1));

        -- 金额字段：纯数字带两位小数
        SET v_rgst_cpamt = TRIM(FORMAT(500000.00 + RAND() * 50000000.00, 2));
        SET v_rgst_cpamt = REPLACE(v_rgst_cpamt, ',', '');
        SET v_arcptl_cpamt = TRIM(FORMAT(200000.00 + RAND() * 30000000.00, 2));
        SET v_arcptl_cpamt = REPLACE(v_arcptl_cpamt, ',', '');

        -- 是否我行贷款客户：约 60% 为 1
        SET v_loan_flag = IF(RAND() < 0.6, 1, 0);

        IF v_loan_flag = 1 THEN
            SET v_loan_amount = TRIM(FORMAT(1000000.00 + RAND() * 50000000.00, 2));
            SET v_loan_amount = REPLACE(v_loan_amount, ',', '');
            SET v_loan_balance = TRIM(FORMAT(RAND() * 30000000.00, 2));
            SET v_loan_balance = REPLACE(v_loan_balance, ',', '');
        ELSE
            SET v_loan_amount = NULL;
            SET v_loan_balance = NULL;
        END IF;

        SET v_dep_bal = TRIM(FORMAT(1000.00 + RAND() * 10000000.00, 2));
        SET v_dep_bal = REPLACE(v_dep_bal, ',', '');
        SET v_dep_aadbal = TRIM(FORMAT(500.00 + RAND() * 5000000.00, 2));
        SET v_dep_aadbal = REPLACE(v_dep_aadbal, ',', '');
        SET v_avg_12_isug_amt = TRIM(FORMAT(10000.00 + RAND() * 500000.00, 2));
        SET v_avg_12_isug_amt = REPLACE(v_avg_12_isug_amt, ',', '');

        -- 科创分：360.00 附近，约 20% 没匹配到为 -99
        IF RAND() < 0.2 THEN
            SET v_kc_score = '-99';
        ELSE
            SET v_kc_score = TRIM(FORMAT(300.00 + RAND() * 200.00, 2));
            SET v_kc_score = REPLACE(v_kc_score, ',', '');
        END IF;

        -- 管户客户经理编号：8 位数字
        SET v_staff_code = LPAD(10000001 + FLOOR(RAND() * 1000), 8, '0');
        -- 管户支行编号：9 位数字，类似 443536363
        SET v_inst_no = CAST(443536000 + FLOOR(RAND() * 1000) AS CHAR);

        INSERT IGNORE INTO kjjr_ai_sxd_profile (
            data_bsn_dt, cst_id, cst_nm, fd_dt, lgl_rprs_nm, act_cntlr_nm,
            rgst_cpamt, arcptl_cpamt, credit_code, CPCT_TPCD, entp_sz_cd,
            dtl_adr, org_oprt_scop_dsc, entp_bliy, tech_tag, tech_flow, kc_score,
            ENTP_PTNT_NUM, ENTPPRCTNEWTPPTNT_NUM, ENTP_IVT_PTNT_NUM, CLST5YRINNRSWCOPR_NUM,
            if_loan, product_name, loan_amount, loan_term, loan_balance,
            dep_bal, dep_bal_dt, dep_aadbal, acc_start_dt, acc_type,
            isug_pnum, avg_12_isug_amt, if_yuqi, ltgtrltd_ind, if_rad_alarm,
            cst_mngacc_cstmgr_id, cst_mngacc_inst_supr_insid,
            byzd1, byzd2, byzd3, byzd4, byzd5, byzd6, byzd7, byzd8, byzd9, byzd10
        ) VALUES (
            CURDATE(), v_cst_id, v_cst_nm, v_fd_dt,
            ELT(1 + FLOOR(RAND() * 10), '王建国','李明辉','张伟强','刘志强','陈永华','杨志刚','赵建华','黄文杰','周志远','吴国栋'),
            ELT(1 + FLOOR(RAND() * 10), '王美','李芳','张华','刘敏','陈静','杨丽','赵娟','黄婷','周雪','吴琴'),
            v_rgst_cpamt, v_arcptl_cpamt, v_credit_code,
            ELT(1 + FLOOR(RAND() * 4), '有限责任公司','股份有限公司','合伙企业','个人独资企业'),
            '小型',
            CONCAT('深圳市南山区科技园', 1 + FLOOR(RAND() * 100), '号'),
            '计算机软硬件技术开发、技术咨询、技术服务；电子产品的销售；经营进出口业务。',
            '批发和零售业-零售业-综合零售-其他综合零售',
            ELT(1 + FLOOR(RAND() * 3), '科技型中小企业 专精特新小巨人 创新型中小企业', '科技型中小企业 创新型中小企业', '科技型中小企业'),
            -- tech_flow：T1~T9，约 20% 没匹配到为 -
            IF(RAND() < 0.2, '-', CONCAT('T', 1 + FLOOR(RAND() * 9))),
            v_kc_score,
            -- 专利类数量：约 20% 没匹配到为 -99
            IF(RAND() < 0.2, '-99', CAST(FLOOR(RAND() * 50) AS CHAR)),
            IF(RAND() < 0.2, '-99', CAST(FLOOR(RAND() * 30) AS CHAR)),
            IF(RAND() < 0.2, '-99', CAST(FLOOR(RAND() * 20) AS CHAR)),
            IF(RAND() < 0.2, '-99', CAST(FLOOR(RAND() * 30) AS CHAR)),
            CAST(v_loan_flag AS CHAR),
            IF(v_loan_flag = 1, ELT(1 + FLOOR(RAND() * 3), '流动资金贷款','固定资产贷款','科技信用贷'), NULL),
            v_loan_amount,
            IF(v_loan_flag = 1, CAST(6 + FLOOR(RAND() * 54) AS CHAR), NULL),
            v_loan_balance,
            v_dep_bal, v_dep_bal_dt, v_dep_aadbal, v_acc_start_dt,
            ELT(1 + FLOOR(RAND() * 3), '基本户','一般户','专户'),
            CAST(1 + FLOOR(RAND() * 500) AS CHAR),
            v_avg_12_isug_amt,
            CAST(FLOOR(RAND() * 2) AS CHAR),
            CAST(FLOOR(RAND() * 2) AS CHAR),
            '0',
            v_staff_code, v_inst_no,
            NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL
        );

        SET v_i = v_i + 1;
    END WHILE;

    SELECT COUNT(*) AS inserted_rows FROM kjjr_ai_sxd_profile;
END$$
DELIMITER ;

-- ---------------------------------------------------------------------
-- 执行：生成 100 条测试数据
-- ---------------------------------------------------------------------
CALL generate_sxd_profile(100);

-- 验证
SELECT COUNT(*) AS total_rows FROM kjjr_ai_sxd_profile;
SELECT cst_id, cst_nm, fd_dt, rgst_cpamt, loan_amount, dep_bal, dep_bal_dt, kc_score, tech_flow,
       cst_mngacc_cstmgr_id, cst_mngacc_inst_supr_insid
FROM kjjr_ai_sxd_profile LIMIT 10;