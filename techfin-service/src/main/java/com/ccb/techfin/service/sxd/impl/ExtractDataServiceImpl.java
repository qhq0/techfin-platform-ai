package com.ccb.techfin.service.sxd.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.ccb.techfin.common.exception.BusinessException;
import com.ccb.techfin.common.util.UrlSecurityUtils;
import com.ccb.techfin.dao.sxd.DocEntryMapper;
import com.ccb.techfin.dao.sxd.ExtractDataMapper;
import com.ccb.techfin.dao.sxd.SxdMapper;
import com.ccb.techfin.model.external.*;
import com.ccb.techfin.model.sxd.dto.response.ExtractDataItem;
import com.ccb.techfin.model.sxd.dto.response.FinanceExportResult;
import com.ccb.techfin.model.sxd.entity.*;
import com.ccb.techfin.service.sxd.CustomerService;
import com.ccb.techfin.service.sxd.ExtractDataService;
import com.ccb.techfin.service.sxd.support.AbnormalFinanceDescriber;
import com.ccb.techfin.service.sxd.support.BpExtractSources;
import com.ccb.techfin.service.sxd.support.BusinessOverviewSummarizer;
import com.ccb.techfin.service.sxd.support.BusinessProfileSummarizer;
import com.ccb.techfin.service.sxd.support.DirectorResumeSummarizer;
import com.ccb.techfin.service.external.config.ApiProperties;
import com.ccb.techfin.service.external.config.RestClientConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.xmlbeans.XmlCursor;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTText;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.*;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 提取数据服务实现：要素提取、导出、报告生成。
 *
 * @author qiuhaoquan
 * @since 2026-07-23
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExtractDataServiceImpl implements ExtractDataService {

    private final SxdMapper sxdMapper;
    private final DocEntryMapper docEntryMapper;
    private final ExtractDataMapper extractDataMapper;
    private final ApiProperties apiProperties;
    /** 导出文件下载（响应体为 xlsx 字节流） */
    @Qualifier(RestClientConfig.DOWNLOAD_REST_CLIENT)
    private final RestClient downloadRestClient;

    /** 轻量接口：批量新增、资料详情、数据查询、状态轮询 */
    @Qualifier(RestClientConfig.API_REST_CLIENT)
    private final RestClient apiRestClient;
    private final CustomerService customerService;
    private final ResourceLoader resourceLoader;
    /** 「企业主营业务情况」段落生成器（两段提取文本 → 大模型二次总结） */
    private final BusinessOverviewSummarizer businessOverviewSummarizer;

    /** 「异常财务数据说明」段落生成器（超阈值科目清单 → 大模型成文） */
    private final AbnormalFinanceDescriber abnormalFinanceDescriber;

    /** 「实际控制人及团队简介」段落生成器（人员简历多行文本 + 实控人姓名 → 大模型二次总结） */
    private final DirectorResumeSummarizer directorResumeSummarizer;

    /** 「客户基本概况」四字段生成器（主营业务概况 / 下游主要服务 / 企业亮点描述 / 专利技术应用情况及创投投资情况） */
    private final BusinessProfileSummarizer businessProfileSummarizer;

    /**
     * 用于把「多步写」收敛成单个原子事务。
     * <p>
     * 两个原因让它不用 {@code @Transactional}：
     * </p>
     * <ol>
     *   <li>写操作都在私有方法里被同类方法调用，自调用不经过 Spring 代理，注解会<b>静默失效</b>；</li>
     *   <li>这两个流程里夹着大量外部平台 HTTP 调用（单次超时 30s），圈进事务会长时间占用数据库连接，
     *       集群下容易打满连接池。</li>
     * </ol>
     * <p>
     * 这里注入 {@link PlatformTransactionManager} 自行构造模板，而<b>不</b>直接注入
     * {@code TransactionTemplate} bean：后者的自动装配带 {@code @ConditionalOnSingleCandidate}，
     * 其判定依赖 bean 定义的注册顺序，在配置类里声明事务管理器时并不可靠（实测会拿不到该 bean，
     * 直接启动失败）。项目本身大量使用 {@code @Transactional}，事务管理器必然存在且唯一。
     * </p>
     */
    private final PlatformTransactionManager transactionManager;

    /**
     * 构造一个编程式事务模板。{@link TransactionTemplate} 是轻量的配置对象，随用随建、线程安全。
     */
    private TransactionTemplate txTemplate() {
        return new TransactionTemplate(transactionManager);
    }

    @Value("${report.template-path}")
    private String reportTemplatePath;

    /** 商业计划书提取数据查询的 tableName 列表（按展示顺序） */
    private static final List<String> BUSINESS_PLAN_TABLES = Collections.unmodifiableList(
            Arrays.asList(
                    "dib_manage_company_profile",
                    "dib_director_keyresume",
                    "dib_manage_business_and_products",
                    "dib_manage_business_circumstance",
                    "dib_company_qualification",
                    "dib_manage_progressiveness_description",
                    "dib_manage_competitive_advantages",
                    "dib_manage_development_strategy",
                    "dib_manage_y_industry_analysis"
            ));

    /**
     * 商业计划书「实际控制人及团队简介」提取表名（人员简历）。
     * <p>
     * 它同时就是报告模板里的占位符名 {@code {{dib_director_keyresume}}}：本段落<b>复用同名键</b>，
     * 只是把"原始多行简历文本"换成经 {@link DirectorResumeSummarizer} 二次总结后的文字，模板零改动。
     */
    private static final String TABLE_DIRECTOR_KEYRESUME = "dib_director_keyresume";

    /**
     * 「企业主营业务情况」占位符名。
     * <p>
     * 由 {@link BpExtractSources#SOURCES} 列出的各段提取文本（2026-10-08 起 7 段，原为 2 段）
     * 经大模型二次总结编辑后合并而来，替代原先报告中并列的原始提取文本。
     * <p>
     * 它不是外部平台的表名，自成一套命名只为和模板里既有的 {@code {{dib_*}}} 占位符保持观感一致。
     */
    private static final String PLACEHOLDER_BUSINESS_OVERVIEW = "dib_manage_business_overview";

    /**
     * 「异常财务数据说明」占位符名。
     * <p>
     * 内容由 {@link AbnormalFinanceDescriber} 依据"增长率超阈值科目清单"生成，清单本身由本类
     * 用报表同一套计算函数筛出（见 {@link #buildAbnormalFinanceMaterial}）。
     */
    private static final String PLACEHOLDER_ABNORMAL_FINANCE = "abnormal_finance_description";

    /**
     * 「异常财务数据说明」的入选阈值：相邻两期<b>年末</b>增长率的<b>绝对值</b>严格大于该值（%）。
     * <p>
     * 取绝对值而非带符号值：净利润同比下降 54% 与同比上升 54% 同样是"异常"，报告都得说明。
     * 判定用的是保留 2 位小数后的值，与报表单元格里显示的数字口径一致 ——
     * 避免出现"表里显示 30.00%、说明里却把它列为超 30% 的异常科目"。
     */
    private static final BigDecimal ABNORMAL_GROWTH_THRESHOLD = new BigDecimal("30");

    /** 「异常财务数据说明」素材里两张表的小标题 */
    private static final String ABNORMAL_SECTION_BALANCE = "【资产负债表】";
    private static final String ABNORMAL_SECTION_PROFIT = "【利润表】";

    /** zip 内「未能导出」清单的文件名（仅在有失败时写入） */
    private static final String EXPORT_FAILURE_MANIFEST_NAME = "导出失败清单.txt";

    /**
     * 单个导出文件上限。导出物是 xlsx，正常只有几百 KB 量级；16MB 已远超正常值，
     * 超限即视为异常响应，立刻中断读取。
     * <p>刻意<b>不</b>与上传侧 50MB 对齐：上传的是扫描件 / PDF 等原始材料，导出的是结构化表格，
     * 两者体量不具可比性；对齐只会给异常响应留一个 50MB 的空子。
     */
    private static final long MAX_EXPORT_FILE_BYTES = 16L * 1024 * 1024;

    /**
     * 一次导出的累计上限。zip 须完整驻留内存（接口按 {@code byte[]} 返回，
     * 且 {@code toByteArray()} 会再复制一份），所以闸门要对着 <b>堆</b> 设，不能对着
     * 「能装下多少份」设：
     * <pre>峰值 ≈ 2 × 本值（zip 缓冲 + 复制） + 2 × {@link #MAX_EXPORT_FILE_BYTES}（单份下载缓冲）</pre>
     * 取 32MB ⇒ 最坏约 96MB，在 {@code -Xmx512m}（见 {@code starts.sh}）下给 POI 生成报告
     * 和请求线程留足余量。按单份 2MB 计可容纳 16 份，覆盖正常任务（通常 4~6 份文档）。
     */
    private static final long MAX_EXPORT_TOTAL_BYTES = 32L * 1024 * 1024;

    /** 资产负债表关键科目名称（按展示顺序） */
    private static final List<String> BALANCE_SHEET_KEY_ITEMS = Collections.unmodifiableList(
            Arrays.asList(
                    "资产总计", "流动资产", "应收账款", "预付款项", "其他应收款", "存货", "固定资产",
                    "负债合计", "流动负债", "短期借款", "应付账款", "预收款项", "非流动负债", "长期借款",
                    "所有者权益", "实收资本", "未分配利润"
            ));

    /** 利润表关键科目名称（按展示顺序，不含计算行） */
    private static final List<String> PROFIT_SHEET_KEY_ITEMS = Collections.unmodifiableList(
            Arrays.asList(
                    "营业收入", "营业成本", "管理费用", "销售费用", "财务费用", "研发费用",
                    "营业利润", "利润总额", "净利润"
            ));

    /** 利润表科目匹配关键词：显示名称 → 可能的 item_standard 值 */
    private static final Map<String, List<String>> PROFIT_ITEM_SEARCH_KEYS = Map.of(
            "营业收入", Arrays.asList("其中：营业收入"),
            "营业成本", Arrays.asList("其中：营业成本"),
            "管理费用", Arrays.asList("管理费用"),
            "销售费用", Arrays.asList("销售费用"),
            "财务费用", Arrays.asList("财务费用"),
            "研发费用", Arrays.asList("研发费用"),
            "营业利润", Arrays.asList("营业利润"),
            "利润总额", Arrays.asList("利润总额"),
            "净利润", Arrays.asList("净利润")
    );

    private static final String ITEM_ASSET_TOTAL = "资产总计";
    private static final String ITEM_LIABILITY_TOTAL = "负债合计";

    private static final String ITEM_REVENUE = "营业收入";
    private static final String ITEM_COST = "营业成本";
    private static final String ITEM_NET_PROFIT = "净利润";
    private static final String ITEM_RD_EXPENSE = "研发费用";

    // 报表里的「计算行」标签。抽成常量是因为它们同时被三类代码引用：填表（fillBalanceSheetTable /
    // fillProfitSheetTable）、算比率（computeRatioForLabel）、以及筛异常科目
    // （buildAbnormalFinanceMaterial）。写死字面量迟早会出现"改了一处漏一处"。

    /** 资产负债表计算行：负债合计 / 资产总计 */
    private static final String RATIO_LIABILITY = "资产负债率";

    /** 利润表计算行：(营业收入 - 营业成本) / 营业收入 */
    private static final String RATIO_GROSS_MARGIN = "毛利润率";

    /** 利润表计算行：净利润 / 营业收入 */
    private static final String RATIO_NET_MARGIN = "净利润率";

    /** 利润表计算行：研发费用 / 营业收入 */
    private static final String RATIO_RD = "研发费用占比";

    /** 资产负债表的计算行（不含科目，金额单位不适用，值为百分数） */
    private static final List<String> BALANCE_SHEET_RATIO_ITEMS = Collections.singletonList(RATIO_LIABILITY);

    /** 利润表的计算行（不含科目，金额单位不适用，值为百分数） */
    private static final List<String> PROFIT_SHEET_RATIO_ITEMS =
            Collections.unmodifiableList(Arrays.asList(RATIO_GROSS_MARGIN, RATIO_NET_MARGIN, RATIO_RD));

    /** 元 转 万元 */
    private static final BigDecimal TEN_THOUSAND = new BigDecimal("10000");

    /** 占位符名称 -> CustomerProfile 字段值提取函数（含格式化转换） */
    private static final Map<String, Function<CustomerProfile, String>> PROFILE_FIELD_GETTERS = Map.ofEntries(
            Map.entry("cst_nm", CustomerProfile::getCstNm),
            Map.entry("credit_code", CustomerProfile::getCreditCode),
            // fd_dt：数据库存储为 ddMMMyyyy（如 15Mar2015），填充时转为 xx年xx月xx日
            Map.entry("fd_dt", p -> formatDateZh(p.getFdDt())),
            Map.entry("lgl_rprs_nm", CustomerProfile::getLglRprsNm),
            // 金额字段：数据库以元为单位存储，填充时转为以万为单位（如 220000.00 → 22.00）
            Map.entry("rgst_cpamt", p -> formatAmountWan(p.getRgstCpamt())),
            Map.entry("arcptl_cpamt", p -> formatAmountWan(p.getArcptlCpamt())),
            Map.entry("cpct_tpcd", CustomerProfile::getCpctTpcd),
            Map.entry("entp_sz_cd", CustomerProfile::getEntpSzCd),
            Map.entry("entp_bliy", CustomerProfile::getEntpBliy),
            Map.entry("dtl_adr", CustomerProfile::getDtlAdr),
            Map.entry("org_oprt_scop_dsc", CustomerProfile::getOrgOprtScopDsc),
            Map.entry("tech_tag", CustomerProfile::getTechTag),
            Map.entry("tech_flow", CustomerProfile::getTechFlow),
            Map.entry("kc_score", CustomerProfile::getKcScore),
            Map.entry("entp_ptnt_num", CustomerProfile::getEntpPtntNum),
            Map.entry("entp_prct_new_tp_ptnt_num", CustomerProfile::getEntpPrctNewTpPtntNum),
            Map.entry("entp_ivt_ptnt_num", CustomerProfile::getEntpIvtPtntNum),
            Map.entry("clst_5yr_inn_rs_wcopr_num", CustomerProfile::getClst5YrInnRsWcoprNum),
            // if_loan：1 → 存量，0 → 新增
            Map.entry("if_loan", p -> formatLoanStatus(p.getIfLoan())),
            Map.entry("product_name", CustomerProfile::getProductName),
            Map.entry("loan_amount", p -> formatAmountWan(p.getLoanAmount())),
            // loan_term：以月为单位，填充原值
            Map.entry("loan_term", CustomerProfile::getLoanTerm),
            Map.entry("loan_balance", p -> formatAmountWan(p.getLoanBalance())),
            Map.entry("dep_bal", p -> formatAmountWan(p.getDepBal())),
            // dep_bal_dt：ddMMMyyyy → xx年xx月xx日
            Map.entry("dep_bal_dt", p -> formatDateZh(p.getDepBalDt())),
            Map.entry("dep_aadbal", p -> formatAmountWan(p.getDepAadbal())),
            // acc_start_dt：ddMMMyyyy → xx年xx月xx日
            Map.entry("acc_start_dt", p -> formatDateZh(p.getAccStartDt())),
            Map.entry("acc_type", CustomerProfile::getAccType),
            Map.entry("isug_pnum", CustomerProfile::getIsugPnum),
            Map.entry("avg_12_isug_amt", p -> formatAmountWan(p.getAvg12IsugAmt())),
            // 0/1 → 否/是
            Map.entry("if_yuqi", p -> formatYesNo(p.getIfYuqi())),
            Map.entry("ltgtrltd_ind", p -> formatYesNo(p.getLtgtrltdInd())),
            Map.entry("if_rad_alarm", p -> formatYesNo(p.getIfRadAlarm()))
    );

    /** 填充到文档的目标日期格式 */
    private static final DateTimeFormatter DOC_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy年M月d日");
    /** 解析 dMMMyyyy（如 5Mar2015） */
    private static final DateTimeFormatter DOC_DATE_PARSE_SINGLE_DAY =
            DateTimeFormatter.ofPattern("dMMMyyyy", Locale.ENGLISH);
    /** 解析 ddMMMyyyy（如 15Mar2015） */
    private static final DateTimeFormatter DOC_DATE_PARSE_DOUBLE_DAY =
            DateTimeFormatter.ofPattern("ddMMMyyyy", Locale.ENGLISH);

    /**
     * 将数据库日期（dMMMyyyy 或 ddMMMyyyy，如 5Mar2015 / 15Mar2015）格式化为 xx年xx月xx日。
     * 解析失败时原样返回。
     */
    private static String formatDateZh(String raw) {
        if (!StringUtils.hasText(raw)) return "";
        String s = raw.trim();
        // 日可能是一位（5Mar2015）或两位（15Mar2015），用 "d" 兼容两种情况
        DateTimeFormatter[] formatters = new DateTimeFormatter[]{
                DOC_DATE_PARSE_SINGLE_DAY, DOC_DATE_PARSE_DOUBLE_DAY
        };
        for (DateTimeFormatter f : formatters) {
            try {
                return LocalDate.parse(s, f).format(DOC_DATE_FORMATTER);
            } catch (Exception ignore) {
            }
        }
        // 兜底：尝试标准 yyyy-MM-dd 等格式
        try {
            return LocalDate.parse(s).format(DOC_DATE_FORMATTER);
        } catch (Exception e) {
            return raw;
        }
    }

    /**
     * 将以元为单位的金额字符串转为以万元为单位的字符串（2 位小数）。
     * 如 "220000.00" → "22.00"。无法解析时原样返回。
     */
    private static String formatAmountWan(String raw) {
        if (!StringUtils.hasText(raw)) return "";
        try {
            BigDecimal yuan = new BigDecimal(raw.trim());
            BigDecimal wan = yuan.divide(TEN_THOUSAND, 2, RoundingMode.HALF_UP);
            return wan.toPlainString();
        } catch (Exception e) {
            return raw;
        }
    }

    /**
     * if_loan 转换：1 → 存量，0 → 新增，其他值原样返回。
     */
    private static String formatLoanStatus(String raw) {
        if (!StringUtils.hasText(raw)) return "";
        return switch (raw.trim()) {
            case "1" -> "存量";
            case "0" -> "新增";
            default -> raw;
        };
    }

    /**
     * 0/1 标志位转换为 否/是。其他值原样返回。
     */
    private static String formatYesNo(String raw) {
        if (!StringUtils.hasText(raw)) return "";
        return switch (raw.trim()) {
            case "1" -> "是";
            case "0" -> "否";
            default -> raw;
        };
    }

    /** 每个 tableName 对应的文本提取函数 */
    private static final Map<String, Function<BpExtractRecord, String>> TEXT_EXTRACTORS = Map.of(
            "dib_manage_company_profile", BpExtractRecord::getCompanyProfileText,
            "dib_director_keyresume", ExtractDataServiceImpl::formatDirectorResume,
            "dib_manage_business_and_products", BpExtractRecord::getBusinessAndProductsText,
            "dib_manage_business_circumstance", BpExtractRecord::getText,
            "dib_company_qualification", BpExtractRecord::getText,
            "dib_manage_progressiveness_description", BpExtractRecord::getProgressivenessText,
            "dib_manage_competitive_advantages", BpExtractRecord::getCompetitivenessText,
            "dib_manage_development_strategy", BpExtractRecord::getStrategyText,
            "dib_manage_y_industry_analysis", BpExtractRecord::getText
    );

    /**
     * 格式化人员简历文本。
     * 格式：职位：position，简介：resume
     */
    private static String formatDirectorResume(BpExtractRecord r) {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(r.getPosition())) {
            sb.append("职位：").append(r.getPosition());
        }
        if (StringUtils.hasText(r.getResume())) {
            if (sb.length() > 0) sb.append("，");
            sb.append("简介：").append(r.getResume());
        }
        return sb.toString();
    }

    @Override
    public List<ExtractDataItem> queryBusinessExtractData(String taskId) {
        // 查商业计划书类型（businessType = docType.business 的值）的文档
        String businessDocType = String.valueOf(apiProperties.getDocType().get("business"));
        List<DocEntry> docEntries = docEntryMapper.selectList(
                new LambdaQueryWrapper<DocEntry>()
                        .eq(DocEntry::getTaskId, taskId)
                        .eq(DocEntry::getBusinessType, businessDocType));

        if (docEntries == null || docEntries.isEmpty()) {
            throw new BusinessException("DOC_NOT_FOUND",
                    "任务 [" + taskId + "] 下未找到商业计划书文档");
        }

        // 先查缓存
        List<ExtractData> cachedList = extractDataMapper.selectList(
                new LambdaQueryWrapper<ExtractData>()
                        .eq(ExtractData::getTaskId, taskId));

        // 只有缓存包含全部 BUSINESS_PLAN_TABLES 才直接返回，否则视为不完整，重新拉取后整体覆盖
        if (cachedList != null && !cachedList.isEmpty()) {
            Set<String> cachedTables = cachedList.stream()
                    .map(ExtractData::getTableName)
                    .collect(Collectors.toSet());
            if (cachedTables.containsAll(BUSINESS_PLAN_TABLES)) {
                return buildResponseFromCache(cachedList);
            }
            log.info("Cache incomplete for taskId={} ({} of {} tables), re-fetching from external API",
                    taskId, cachedTables.size(), BUSINESS_PLAN_TABLES.size());
        }

        // 缓存未命中或不完整：先在事务外把外部调用全部做完，避免慢接口长时间占着数据库连接
        List<ExtractData> fetched = new ArrayList<>();

        for (DocEntry entry : docEntries) {
            Long docId;
            try {
                docId = Long.parseLong(entry.getDocId());
            } catch (NumberFormatException e) {
                log.warn("Invalid docId format: {}", entry.getDocId());
                throw new BusinessException("DOC_DATA_FAILED",
                        "文档 ID 格式无效：" + entry.getDocId());
            }

            for (String tableName : BUSINESS_PLAN_TABLES) {
                List<String> texts = queryBusinessExtractDataByTable(docId, tableName);
                String mergedText = String.join("\n", texts);
                ExtractData extractData = new ExtractData();
                extractData.setTaskId(taskId);
                extractData.setDocId(entry.getDocId());
                extractData.setTableName(tableName);
                extractData.setText(mergedText);
                fetched.add(extractData);
            }
        }

        // 写回缓存：upsert + 清理差集（整体覆盖语义，详见 replaceExtractDataCache 的注释）。
        // 唯一性由表上的唯一键 uk_task_doc_table 保证，不再依赖「缺索引导致的间隙锁」这个副作用，
        // 也不再需要那条会全表加锁的 DELETE WHERE task_id。
        replaceExtractDataCache(taskId, fetched);

        // 从缓存表读取并构建响应
        cachedList = extractDataMapper.selectList(
                new LambdaQueryWrapper<ExtractData>()
                        .eq(ExtractData::getTaskId, taskId));
        return buildResponseFromCache(cachedList);
    }

    /**
     * 以「upsert + 清理差集」整体覆盖某个任务的提取数据缓存，全部写入在同一事务内完成。
     *
     * <p>
     * 2026-10-08 由「先删后插」改为本实现，原因：
     * </p>
     * <ol>
     *   <li>原来的 {@code DELETE ... WHERE task_id = ?} 在缺索引时走全表扫；REPEATABLE READ 下
     *       InnoDB 会对扫过的每一行加 Next-Key Lock ⇒ <b>近似全表加锁</b>，把其它任务的写一起挡住
     *       （实测：删一个一行都没有的任务，仍会让另一个任务那行的 UPDATE 阻塞 2.79s 至超时）。</li>
     *   <li>两个事务「同范围 DELETE + 同范围 INSERT」必然在同一片间隙锁上互撞 ⇒ 并发重入时约九成
     *       报 1213 死锁 / 1205 锁等待超时。改成 upsert 后该冲突点消失（实测 52 次报错 → 0）。</li>
     *   <li>唯一性不再依赖「缺索引导致的间隙锁」这个副作用，而是由唯一键
     *       {@code uk_task_doc_table} 保证；该键最左前缀是 task_id，顺带让本表的 DELETE 与全部读取走索引。</li>
     * </ol>
     *
     * <p>
     * ⚠️ 自定义 SQL 不触发 MyBatis-Plus 的自动填充与 ASSIGN_ID 发号，故这里显式填
     * {@code id} / {@code createdAt} / {@code updatedAt}。其中 {@code IdWorker.getId()} 取的正是
     * {@code mybatis-plus.global-config.sequence.*} 配置的节点标识（MP 构建 SqlSessionFactory 时
     * 会把该 generator 注入 IdWorker 的静态字段），集群下与 MP 自动发号同源、不会撞。
     * </p>
     *
     * @param taskId 任务 ID
     * @param rows   本次从外部接口新拉到的缓存行
     */
    private void replaceExtractDataCache(String taskId, List<ExtractData> rows) {
        LocalDateTime now = LocalDateTime.now();
        txTemplate().executeWithoutResult(status -> {
            for (ExtractData row : rows) {
                row.setId(IdWorker.getId());
                row.setCreatedAt(now);
                row.setUpdatedAt(now);
                extractDataMapper.upsert(row);
            }
            // 清掉「上一轮有、本轮不再产出」的残留行，保持原本的整体覆盖语义。
            // 注意是按 (doc_id, table_name) 差集删，而不是 DELETE WHERE task_id —— 后者会全表加锁。
            if (!rows.isEmpty()) {
                extractDataMapper.deleteStaleByKeys(taskId, rows);
            }
        });
    }

    /**
     * 从缓存表记录构建响应列表，按 BUSINESS_PLAN_TABLES 顺序排列。
     */
    private List<ExtractDataItem> buildResponseFromCache(List<ExtractData> cachedList) {
        // 按 tableName 分组，同一 tableName 多条记录时换行合并
        Map<String, String> tableTextMap = new LinkedHashMap<>();
        for (String tableName : BUSINESS_PLAN_TABLES) {
            tableTextMap.put(tableName, "");
        }
        for (ExtractData item : cachedList) {
            tableTextMap.merge(item.getTableName(), item.getText(),
                    (existing, incoming) -> existing.isEmpty() ? incoming : existing + "\n" + incoming);
        }

        List<ExtractDataItem> extractData = new ArrayList<>();
        for (String tableName : BUSINESS_PLAN_TABLES) {
            String text = tableTextMap.getOrDefault(tableName, "");
            extractData.add(new ExtractDataItem(tableName, text));
        }
        return extractData;
    }

    @Override
    public byte[] exportBusinessExtractData(String taskId) {
        String businessDocType = String.valueOf(apiProperties.getDocType().get("business"));
        List<DocEntry> entries = docEntryMapper.selectList(
                new LambdaQueryWrapper<DocEntry>()
                        .eq(DocEntry::getTaskId, taskId)
                        .eq(DocEntry::getBusinessType, businessDocType));

        if (entries == null || entries.isEmpty()) {
            throw new BusinessException("DOC_NOT_FOUND",
                    "任务 [" + taskId + "] 下未找到商业计划书文档");
        }

        // 商业计划书仅单个文件，取第一个文档
        return downloadExportFile(entries.get(0).getDocId());
    }

    @Override
    public FinanceExportResult exportFinanceExtractData(String taskId) {
        String financeDocType = String.valueOf(apiProperties.getDocType().get("finance"));
        List<DocEntry> entries = docEntryMapper.selectList(
                new LambdaQueryWrapper<DocEntry>()
                        .eq(DocEntry::getTaskId, taskId)
                        .eq(DocEntry::getBusinessType, financeDocType));

        if (entries == null || entries.isEmpty()) {
            throw new BusinessException("DOC_NOT_FOUND",
                    "任务 [" + taskId + "] 下未找到财务报表文档");
        }

        return buildFinanceZip(entries, taskId);
    }

    /**
     * 逐个下载并<b>就地</b>写入 zip：下载完一个就丢掉字节，不再把所有 xlsx 攒在内存里
     * （旧写法是先把 N 份收进 {@code List<byte[]>} 再 zip 一份，峰值约 2 倍总量）。
     * <p>失败的文档收进返回值的 failures，并写一份清单到 zip 内 ——
     * 避免「拿到少了几份的 zip，界面却显示成功」。
     */
    private FinanceExportResult buildFinanceZip(List<DocEntry> entries, String taskId) {
        List<String> failures = new ArrayList<>();
        Map<String, Integer> dateCounter = new HashMap<>();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        long totalBytes = 0L;

        try (ZipOutputStream zos = new ZipOutputStream(baos, StandardCharsets.UTF_8)) {
            for (DocEntry entry : entries) {
                String label = "财务报表_" + reportDateLabel(entry) + "（docId=" + entry.getDocId() + "）";
                byte[] data;
                try {
                    data = downloadExportFile(entry.getDocId());
                } catch (BusinessException e) {
                    failures.add(label + "：" + e.getMessage());
                    log.warn("Failed to download export file for docId={}, skipping: {}",
                            entry.getDocId(), e.getMessage());
                    continue;
                }
                if (totalBytes + data.length > MAX_EXPORT_TOTAL_BYTES) {
                    failures.add(label + "：累计体积超过上限（"
                            + (MAX_EXPORT_TOTAL_BYTES / 1024 / 1024) + "MB），未打包");
                    log.warn("Export total size limit exceeded, skipping docId={}", entry.getDocId());
                    continue;
                }
                zos.putNextEntry(new ZipEntry(nextZipEntryName(entry, dateCounter)));
                zos.write(data);
                zos.closeEntry();
                totalBytes += data.length;
            }

            if (totalBytes == 0L) {
                throw new BusinessException("DOC_EXPORT_FAILED",
                        "任务 [" + taskId + "] 下所有财务报表导出均失败");
            }
            if (!failures.isEmpty()) {
                zos.putNextEntry(new ZipEntry(EXPORT_FAILURE_MANIFEST_NAME));
                zos.write(buildFailureManifest(failures));
                zos.closeEntry();
            }
        } catch (IOException e) {
            log.error("Failed to create zip archive", e);
            throw new BusinessException("ZIP_CREATE_FAILED",
                    "财务报表压缩包创建失败：" + e.getMessage());
        }

        return new FinanceExportResult(baos.toByteArray(), failures);
    }

    /** zip 内条目名：同一日期多份时加序号（与旧实现一致） */
    private static String nextZipEntryName(DocEntry entry, Map<String, Integer> dateCounter) {
        String baseName = reportDateLabel(entry);
        int count = dateCounter.merge(baseName, 1, Integer::sum);
        return count == 1
                ? "财务报表_" + baseName + ".xlsx"
                : "财务报表_" + baseName + "_" + count + ".xlsx";
    }

    private static String reportDateLabel(DocEntry entry) {
        return entry.getReportDate() != null && !entry.getReportDate().isEmpty()
                ? entry.getReportDate() : "未知日期";
    }

    private static byte[] buildFailureManifest(List<String> failures) {
        StringBuilder sb = new StringBuilder();
        sb.append("以下文档未能导出，其余文档已正常打包：").append("\r\n\r\n");
        for (String failure : failures) {
            sb.append("- ").append(failure).append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 调用外部导出资料接口，下载单个文档的 xlsx 文件字节流。
     */
    private byte[] downloadExportFile(String docId) {
        String url = apiProperties.getDocExportDataUrl() + "/" + docId;
        UrlSecurityUtils.assertNoCrlf(url);
        long startNanos = System.nanoTime();
        try {
            // 鉴权头由 downloadRestClient 的拦截器统一注入
            // 用 exchange 而不是 toEntity(byte[])：自己做「有上限的读取」，
            // 否则服务端返回超大或异常响应时会整份读进堆
            byte[] body = downloadRestClient.get()
                    .uri(url)
                    .exchange((request, response) -> readBounded(response, docId));

            if (body == null || body.length == 0) {
                throw new BusinessException("DOC_EXPORT_FAILED",
                        "文档 " + docId + " 导出数据为空");
            }
            // 耗时是校准 dib.download-timeout-seconds 的唯一依据：正常应在秒级；
            // 若长期贴近超时值，说明收紧该值会误伤（详见 ApiProperties 该字段注释）
            log.info("Exported doc data: docId={}, size={} bytes, elapsed={}ms",
                    docId, body.length, (System.nanoTime() - startNanos) / 1_000_000);
            return body;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to export doc data for docId={}", docId, e);
            throw new BusinessException("DOC_EXPORT_FAILED",
                    "文档 " + docId + " 导出失败：" + e.getMessage());
        }
    }

    /**
     * 有上限地读取导出响应体：声明值与实际字节数都卡。
     * <p>不信任 {@code Content-Length}（可能缺失或不准），超限立即中断，避免把超大响应读进堆。
     */
    private static byte[] readBounded(ClientHttpResponse response, String docId) throws IOException {
        // ⚠️ exchange 不像 retrieve 那样自动套状态处理器：非 2xx 必须自己拦，
        // 否则 404 的空体会被当成"没数据"，500 的错误页会被当成"导出成功"打进 zip
        HttpStatusCode status = response.getStatusCode();
        if (!status.is2xxSuccessful()) {
            throw new BusinessException("DOC_EXPORT_FAILED",
                    "文档 " + docId + " 导出失败：DIB 返回 HTTP " + status.value());
        }
        long declared = response.getHeaders().getContentLength();
        if (declared > MAX_EXPORT_FILE_BYTES) {
            throw new BusinessException("DOC_EXPORT_FAILED", oversizeMessage(docId, declared));
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        long total = 0L;
        try (InputStream in = response.getBody()) {
            int read;
            while ((read = in.read(chunk)) != -1) {
                total += read;
                if (total > MAX_EXPORT_FILE_BYTES) {
                    throw new BusinessException("DOC_EXPORT_FAILED", oversizeMessage(docId, total));
                }
                buffer.write(chunk, 0, read);
            }
        }
        return buffer.toByteArray();
    }

    private static String oversizeMessage(String docId, long bytes) {
        return "文档 " + docId + " 导出文件过大（" + (bytes / 1024 / 1024) + "MB，上限 "
                + (MAX_EXPORT_FILE_BYTES / 1024 / 1024) + "MB）";
    }

    /**
     * POST 外部 queryData 接口（docQueryDataUrl），返回原始响应体。
     * 异常交由调用方处理（各调用方的失败/异常语义不同）。
     */
    private ExternalResponse postQueryData(Long docId, String tableName) {
        BpExtractRequest request = new BpExtractRequest(docId, tableName);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<BpExtractRequest> requestEntity = new HttpEntity<>(request, headers);
        ResponseEntity<ExternalResponse> response = apiRestClient.post()
                .uri(apiProperties.getDocQueryDataUrl())
                .headers(h -> h.addAll(requestEntity.getHeaders()))
                .body(request)
                .retrieve()
                .toEntity(ExternalResponse.class);
        return response.getBody();
    }

    /**
     * 调用外部提取数据查询 API，返回该 tableName 下的文本列表。
     */
    private List<String> queryBusinessExtractDataByTable(Long docId, String tableName) {
        try {
            ExternalResponse respBody = postQueryData(docId, tableName);
            if (respBody == null || !respBody.isSuccess() || respBody.getData() == null) {
                log.warn("Query extract data returned empty for docId={}, tableName={}: {}",
                        docId, tableName, respBody != null ? respBody.getMessage() : "null");
                return Collections.emptyList();
            }

            List<BpExtractRecord> records = respBody.getDataAsList(BpExtractRecord.class);
            if (records == null || records.isEmpty()) {
                return Collections.emptyList();
            }

            Function<BpExtractRecord, String> extractor = TEXT_EXTRACTORS.get(tableName);
            if (extractor == null) {
                log.warn("No text extractor for tableName: {}", tableName);
                return Collections.emptyList();
            }

            return records.stream()
                    .map(extractor)
                    .filter(Objects::nonNull)
                    .filter(s -> !s.trim().isEmpty())
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.warn("Failed to query extract data for docId={}, tableName={}: {}", docId, tableName, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public byte[] generateReport(String taskId, String cstId, String userId) {
        // ============ 校验任务存在 ============
        SxdRecord record = sxdMapper.selectById(taskId);
        if (record == null) {
            throw new BusinessException("TASK_NOT_FOUND",
                    "任务 [" + taskId + "] 不存在");
        }

        // ============ 校验 cstId 与任务关联的客户编号一致 ============
        // 防止前端篡改 cstId 越权查询其他客户信息
        if (record.getCstId() != null && !record.getCstId().isEmpty()
                && !record.getCstId().equals(cstId)) {
            log.warn("CstId mismatch: taskId={}, recordCstId={}, requestCstId={}",
                    taskId, record.getCstId(), cstId);
            throw new BusinessException("CST_ID_MISMATCH",
                    "客户编号与任务不匹配");
        }

        // ============ 查询企业信息 ============
        CustomerProfile customerProfile = null;
        if (cstId != null && !cstId.isEmpty()) {
            try {
                customerProfile = customerService.getCustomerProfile(cstId);
            } catch (BusinessException e) {
                log.warn("Customer profile not found for cstId={}: {}", cstId, e.getMessage());
            }
        }

        String financeDocType = String.valueOf(apiProperties.getDocType().get("finance"));
        List<DocEntry> entries = docEntryMapper.selectList(
                new LambdaQueryWrapper<DocEntry>()
                        .eq(DocEntry::getTaskId, taskId)
                        .eq(DocEntry::getBusinessType, financeDocType));

        if (entries == null || entries.isEmpty()) {
            throw new BusinessException("DOC_NOT_FOUND",
                    "任务 [" + taskId + "] 下未找到财务报表文档");
        }

        // ============ 资产负债表 + 利润表处理（合并单次循环） ============
        Map<String, Map<String, BigDecimal>> bsItemDateValues = new LinkedHashMap<>();
        List<String> bsDateColumns = new ArrayList<>();
        Map<String, Map<String, BigDecimal>> psItemDateValues = new LinkedHashMap<>();
        List<String> psDateColumns = new ArrayList<>();

        for (DocEntry entry : entries) {
            Long docId;
            try {
                docId = Long.parseLong(entry.getDocId());
            } catch (NumberFormatException e) {
                log.warn("Invalid docId format, skipping: {}", entry.getDocId());
                continue;
            }

            // 一次查询表格提取状态，同时确定资产负债表和利润表的表类型
            List<DocTableStateRecord> states;
            try {
                states = queryDocTableStates(docId);
            } catch (BusinessException e) {
                log.warn("Query table states failed for docId={}: {}", docId, e.getMessage());
                continue;
            }
            // 同一 docId 下资产负债表与利润表都要判口径，共用一份缓存，避免对同一份文档重复查询
            Map<Long, String> scopeCache = new HashMap<>();
            String bsTableName = determineSheetTable(docId, states,
                    "dib_fin_balance", "dib_fin_balance_parent", "资产负债表", scopeCache);
            String psTableName = determineSheetTable(docId, states,
                    "dib_fin_profit_statement", "dib_fin_profit_statement_parent", "利润表", scopeCache);

            // 查询资产负债表数据
            List<FinanceRecord> bsRecords = null;
            try {
                bsRecords = queryFinanceData(docId, bsTableName);
            } catch (BusinessException e) {
                log.warn("Query failed for docId={} balance sheet: {}", docId, e.getMessage());
            }
            if (bsRecords == null || bsRecords.isEmpty()) {
                log.warn("No balance sheet data for docId={}", docId);
            }

            // 查询利润表数据
            List<FinanceRecord> psRecords = null;
            try {
                psRecords = queryFinanceData(docId, psTableName);
            } catch (BusinessException e) {
                log.warn("Query failed for docId={} profit sheet: {}", docId, e.getMessage());
            }
            if (psRecords == null || psRecords.isEmpty()) {
                log.warn("No profit sheet data for docId={}", docId);
            }

            // 提取 reportDate：优先从查询结果取，fallback 到 entry.getReportDate()
            String bsReportDate = (bsRecords != null && !bsRecords.isEmpty()) ? extractReportDateFromRecords(bsRecords) : null;
            String psReportDate = (psRecords != null && !psRecords.isEmpty()) ? extractReportDateFromRecords(psRecords) : null;
            String reportDate = bsReportDate != null && !bsReportDate.isEmpty() ? bsReportDate
                    : psReportDate != null && !psReportDate.isEmpty() ? psReportDate : entry.getReportDate();

            if (reportDate == null || reportDate.isEmpty()) {
                log.warn("No report date for docId={}, skipping", docId);
                continue;
            }

            String dateCol = formatDateColumn(reportDate);

            // 资产负债表聚合
            // 无条件先登记当前期日期列，确保即使该期查询数据为空，
            // 列头仍保留、数据格显示 "-"，与其它期对齐，避免整列丢失。
            if (!bsDateColumns.contains(dateCol)) {
                bsDateColumns.add(dateCol);
            }
            if (bsRecords != null) {
                for (FinanceRecord r : bsRecords) {
                    String itemName = r.getItem();
                    if (itemName != null && BALANCE_SHEET_KEY_ITEMS.contains(itemName)
                            && r.getCurrentAmount() != null) {
                        bsItemDateValues.computeIfAbsent(itemName, k -> new LinkedHashMap<>())
                                .put(dateCol, r.getCurrentAmount());
                    }
                }
                // 利用 lastAmount 补全缺失的上一期日期列
                fillLastAmountColumn(bsRecords, bsItemDateValues, bsDateColumns, reportDate);
            }

            // 利润表聚合
            // 同样无条件登记当前期日期列，避免空查询导致整列丢失。
            if (!psDateColumns.contains(dateCol)) {
                psDateColumns.add(dateCol);
            }
            if (psRecords != null) {
                for (String itemName : PROFIT_SHEET_KEY_ITEMS) {
                    BigDecimal value = findProfitItemValue(psRecords, itemName);
                    if (value != null) {
                        psItemDateValues.computeIfAbsent(itemName, k -> new LinkedHashMap<>())
                                .put(dateCol, value);
                    }
                }
                // 利用 lastAmount 补全缺失的上一期日期列
                fillLastAmountColumnForProfit(psRecords, psItemDateValues, psDateColumns, reportDate);
            }
        }

        // ============ 日期列降序排列（最新在前） ============
        bsDateColumns.sort(Comparator.comparingInt(ExtractDataServiceImpl::dateColumnToInt).reversed());
        psDateColumns.sort(Comparator.comparingInt(ExtractDataServiceImpl::dateColumnToInt).reversed());

        if (bsDateColumns.isEmpty()) {
            log.warn("No balance sheet data found for taskId={}, placeholder will be empty", taskId);
        }
        if (psDateColumns.isEmpty()) {
            log.warn("No profit sheet data found for taskId={}, placeholder will be empty", taskId);
        }

        // ============ 从缓存表读取商业计划书提取数据（在清理之前） ============
        Map<String, String> extractTextMap = loadExtractDataFromCache(taskId);

        // ============ 「客户基本概况」四字段：主营业务概况 / 下游主要服务 / 企业亮点描述 / 专利技术应用及创投情况 ============
        // ⚠️ 必须排在 applyBusinessOverviewSummary 之前：两者素材同源（同一份 7 段），而后者会把
        // 这 7 个来源键全部置为空白，置空后这里就读不到材料了。
        // 这一步是外部 HTTP 调用（最坏 max-attempts × read-timeout），刻意放在写库事务之外，
        // 避免长时间占用数据库连接。
        applyBusinessProfileSummary(extractTextMap, taskId, userId);

        // ============ 从 sxd_record 读取实控人姓名和管户权 ============
        // 「实际控制人及团队简介」段落要用实控人姓名做比对，所以先读出来
        String actCntlrNm = record.getActCntlrNm();
        boolean hasOwnership = "1".equals(record.getHasOwnership());

        // ============ 「实际控制人及团队简介」：人员简历多行文本 + 实控人姓名 → 大模型二次总结 ============
        // 把 dib_director_keyresume 的原始多行简历文本，重写成"实际控制人情况 + 核心团队情况"的
        // 固定样式，仍写回同名占位符。任何失败都会在 summarizer 内部让该段落留空，不中断报告生成。
        // 这一步是外部 HTTP 调用（最坏 max-attempts × read-timeout），刻意放在写库事务之外，
        // 避免长时间占用数据库连接。
        applyDirectorKeyresumeSummary(extractTextMap, actCntlrNm, taskId, userId);

        // ============ 7 段提取文本合并为一段「企业主营业务情况」 ============
        // 素材见 BusinessOverviewSummarizer.SOURCES（公司介绍/主营业务和产品/经营情况介绍/
        // 研发成果与转化情况/竞争优势介绍/发展战略情况/行业发展情况），交由大模型二次总结编辑；
        // 任何失败都会在 summarizer 内部让该段落留空，不中断报告生成。
        // ⚠️ 必须排在 applyBusinessProfileSummary 之后：本步会把「主营业务和产品」「经营情况介绍」置空。
        // 这一步是外部 HTTP 调用（最坏 max-attempts × read-timeout），刻意放在写库事务之外，
        // 避免长时间占用数据库连接。
        applyBusinessOverviewSummary(extractTextMap, taskId, userId);

        // ============ 资产负债表 / 利润表关键科目里增长率超阈值的科目 →「异常财务数据说明」 ============
        // 素材（超阈值科目清单）在本类用报表同一套计算函数筛好，大模型只负责组织语言；
        // 同样放在写库事务之外，避免外部 HTTP 调用长时间占用数据库连接。
        applyAbnormalFinanceDescription(bsItemDateValues, bsDateColumns,
                psItemDateValues, psDateColumns, extractTextMap, taskId, userId);

        // ============ 生成 Word 文档 ============
        byte[] document = createWordDocument(customerProfile, actCntlrNm, hasOwnership,
                bsItemDateValues, bsDateColumns,
                psItemDateValues, psDateColumns, extractTextMap);

        // ============ 更新任务状态 + 清理文档记录和缓存 ============
        // 删 sxd_doc、删 sxd_extract_data、置 status=1 三步必须原子：中途失败会留下
        // 「文档记录已删、status 仍为 '0'」的半成品状态 —— 任务既无法重试
        //（重入直接 DOC_NOT_FOUND），也不会被按 status='0' 的清理逻辑回收。
        markTaskFinished(taskId);

        // ============ 删除外部系统中的文档（本地事务已提交，失败仅记日志） ============
        deleteExternalDocs(entries);

        return document;
    }

    /**
     * 报告生成收尾：删除本任务的文档记录与提取数据缓存，并把任务置为已完成（status=1）。
     *
     * @param taskId 任务 ID
     */
    private void markTaskFinished(String taskId) {
        txTemplate().executeWithoutResult(status -> {
            docEntryMapper.delete(new LambdaQueryWrapper<DocEntry>()
                    .eq(DocEntry::getTaskId, taskId));
            extractDataMapper.delete(new LambdaQueryWrapper<ExtractData>()
                    .eq(ExtractData::getTaskId, taskId));
            // 只更新 status 一列：整实体回写会把生成 Word 期间（数秒，含外部调用）
            // 其它请求 / 其它副本改过的列，用开始生成时读到的旧快照覆盖回去。
            sxdMapper.update(null, new LambdaUpdateWrapper<SxdRecord>()
                    .eq(SxdRecord::getTaskId, taskId)
                    .set(SxdRecord::getStatus, "1")
                    .set(SxdRecord::getUpdatedAt, LocalDateTime.now()));
        });
    }

    /**
     * 查询文档的表格提取状态列表。
     */
    private List<DocTableStateRecord> queryDocTableStates(Long docId) {
        String url = apiProperties.getDocTableExtractStateUrl() + "/" + docId;
        UrlSecurityUtils.assertNoCrlf(url);
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Void> requestEntity = new HttpEntity<>(headers);
            ResponseEntity<ExternalResponse> response = apiRestClient.post()
                    .uri(url)
                    .headers(h -> h.addAll(requestEntity.getHeaders()))
                    .retrieve()
                    .toEntity(ExternalResponse.class);
            ExternalResponse respBody = response.getBody();
            if (respBody == null || !respBody.isSuccess() || respBody.getData() == null) {
                throw new BusinessException("TABLE_STATE_FAILED",
                        "文档 " + docId + " 表格提取状态查询失败");
            }
            return respBody.getDataAsList(DocTableStateRecord.class);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to query table extract state for docId={}", docId, e);
            throw new BusinessException("TABLE_STATE_FAILED",
                    "表格提取状态查询异常：" + e.getMessage());
        }
    }

    /**
     * 通过表格提取状态和审计报告附注判断使用合并还是母公司报表。
     * <p>
     * ① 有审计报告附注 → 查询"财务报表口径"：1-单一（母公司表）/ 2-合并（合并表）
     * ② 无审计报告附注 → 合并表和母公司表哪个状态为Y用哪个，都Y或都非Y默认合并表
     *
     * @param states         已查询到的表格提取状态列表
     * @param mergeTableName 合并报表表名（如 dib_fin_balance）
     * @param parentTableName 母公司报表表名（如 dib_fin_balance_parent）
     * @param reportTypeName 报表类型名称（用于日志）
     * @param scopeCache     同一 docId 内「财务报表口径」查询结果缓存，见 {@link #queryFinanceReportScopeCached}
     */
    private String determineSheetTable(Long docId, List<DocTableStateRecord> states,
                                       String mergeTableName, String parentTableName, String reportTypeName,
                                       Map<Long, String> scopeCache) {
        DocTableStateRecord auditNote = null;
        DocTableStateRecord merge = null;
        DocTableStateRecord parent = null;

        for (DocTableStateRecord state : states) {
            String name = state.getTableName();
            if ("dib_intervening_y_auditreport_jh".equals(name)) {
                auditNote = state;
            } else if (mergeTableName.equals(name)) {
                merge = state;
            } else if (parentTableName.equals(name)) {
                parent = state;
            }
        }

        // ① 审计报告附注已提取 → 查询"财务报表口径"
        if (auditNote != null && "Y".equals(auditNote.getExtractState())) {
            String scope = queryFinanceReportScopeCached(docId, scopeCache);
            if ("1".equals(scope)) {
                log.info("Doc {} 财务报表口径=单一，使用母公司{}", docId, reportTypeName);
                return parentTableName;
            } else if ("2".equals(scope)) {
                log.info("Doc {} 财务报表口径=合并，使用合并{}", docId, reportTypeName);
                return mergeTableName;
            }
            log.warn("Doc {} 无法确定财务报表口径（scope={}），回退到状态判断", docId, scope);
        }

        // ② 无审计报告附注（或口径查询失败）→ 查看状态
        boolean mergeY = merge != null && "Y".equals(merge.getExtractState());
        boolean parentY = parent != null && "Y".equals(parent.getExtractState());

        if (mergeY && !parentY) {
            log.info("Doc {} 合并{}状态为Y，使用合并表", docId, reportTypeName);
            return mergeTableName;
        } else if (!mergeY && parentY) {
            log.info("Doc {} 母公司{}状态为Y，使用母公司表", docId, reportTypeName);
            return parentTableName;
        } else {
            log.info("Doc {} 默认使用合并{}（mergeY={}, parentY={})", docId, reportTypeName, mergeY, parentY);
            return mergeTableName;
        }
    }

    /**
     * 带缓存的「财务报表口径」查询：同一 docId 在一次报告生成内只真正发起一次外部调用。
     *
     * <p>为什么需要缓存：{@link #determineSheetTable} 对<b>同一 docId</b> 会被调用两次
     * （资产负债表、利润表各一次），而两处都要判口径。不加缓存就会对同一份文档重复发起
     * 完全相同的请求 —— N 份文档最多放大到 2N 次。
     *
     * <p>查询失败（返回 {@code null}）<b>同样缓存</b>：一是失败的那次重试一次毫无意义，
     * 二是两次调用必须拿到<b>同一个</b>口径值 —— 否则「一次失败一次成功」会导致
     * 资产负债表用母公司表、利润表用合并表这种半新半旧的错配。
     *
     * <p>用 {@code containsKey} 而不是 {@code computeIfAbsent}：后者对 {@code null} 结果
     * 不会落盘（会当成"未计算"再算一次），而这正是这里要避免的。
     *
     * @param scopeCache 同一 docId 的查询结果缓存，由调用方按 docId 维度创建
     * @return "1"(单一) 或 "2"(合并)，查询失败返回 null
     */
    private String queryFinanceReportScopeCached(Long docId, Map<Long, String> scopeCache) {
        if (scopeCache.containsKey(docId)) {
            return scopeCache.get(docId);
        }
        String scope = queryFinanceReportScope(docId);
        scopeCache.put(docId, scope);
        return scope;
    }

    /**
     * 从审计报告附注表（dib_intervening_y_auditreport_jh）中查询"财务报表口径"的值。
     *
     * <p>本方法<b>不做缓存</b>，同一 docId 会被判两次（资产负债表、利润表）；
     * 调用请走 {@link #queryFinanceReportScopeCached}。
     *
     * @return "1"(单一) 或 "2"(合并)，查询失败返回 null
     */
    private String queryFinanceReportScope(Long docId) {
        try {
            ExternalResponse respBody = postQueryData(docId, "dib_intervening_y_auditreport_jh");
            if (respBody == null || !respBody.isSuccess() || respBody.getData() == null) {
                log.warn("Failed to query audit report for docId={}", docId);
                return null;
            }
            List<AuditReportItem> items = respBody.getDataAsList(AuditReportItem.class);
            if (items == null) return null;
            for (AuditReportItem item : items) {
                if ("财务报表口径".equals(item.getItem())) {
                    return item.getItemValue();
                }
            }
        } catch (Exception e) {
            log.warn("Exception querying audit report scope for docId={}: {}", docId, e.getMessage());
        }
        return null;
    }

    /**
     * 查询财务报表的提取数据（调用外部 queryData 接口）。
     * 同时用于资产负债表和利润表查询。
     */
    private List<FinanceRecord> queryFinanceData(Long docId, String tableName) {
        try {
            ExternalResponse respBody = postQueryData(docId, tableName);
            if (respBody == null || !respBody.isSuccess() || respBody.getData() == null) {
                throw new BusinessException("DOC_QUERY_FAILED",
                        "财务报表数据查询失败：" + (respBody != null ? respBody.getMessage() : "未知错误"));
            }
            return respBody.getDataAsList(FinanceRecord.class);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to query finance data for docId={}, tableName={}", docId, tableName, e);
            throw new BusinessException("DOC_QUERY_FAILED",
                    "财务报表数据查询异常：" + e.getMessage());
        }
    }

    /**
     * 调用外部资料删除 API，逐个删除外部系统中的文档。
     * 单个文档删除失败仅告警，不影响整体流程；最后汇总一次，因为失败的那些会留在 DIB 侧成为孤儿。
     */
    private void deleteExternalDocs(List<DocEntry> entries) {
        List<String> failedDocIds = new ArrayList<>();
        for (DocEntry entry : entries) {
            try {
                deleteExternalDoc(entry.getDocId());
            } catch (Exception e) {
                failedDocIds.add(entry.getDocId());
                log.warn("Failed to delete external doc {}: {}", entry.getDocId(), e.getMessage());
            }
        }
        if (!failedDocIds.isEmpty()) {
            log.warn("{} document(s) not deleted on DIB side, manual cleanup needed: {}",
                    failedDocIds.size(), failedDocIds);
        }
    }

    /**
     * 调用外部 API 删除单个文档。
     */
    private void deleteExternalDoc(String docId) {
        String url = apiProperties.getDocDeleteUrl() + "/" + docId;
        UrlSecurityUtils.assertNoCrlf(url);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Void> requestEntity = new HttpEntity<>(headers);
        ExternalResponse respBody = apiRestClient.post()
                .uri(url)
                .headers(h -> h.addAll(requestEntity.getHeaders()))
                .retrieve()
                .toEntity(ExternalResponse.class)
                .getBody();
        // 平台用 {success:false} 表达「没删掉」，HTTP 状态仍是 200 —— 不能当成功，否则 DIB 侧留孤儿文档。
        // 返回体为空时不判失败（该接口可能只回 200 无体），与改动前保持一致。
        if (respBody != null && !respBody.isSuccess()) {
            String reason = StringUtils.hasText(respBody.getMessage())
                    ? respBody.getMessage() : "平台返回 success=false";
            throw new BusinessException("DOC_DELETE_FAILED",
                    "文档 " + docId + " 删除失败：" + reason);
        }
        log.info("Deleted external doc: docId={}", docId);
    }

    /**
     * 从查询结果中提取第一个非空的 report_date。
     */
    private String extractReportDateFromRecords(List<FinanceRecord> records) {
        if (records == null) return null;
        for (FinanceRecord record : records) {
            if (record.getReportDate() != null && !record.getReportDate().isEmpty()) {
                return record.getReportDate();
            }
        }
        return null;
    }


    /**
     * 将 YYYY-MM-DD 格式的日期转换为列标题。
     * 12-31 → "2024年"（年末报告），否则 → "2025年6月"
     */
    private String formatDateColumn(String reportDate) {
        if (reportDate == null || reportDate.isEmpty()) return "未知日期";
        String[] parts = reportDate.split("-");
        if (parts.length < 2) return reportDate;
        String year = parts[0];
        try {
            int month = Integer.parseInt(parts[1]);
            if (parts.length >= 3 && "12".equals(parts[1]) && "31".equals(parts[2])) {
                return year + "年";
            }
            return year + "年" + month + "月";
        } catch (NumberFormatException e) {
            return year + "年";
        }
    }

    /**
     * 将列标题转换为可排序的整数（年×12+月），用于降序排列。
     * "2025年" → 2025×12+12=24312；"2025年6月" → 2025×12+6=24306
     */
    private static int dateColumnToInt(String col) {
        if (col == null) return 0;
        int yearEndIdx = col.indexOf("年");
        if (yearEndIdx <= 0) return 0;
        try {
            int year = Integer.parseInt(col.substring(0, yearEndIdx));
            int monthEndIdx = col.indexOf("月");
            int month = monthEndIdx > 0 ? Integer.parseInt(col.substring(yearEndIdx + 1, monthEndIdx)) : 12;
            return year * 12 + month;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 仅当 reportDate 为年末（12月31日）时，推算上一期日期列名。
     * 例如 "2025-12-31" → "2024年"。非年末返回 null。
     */
    private String derivePrevDateCol(String reportDate) {
        if (reportDate == null || reportDate.isEmpty()) return null;
        String[] parts = reportDate.split("-");
        if (parts.length < 3) return null;
        if (!"12".equals(parts[1]) || !"31".equals(parts[2])) return null;
        try {
            int year = Integer.parseInt(parts[0]);
            return (year - 1) + "年";
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 利用资产负债表的 lastAmount 补全缺失的上一期日期列。
     * 仅当上一期日期列不存在时才写入，不覆盖已有数据。
     */
    private void fillLastAmountColumn(List<FinanceRecord> records,
                                      Map<String, Map<String, BigDecimal>> itemDateValues,
                                      List<String> dateColumns,
                                      String reportDate) {
        String prevDateCol = derivePrevDateCol(reportDate);
        if (prevDateCol == null || dateColumns.contains(prevDateCol)) return;

        boolean hasLastAmount = false;
        for (FinanceRecord r : records) {
            String itemName = r.getItem();
            if (itemName != null && BALANCE_SHEET_KEY_ITEMS.contains(itemName)
                    && r.getLastAmount() != null) {
                itemDateValues.computeIfAbsent(itemName, k -> new LinkedHashMap<>())
                        .put(prevDateCol, r.getLastAmount());
                hasLastAmount = true;
            }
        }
        if (hasLastAmount) {
            dateColumns.add(prevDateCol);
            log.info("Filled lastAmount column {} from doc reportDate={}", prevDateCol, reportDate);
        }
    }

    /**
     * 利用利润表的 lastAmount 补全缺失的上一期日期列。
     * 仅当上一期日期列不存在时才写入，不覆盖已有数据。
     */
    private void fillLastAmountColumnForProfit(List<FinanceRecord> records,
                                               Map<String, Map<String, BigDecimal>> itemDateValues,
                                               List<String> dateColumns,
                                               String reportDate) {
        String prevDateCol = derivePrevDateCol(reportDate);
        if (prevDateCol == null || dateColumns.contains(prevDateCol)) return;

        boolean hasLastAmount = false;
        for (String itemName : PROFIT_SHEET_KEY_ITEMS) {
            BigDecimal lastVal = findProfitItemLastAmount(records, itemName);
            if (lastVal != null) {
                itemDateValues.computeIfAbsent(itemName, k -> new LinkedHashMap<>())
                        .put(prevDateCol, lastVal);
                hasLastAmount = true;
            }
        }
        if (hasLastAmount) {
            dateColumns.add(prevDateCol);
            log.info("Filled lastAmount column {} for profit from doc reportDate={}", prevDateCol, reportDate);
        }
    }

    /**
     * 从日期列标题中提取年份。如 "2024年" → "2024"，"2025年9月" → "2025"
     */
    private String extractYearFromColumn(String column) {
        if (column == null) return "";
        int idx = column.indexOf("年");
        if (idx > 0) {
            return column.substring(0, idx);
        }
        return column;
    }

    /**
     * 将金额从 元 格式化为 万元 显示（千分位，2位小数）。
     * null → "-"
     */
    private String formatAmount(BigDecimal valueYuan) {
        if (valueYuan == null) return "-";
        BigDecimal valueWan = valueYuan.divide(TEN_THOUSAND, 2, RoundingMode.HALF_UP);
        return String.format("%,.2f", valueWan);
    }

    /**
     * 计算增长率数值。
     * (current - previous) / |previous| * 100，保留 4 位小数后再乘 100（即百分数保留 6 位小数）。
     * 任一为 null 或 previous=0 时返回 null，表示无法计算。
     */
    private static BigDecimal calcGrowthRate(BigDecimal current, BigDecimal previous) {
        if (current == null || previous == null) return null;
        if (previous.compareTo(BigDecimal.ZERO) == 0) return null;
        return current.subtract(previous)
                .divide(previous.abs(), 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));
    }

    /**
     * 把增长率数值格式化为百分比字符串（2 位小数）。null → {@code "-"}。
     */
    private static String formatRate(BigDecimal rate) {
        return rate != null ? String.format("%.2f%%", rate) : "-";
    }

    /**
     * 计算增长率并格式化为百分比字符串（2 位小数）。
     * <p>
     * 本方法只负责"带百分号的字符串"，数值判断请用 {@link #calcGrowthRate}；两者共用同一份
     * 计算逻辑（{@link #formatRate} 只做格式化），保证「异常财务数据说明」的筛选口径与报表
     * 单元格显示的数字一致。
     */
    private String formatGrowthRate(BigDecimal current, BigDecimal previous) {
        return formatRate(calcGrowthRate(current, previous));
    }

    /**
     * 计算比率数值（numerator / denominator × 100）。null 表示无法计算。
     * 任一参数为 null 或 denominator=0 时返回 null。
     */
    private static BigDecimal calcRatio(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return numerator.divide(denominator, 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));
    }

    /**
     * 将已计算的比率值格式化为百分比字符串（2 位小数）。null → "-"
     */
    private static String formatRatioPercent(BigDecimal ratio) {
        return ratio != null ? String.format("%.2f%%", ratio) : "-";
    }

    /**
     * 计算资产负债率并格式化为百分比。负债合计 / 资产总计 * 100%
     */
    private String formatLiabilityRatio(BigDecimal assetTotal, BigDecimal liabilityTotal) {
        return formatRatio(liabilityTotal, assetTotal);
    }

    /**
     * 计算资产负债率的数值（用于增长率计算）。null 表示无法计算。
     */
    private BigDecimal calcLiabilityRatio(BigDecimal assetTotal, BigDecimal liabilityTotal) {
        return calcRatio(liabilityTotal, assetTotal);
    }

    // ========== 利润表辅助方法 ==========

    /**
     * 从利润表记录中按 item_standard 精确匹配指定科目的 current_amount。
     * 如"营业收入"匹配 item_standard="其中：营业收入"。
     */
    private BigDecimal findProfitItemValue(List<FinanceRecord> records, String displayName) {
        return findProfitItemAmount(records, displayName, FinanceRecord::getCurrentAmount);
    }

    /**
     * 从利润表记录中按 item_standard 匹配指定科目的 last_amount。
     */
    private BigDecimal findProfitItemLastAmount(List<FinanceRecord> records, String displayName) {
        return findProfitItemAmount(records, displayName, FinanceRecord::getLastAmount);
    }

    /**
     * 按 item_standard 匹配指定科目的金额值（current_amount 或 last_amount）。
     */
    private BigDecimal findProfitItemAmount(List<FinanceRecord> records, String displayName,
                                            Function<FinanceRecord, BigDecimal> amountGetter) {
        List<String> keys = PROFIT_ITEM_SEARCH_KEYS.get(displayName);
        if (keys == null || records == null) return null;

        for (FinanceRecord r : records) {
            if (r.getItemStandard() != null && keys.contains(r.getItemStandard())) {
                return amountGetter.apply(r);
            }
        }
        return null;
    }

    /**
     * 计算比率并格式化为百分比。numerator / denominator × 100%
     * 任一参数为 null 或 denominator=0 时返回 "-"
     */
    private String formatRatio(BigDecimal numerator, BigDecimal denominator) {
        return formatRatioPercent(calcRatio(numerator, denominator));
    }

    // ========== Word 文档生成（模板模式） ==========

    /**
     * 打开模板文档，替换 {{占位符}} 为实际数据，返回 Word 文档字节。
     * <p>
     * 模板中 {{{{field_name}}}} 占位符会被替换为客户信息字段值，
     * {{{{balance_sheet_key_items}}}} 和 {{{{profit_sheet_key_items}}}} 会被替换为对应的数据表格。
     */
    private byte[] createWordDocument(CustomerProfile profile, String actCntlrNm, boolean hasOwnership,
                                       Map<String, Map<String, BigDecimal>> bsItemDateValues, List<String> bsDateColumns,
                                       Map<String, Map<String, BigDecimal>> psItemDateValues, List<String> psDateColumns,
                                       Map<String, String> extractTextMap) {
        String templatePath = reportTemplatePath;
        Resource templateResource = resourceLoader.getResource(templatePath);
        if (!templateResource.exists()) {
            throw new BusinessException("TEMPLATE_NOT_FOUND",
                    "报告模板文件不存在: " + templatePath);
        }

        try (InputStream is = templateResource.getInputStream();
             XWPFDocument doc = new XWPFDocument(is);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            // 1. 替换客户基本信息占位符 {{cst_nm}}、{{credit_code}} 等
            replaceProfilePlaceholders(doc, profile, actCntlrNm, hasOwnership);

            // 2. 替换商业计划书提取数据占位符 {{dib_director_keyresume}} 等
            replaceExtractDataPlaceholders(doc, extractTextMap);

            // 3. 替换资产负债表关键科目占位符 -> 插入表格或清空
            if (!bsDateColumns.isEmpty()) {
                replacePlaceholderWithTable(doc, "{{balance_sheet_key_items}}",
                        table -> fillBalanceSheetTable(table, bsItemDateValues, bsDateColumns));
            } else {
                clearPlaceholderText(doc, "{{balance_sheet_key_items}}");
            }

            // 4. 替换利润表关键科目占位符 -> 插入表格或清空
            if (!psDateColumns.isEmpty()) {
                replacePlaceholderWithTable(doc, "{{profit_sheet_key_items}}",
                        table -> fillProfitSheetTable(table, psItemDateValues, psDateColumns));
            } else {
                clearPlaceholderText(doc, "{{profit_sheet_key_items}}");
            }

            doc.write(baos);
            return baos.toByteArray();
        } catch (IOException e) {
            log.error("Failed to create Word document from template", e);
            throw new BusinessException("DOC_CREATE_FAILED", "Word 文档生成失败：" + e.getMessage());
        }
    }

    /**
     * 替换文档段落中 {{{{field_name}}}} 格式的占位符为实际客户信息字段值。
     * <p>
     * 在段落级别拼接文本后替换占位符，然后复用第一个 run 写入替换结果，
     * 删除多余 run，保留第一个 run 的字体样式。
     * <p>
     * 管户权规则：{{act_cntlr_nm}} 始终从 kjjr_ai_sxd_record.act_cntlr_nm 读取，不受管户权影响；
     * 其余从 kjjr_ai_sxd_profile 读取的占位符，has_ownership=1 时按正常值填充，
     * has_ownership=0 或未设置时统一替换为空字符串。
     */
    private void replaceProfilePlaceholders(XWPFDocument doc, CustomerProfile profile, String actCntlrNm, boolean hasOwnership) {
        // 按 PROFILE_FIELD_GETTERS 的迭代顺序构建占位符 → 值映射，
        // act_cntlr_nm 最后插入，保持与原替换顺序一致。
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, Function<CustomerProfile, String>> entry : PROFILE_FIELD_GETTERS.entrySet()) {
            String value;
            if (!hasOwnership) {
                value = "";
            } else {
                value = profile != null ? entry.getValue().apply(profile) : "";
                value = value != null ? value : "";
            }
            values.put(entry.getKey(), value);
        }
        // 实控人姓名从 kjjr_ai_sxd_record 表读取，不受管户权影响
        values.put("act_cntlr_nm", actCntlrNm != null ? actCntlrNm : "");
        replacePlaceholders(doc, values);
    }

    /**
     * 从 sxd_extract_data 缓存表加载商业计划书提取数据。
     * @return tableName → text 映射；始终包含全部 BUSINESS_PLAN_TABLES 键，
     *         未缓存的数据项值为空字符串，确保占位符能被替换为空而非残留。
     */
    private Map<String, String> loadExtractDataFromCache(String taskId) {
        // 先预置全部商业计划书表名为空字符串，保证即便缓存完全为空，
        // 占位符也能被替换为空字符串，而非原样残留在文档中。
        Map<String, String> map = new LinkedHashMap<>();
        for (String tableName : BUSINESS_PLAN_TABLES) {
            map.put(tableName, "");
        }

        List<ExtractData> cachedList = extractDataMapper.selectList(
                new LambdaQueryWrapper<ExtractData>()
                        .eq(ExtractData::getTaskId,
                                taskId));
        if (cachedList == null || cachedList.isEmpty()) {
            log.warn("No extract data found in cache for taskId={}, extract placeholders will be empty", taskId);
            return map;
        }
        for (ExtractData item : cachedList) {
            map.merge(item.getTableName(), item.getText(),
                    (existing, incoming) -> existing.isEmpty() ? incoming : existing + "\n" + incoming);
        }
        return map;
    }

    /**
     * 用商业计划书那 7 段提取文本（{@link BpExtractSources#SOURCES}），生成「客户基本概况」里的四个字段，
     * 写回 {@code extractTextMap}：
     * {@link BusinessProfileSummarizer#PLACEHOLDER_MAIN_BUSINESS}（主营业务概况）、
     * {@link BusinessProfileSummarizer#PLACEHOLDER_DOWNSTREAM}（下游主要服务）、
     * {@link BusinessProfileSummarizer#PLACEHOLDER_HIGHLIGHTS}（企业亮点描述）、
     * {@link BusinessProfileSummarizer#PLACEHOLDER_PATENT_APPLICATION}
     * （专利技术在生产中的应用情况 + 是否有创投机构投资，2026-10-09 新增）。
     *
     * <p><b>四个键恒定写入</b>（取不到就写兜底句式）：这四个占位符不在 {@code BUSINESS_PLAN_TABLES} 里，
     * 不会被 {@link #loadExtractDataFromCache} 预置，所以必须在这里落键 ——
     * 替换是"按键查表"，键缺失会让模板里的 {@code {{...}}} 以字面量原样留在报告中。
     *
     * <p>素材与「企业主营业务情况」同源（那 7 段），但另外成篇：那边是正文一大段，
     * 这边是概况里的几个短字段，所以各用一份提示词、各调一次模型（见 {@link BusinessProfileSummarizer}
     * 类注释）。四个字段共用<b>同一次</b>调用（模型按五行标签一次输出，2026-10-09 用户选定），
     * 不额外增加网络往返。
     *
     * <p>⚠️ <b>必须排在 {@code applyBusinessOverviewSummary} 之前</b>：后者会把 7 个来源键全部置空。
     *
     * @param extractTextMap 提取文本映射（原地修改）
     * @param taskId         任务 ID，仅用于日志
     * @param userId         调用方账号，透传给大模型网关，可空
     */
    private void applyBusinessProfileSummary(Map<String, String> extractTextMap, String taskId, String userId) {
        Map<String, String> generated = businessProfileSummarizer.summarize(taskId, extractTextMap, userId);
        extractTextMap.putAll(generated);
    }

    /**
     * 把 {@code dib_director_keyresume} 的人员简历多行文本 + 实控人姓名交给大模型，重写成
     * 「实际控制人及团队简介」段落，写回 {@code extractTextMap} 的<b>同名键</b>。
     *
     * <p><b>复用同名键而不是新增占位符</b>：报告模板里该占位符本就叫
     * {@code {{dib_director_keyresume}}}，本次只是把"原始多行简历文本"换成"模型按固定样式二次
     * 总结后的文字"，模板零改动。键必须留在 map 里：替换是"按键查表"，键缺失会让模板里的
     * {@code {{...}}} 以字面量原样留在报告中。
     *
     * <p><b>姓名比对交给模型</b>而不在 Java 侧做子串匹配：材料里人名可能写成「王美女士」
     * 「王美（董事长）」，匹配既会漏判也会误判。实控人姓名取自 {@code sxd_record.act_cntlr_nm}，
     * 与管户权无关。
     *
     * @param extractTextMap 提取文本映射（原地修改）
     * @param actCntlrNm     实控人姓名，可空
     * @param taskId         任务 ID，仅用于日志
     * @param userId         调用方账号，透传给大模型网关，可空
     */
    private void applyDirectorKeyresumeSummary(Map<String, String> extractTextMap, String actCntlrNm,
                                               String taskId, String userId) {
        String summary = directorResumeSummarizer.summarize(
                taskId, actCntlrNm, extractTextMap.get(TABLE_DIRECTOR_KEYRESUME), userId);
        extractTextMap.put(TABLE_DIRECTOR_KEYRESUME, summary == null ? "" : summary);
    }

    /**
     * 把 {@link BpExtractSources#SOURCES} 里的各段提取文本（2026-10-08 起为 7 段：
     * 公司介绍 / 主营业务和产品 / 经营情况介绍 / 研发成果与转化情况 / 竞争优势介绍 /
     * 发展战略情况 / 行业发展情况）合并成一段「企业主营业务情况」，
     * 写回 {@code extractTextMap} 的 {@link #PLACEHOLDER_BUSINESS_OVERVIEW} 键。
     *
     * <p>合并后原始各段不再单独出现在报告中：
     * <ul>
     *   <li>把 {@link #PLACEHOLDER_BUSINESS_OVERVIEW} 指向大模型汇总结果；</li>
     *   <li>把<b>所有来源键</b>（{@link BpExtractSources#SOURCES} 那 7 个表名）置为
     *       <b>空字符串</b>而不是从 map 里移除 —— 占位符替换是"按键查表替换"，键还在但值为空 →
     *       模板里若残留 {@code {{dib_manage_business_and_products}}} 这类占位符会被清空；
     *       若直接移除键，它不会被替换，会以字面量 {@code {{...}}} 原样留在报告里（更难排查）。</li>
     * </ul>
     *
     * <p>⚠️ <b>必须排在 {@code applyBusinessProfileSummary} 之后</b>：本方法会把
     * 「主营业务和产品」「经营情况介绍」置空，而「客户基本概况」四字段正是拿这两段当素材。
     *
     * @param extractTextMap 提取文本映射（原地修改）
     * @param taskId         任务 ID，仅用于日志
     * @param userId         调用方账号，透传给大模型网关，可空
     */
    private void applyBusinessOverviewSummary(Map<String, String> extractTextMap, String taskId, String userId) {
        String overview = businessOverviewSummarizer.summarize(taskId, extractTextMap, userId);

        for (BpExtractSources.Source source : BpExtractSources.SOURCES) {
            extractTextMap.put(source.table(), "");
        }
        extractTextMap.put(PLACEHOLDER_BUSINESS_OVERVIEW, overview == null ? "" : overview);
    }

    /**
     * 筛出增长率超阈值的财务科目交给大模型成文，写回 {@code extractTextMap} 的
     * {@link #PLACEHOLDER_ABNORMAL_FINANCE} 键。
     *
     * <p>清单为空（没有任何科目超阈值）时不调用大模型，占位符写空字符串 —— 与
     * {@link #applyBusinessOverviewSummary} 一样，键必须留在 map 里：
     * 替换是"按键查表"，键缺失会让模板里的 {@code {{...}}} 以字面量原样留在报告中。
     *
     * @param bsItemDateValues 资产负债表 科目 → 日期列 → 值（元）
     * @param bsDateColumns    资产负债表日期列（<b>降序</b>，最新在前）
     * @param psItemDateValues 利润表 科目 → 日期列 → 值（元）
     * @param psDateColumns    利润表日期列（降序）
     * @param extractTextMap   提取文本映射（原地修改）
     * @param taskId           任务 ID，仅用于日志
     * @param userId           调用方账号，透传给大模型网关，可空
     */
    private void applyAbnormalFinanceDescription(Map<String, Map<String, BigDecimal>> bsItemDateValues,
                                                 List<String> bsDateColumns,
                                                 Map<String, Map<String, BigDecimal>> psItemDateValues,
                                                 List<String> psDateColumns,
                                                 Map<String, String> extractTextMap,
                                                 String taskId, String userId) {
        String material = buildAbnormalFinanceMaterial(bsItemDateValues, bsDateColumns,
                psItemDateValues, psDateColumns);
        String description = abnormalFinanceDescriber.describe(taskId, material, userId);
        extractTextMap.put(PLACEHOLDER_ABNORMAL_FINANCE, description == null ? "" : description);
    }

    /**
     * 拼「异常财务数据说明」的素材：把两张表里增长率绝对值超阈值的行，逐行整理成
     * {@code 科目｜2022年：值｜2023年：值｜2023年增长率：值｜…} 形式的清单。
     *
     * <p>刻意<b>只给数据、不给叙述</b>：增长率的高低、"先降后升"这类判断交给提示词去规范。
     * 在代码里再复刻一套"同比下降 / 大幅反弹"的分支规则，只会和提示词的措辞要求打架，
     * 而数字本身已经由报表函数算好，模型没必要重算。
     *
     * @return 素材文本；没有任何行超阈值时返回空字符串（调用方据此跳过模型调用）
     */
    private String buildAbnormalFinanceMaterial(Map<String, Map<String, BigDecimal>> bsItemDateValues,
                                                List<String> bsDateColumns,
                                                Map<String, Map<String, BigDecimal>> psItemDateValues,
                                                List<String> psDateColumns) {
        List<String> bsLines = collectAbnormalLines(bsItemDateValues, bsDateColumns,
                BALANCE_SHEET_KEY_ITEMS, BALANCE_SHEET_RATIO_ITEMS);
        List<String> psLines = collectAbnormalLines(psItemDateValues, psDateColumns,
                PROFIT_SHEET_KEY_ITEMS, PROFIT_SHEET_RATIO_ITEMS);

        if (bsLines.isEmpty() && psLines.isEmpty()) {
            // 不在这里打日志：AbnormalFinanceDescriber 会带上 taskId 打一条 WARN，重复打只会变噪音
            return "";
        }

        StringBuilder sb = new StringBuilder();
        appendAbnormalBlock(sb, ABNORMAL_SECTION_BALANCE, bsLines);
        appendAbnormalBlock(sb, ABNORMAL_SECTION_PROFIT, psLines);
        return sb.toString();
    }

    /**
     * 采集一张表里增长率超阈值的行，每个超阈值的行拼成一条素材文本。
     *
     * <p>取值与填表<b>共用同一批计算函数</b>：普通科目直接取 {@code itemDateValues}，
     * {@link #RATIO_LIABILITY} 走 {@link #calcLiabilityRatio}，其余计算行走
     * {@link #computeRatioForLabel}；增长率统一走 {@link #calcGrowthRate}。
     * 这样素材里的数字与报表单元格逐字一致，不会出现"表里有、说明里没有"或数字对不上。
     *
     * @param itemDateValues 科目 → 日期列 → 值（元；计算行为百分数）
     * @param dateColumns    日期列（降序）
     * @param itemLabels     科目名（单位万元）
     * @param ratioLabels    计算行名（单位 %）
     * @return 超阈值行的素材文本，顺序为"先科目、后计算行"（与报表行序一致）
     */
    private List<String> collectAbnormalLines(Map<String, Map<String, BigDecimal>> itemDateValues,
                                              List<String> dateColumns,
                                              List<String> itemLabels,
                                              List<String> ratioLabels) {
        // 只按"年末"列比增长率：季末 / 月中列与"2024年"不是同一口径，混在一起比没有意义
        List<String> yearCols = dateColumns.stream()
                .filter(col -> col.matches("\\d{4}年"))
                .sorted(Comparator.comparingInt(ExtractDataServiceImpl::dateColumnToInt))
                .collect(Collectors.toList());
        if (yearCols.size() < 2) {
            return Collections.emptyList();
        }

        List<String> labels = new ArrayList<>(itemLabels);
        labels.addAll(ratioLabels);

        List<String> lines = new ArrayList<>();
        for (String label : labels) {
            boolean ratioRow = ratioLabels.contains(label);

            // 逐年取值（升序），便于叙述成"2022 年至 2024 年"
            Map<String, BigDecimal> values = new LinkedHashMap<>();
            for (String col : yearCols) {
                values.put(col, ratioRow
                        ? ratioValueOf(itemDateValues, col, label)
                        : itemValueOf(itemDateValues, label, col));
            }

            // 相邻年份逐对比增长率：只要有一对超阈值，整行入选，并带上全部年份与增长率
            List<BigDecimal> rates = new ArrayList<>();
            boolean abnormal = false;
            for (int i = 1; i < yearCols.size(); i++) {
                BigDecimal rate = calcGrowthRate(values.get(yearCols.get(i)), values.get(yearCols.get(i - 1)));
                if (rate != null && rate.setScale(2, RoundingMode.HALF_UP).abs()
                        .compareTo(ABNORMAL_GROWTH_THRESHOLD) > 0) {
                    abnormal = true;
                }
                rates.add(rate);
            }
            if (!abnormal) {
                continue;
            }
            lines.add(buildAbnormalLine(label, ratioRow, yearCols, values, rates));
        }
        return lines;
    }

    /**
     * 拼一条素材文本，形如：
     * <pre>净利润｜2022年：1,234.56万元｜2023年：567.89万元｜2024年：2,345.67万元｜2023年增长率：-54.00%｜2024年增长率：313.06%</pre>
     * 增长率挂在"本期年份"上，与报表增长率列的口径一致（"2024年增长率" = 2024 vs 2023）。
     * <p>
     * <b>无法计算的增长率（基期为 0 或无数据）直接不出现在素材里</b>，而不是写 {@code "-"}：
     * 给了这个横杠，模型会老老实实抄成"2023 年增长率为 -"（实测），读起来像数据缺失的报错。
     * 报表单元格里的 {@code "-"} 是给"表格对齐"看的，叙述里没有这种需求。
     */
    private String buildAbnormalLine(String label, boolean ratioRow, List<String> yearCols,
                                     Map<String, BigDecimal> values, List<BigDecimal> rates) {
        StringBuilder sb = new StringBuilder(label).append('｜');
        for (String col : yearCols) {
            sb.append(col).append('：').append(formatAbnormalValue(values.get(col), ratioRow)).append('｜');
        }
        for (int i = 0; i < rates.size(); i++) {
            if (rates.get(i) == null) {
                continue;
            }
            // rates.get(i) 对应 yearCols.get(i+1)（本期）
            sb.append(extractYearFromColumn(yearCols.get(i + 1))).append("年增长率：")
                    .append(formatRate(rates.get(i))).append('｜');
        }
        // 末尾多余的「｜」去掉
        if (sb.charAt(sb.length() - 1) == '｜') {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    /**
     * 格式化素材里的数值：金额科目写「万元」，计算行写「%」；无数据写 {@code "-"}。
     */
    private String formatAbnormalValue(BigDecimal value, boolean ratioRow) {
        if (value == null) {
            return "-";
        }
        return ratioRow ? formatRatioPercent(value) : formatAmount(value) + "万元";
    }

    /**
     * 取计算行在指定日期列的值 —— 与报表计算行取值口径一致。
     */
    private BigDecimal ratioValueOf(Map<String, Map<String, BigDecimal>> itemDateValues,
                                    String col, String label) {
        if (RATIO_LIABILITY.equals(label)) {
            return calcLiabilityRatio(itemValueOf(itemDateValues, ITEM_ASSET_TOTAL, col),
                    itemValueOf(itemDateValues, ITEM_LIABILITY_TOTAL, col));
        }
        return computeRatioForLabel(itemDateValues, col, label);
    }

    /** 取科目在指定日期列的值；该科目或该期无数据时返回 null。 */
    private static BigDecimal itemValueOf(Map<String, Map<String, BigDecimal>> itemDateValues,
                                          String item, String col) {
        Map<String, BigDecimal> values = itemDateValues.get(item);
        return values != null ? values.get(col) : null;
    }

    /** 把一张表的素材行追加到总素材里；该表没有超阈值行时不写小标题，免得模型对着空标题硬编。 */
    private static void appendAbnormalBlock(StringBuilder sb, String title, List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append('\n');
        }
        sb.append(title).append('\n').append(String.join("\n", lines));
    }

    /**
     * 替换文档段落中 {{dib_director_keyresume}} 等商业计划书提取数据占位符。
     * <p>
     * 在段落级别拼接文本后替换占位符，然后复用第一个 run 写入替换结果，
     * 删除多余 run，保留第一个 run 的字体样式。
     */
    private void replaceExtractDataPlaceholders(XWPFDocument doc, Map<String, String> extractTextMap) {
        replacePlaceholders(doc, extractTextMap);
    }

    /**
     * 通用占位符替换：遍历文档所有段落（含表格单元格内段落），将 {{{{key}}}} 替换为 map 中的值。
     * <p>
     * 在段落级别拼接文本后替换占位符，去除 run 间的换行符，
     * 复用第一个 run 写入替换结果并删除多余 run，保留第一个 run 的字体样式。
     */
    private void replacePlaceholders(XWPFDocument doc, Map<String, String> placeholderValues) {
        forEachParagraph(doc, para -> {
            String paraText = para.getText();
            if (paraText == null || !paraText.contains("{{")) return;

            // 仅清理段落拼接文本（run 之间）可能残留的换行，替换值内部的换行需保留
            String replaced = paraText.replace("\n", "");
            boolean changed = false;
            for (Map.Entry<String, String> entry : placeholderValues.entrySet()) {
                String placeholder = "{{" + entry.getKey() + "}}";
                if (replaced.contains(placeholder)) {
                    String value = entry.getValue();
                    replaced = replaced.replace(placeholder, value != null ? value : "");
                    changed = true;
                }
            }
            if (changed) {
                replaceParaTextPreserveStyle(para, replaced);
            }
        });
    }

    /**
     * 在文档中查找包含指定占位符文本的段落，在其位置插入一个新表格，
     * 然后移除该占位符段落。通过 XmlCursor 在占位符段落之前插入表格元素。
     */
    private void replacePlaceholderWithTable(XWPFDocument doc, String placeholder,
                                              Consumer<XWPFTable> tableFiller) {
        List<IBodyElement> bodyElements = doc.getBodyElements();
        for (int i = 0; i < bodyElements.size(); i++) {
            IBodyElement elem = bodyElements.get(i);
            if (elem instanceof XWPFParagraph) {
                XWPFParagraph para = (XWPFParagraph) elem;
                if (para.getText().contains(placeholder)) {
                    try (XmlCursor cursor = para.getCTP().newCursor()) {
                        XWPFTable table = doc.insertNewTbl(cursor);
                        tableFiller.accept(table);
                        doc.removeBodyElement(i + 1);
                        return;
                    }
                }
            }
        }
        log.warn("Placeholder not found in template: {}", placeholder);
    }

    /**
     * 将文档中指定占位符替换为空字符串（用于无数据时清空占位符）。
     */
    private void clearPlaceholderText(XWPFDocument doc, String placeholder) {
        forEachParagraph(doc, para -> {
            for (XWPFRun run : para.getRuns()) {
                String text = run.getText(0);
                if (text != null && text.contains(placeholder)) {
                    run.setText(text.replace(placeholder, ""), 0);
                    return;
                }
            }
        });
    }

    /**
     * 遍历文档中所有段落，包括顶层段落和表格单元格内的段落。
     * 解决 POI 中 doc.getParagraphs() 不包含表格内段落的问题。
     *
     * @param doc      XWPFDocument 文档
     * @param consumer 对每个段落执行的操作
     */
    private void forEachParagraph(XWPFDocument doc, Consumer<XWPFParagraph> consumer) {
        // 1. 遍历文档顶层段落
        for (XWPFParagraph para : doc.getParagraphs()) {
            consumer.accept(para);
        }
        // 2. 遍历所有表格单元格内的段落
        for (XWPFTable table : doc.getTables()) {
            for (XWPFTableRow row : table.getRows()) {
                for (XWPFTableCell cell : row.getTableCells()) {
                    for (XWPFParagraph para : cell.getParagraphs()) {
                        consumer.accept(para);
                    }
                }
            }
        }
    }

    /**
     * 替换段落文本，同时保留字体样式。
     * <p>
     * 复用第一个 run 写入替换后的文本，删除多余 run，保留第一个 run 的字体样式。
     * 文本中的换行符（\n）转换为 Word 的软换行 {@code <w:br/>} 元素，
     * 避免写入单个 {@code <w:t>} 文本节点后被 Word 折叠为空格。
     * <p>
     * 调用方需在段落级别（{@link XWPFParagraph#getText()}）拼接所有 run 的文本后替换占位符，
     * 再传入本方法写回；若拼接文本中残留换行符，由调用方在传参前去除。
     */
    private void replaceParaTextPreserveStyle(XWPFParagraph para, String newText) {
        if (para.getRuns().isEmpty()) {
            para.createRun();
        }
        // 复用第一个 run，保留其字体样式
        XWPFRun firstRun = para.getRuns().get(0);
        // 删除多余的 run
        for (int i = para.getRuns().size() - 1; i >= 1; i--) {
            para.removeRun(i);
        }
        setRunTextWithBreaks(firstRun, newText);
    }

    /**
     * 向 run 写入文本，将换行符（\n）转为软换行 {@code <w:br/>}。
     * <p>
     * 保留 run 已有的 rPr 字体样式；清空原文本节点后，按行交替写入
     * {@code <w:t>} 与 {@code <w:br/>}，使 Word 正确渲染多行文本。
     */
    private void setRunTextWithBreaks(XWPFRun run, String text) {
        String value = text != null ? text : "";
        CTR ctr = run.getCTR();
        // 清空现有文本节点与换行节点（rPr 位于 run 首位，不受影响）
        for (int i = ctr.sizeOfTArray() - 1; i >= 0; i--) {
            ctr.removeT(i);
        }
        while (ctr.sizeOfBrArray() > 0) {
            ctr.removeBr(0);
        }
        // 按行交替写入文本和软换行
        String[] lines = value.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                ctr.addNewBr();
            }
            CTText ct = ctr.addNewT();
            ct.setStringValue(lines[i]);
        }
    }

    /**
     * 向已创建的空白表格填充资产负债表关键科目数据。
     */
    private void fillBalanceSheetTable(XWPFTable table,
                                        Map<String, Map<String, BigDecimal>> itemDateValues,
                                        List<String> dateColumns) {
        List<String[]> growthCols = buildGrowthCols(dateColumns);
        int colCount = 1 + dateColumns.size() + growthCols.size();
        int rowCount = 1 + BALANCE_SHEET_KEY_ITEMS.size() + 1;

        // 确保表格有足够的行：如果已有行（插入新表格时自带一行），先删掉
        while (!table.getRows().isEmpty()) {
            table.removeRow(0);
        }
        for (int r = 0; r < rowCount; r++) {
            XWPFTableRow row = table.createRow();
            ensureCellCount(row, colCount);
        }

        // 表头行
        XWPFTableRow headerRow = table.getRow(0);
        setCellText(headerRow.getCell(0), "项目");
        for (int i = 0; i < dateColumns.size(); i++) {
            setCellText(headerRow.getCell(1 + i), dateColumns.get(i));
        }
        for (int i = 0; i < growthCols.size(); i++) {
            setCellText(headerRow.getCell(1 + dateColumns.size() + i), growthCols.get(i)[0]);
        }

        // 关键科目数据行
        for (int r = 0; r < BALANCE_SHEET_KEY_ITEMS.size(); r++) {
            String item = BALANCE_SHEET_KEY_ITEMS.get(r);
            XWPFTableRow row = table.getRow(1 + r);
            setCellText(row.getCell(0), item);

            Map<String, BigDecimal> values = itemDateValues.get(item);
            for (int c = 0; c < dateColumns.size(); c++) {
                BigDecimal val = values != null ? values.get(dateColumns.get(c)) : null;
                setCellText(row.getCell(1 + c), formatAmount(val));
            }
            for (int g = 0; g < growthCols.size(); g++) {
                String[] ginfo = growthCols.get(g);
                BigDecimal curVal = values != null ? values.get(ginfo[1]) : null;
                BigDecimal prevVal = values != null ? values.get(ginfo[2]) : null;
                setCellText(row.getCell(1 + dateColumns.size() + g),
                        formatGrowthRate(curVal, prevVal));
            }
        }

        // 资产负债率行
        int lastRow = 1 + BALANCE_SHEET_KEY_ITEMS.size();
        XWPFTableRow ratioRow = table.getRow(lastRow);
        setCellText(ratioRow.getCell(0), RATIO_LIABILITY);
        for (int c = 0; c < dateColumns.size(); c++) {
            String col = dateColumns.get(c);
            Map<String, BigDecimal> assetVals = itemDateValues.get(ITEM_ASSET_TOTAL);
            Map<String, BigDecimal> liabilityVals = itemDateValues.get(ITEM_LIABILITY_TOTAL);
            BigDecimal asset = assetVals != null ? assetVals.get(col) : null;
            BigDecimal liability = liabilityVals != null ? liabilityVals.get(col) : null;
            setCellText(ratioRow.getCell(1 + c), formatLiabilityRatio(asset, liability));
        }
        for (int g = 0; g < growthCols.size(); g++) {
            String[] ginfo = growthCols.get(g);
            Map<String, BigDecimal> assetVals = itemDateValues.get(ITEM_ASSET_TOTAL);
            Map<String, BigDecimal> liabilityVals = itemDateValues.get(ITEM_LIABILITY_TOTAL);
            BigDecimal curAsset = assetVals != null ? assetVals.get(ginfo[1]) : null;
            BigDecimal curLiability = liabilityVals != null ? liabilityVals.get(ginfo[1]) : null;
            BigDecimal prevAsset = assetVals != null ? assetVals.get(ginfo[2]) : null;
            BigDecimal prevLiability = liabilityVals != null ? liabilityVals.get(ginfo[2]) : null;
            BigDecimal curRatio = calcLiabilityRatio(curAsset, curLiability);
            BigDecimal prevRatio = calcLiabilityRatio(prevAsset, prevLiability);
            setCellText(ratioRow.getCell(1 + dateColumns.size() + g),
                    formatGrowthRate(curRatio, prevRatio));
        }
    }

    /**
     * 向已创建的空白表格填充利润表关键科目数据。
     */
    private void fillProfitSheetTable(XWPFTable table,
                                       Map<String, Map<String, BigDecimal>> itemDateValues,
                                       List<String> dateColumns) {
        List<String[]> growthCols = buildGrowthCols(dateColumns);
        int colCount = 1 + dateColumns.size() + growthCols.size();
        int rowCount = 1 + PROFIT_SHEET_KEY_ITEMS.size() + 3;

        // 确保表格有足够的行：如果已有行（插入新表格时自带一行），先删掉
        while (!table.getRows().isEmpty()) {
            table.removeRow(0);
        }
        for (int r = 0; r < rowCount; r++) {
            XWPFTableRow row = table.createRow();
            ensureCellCount(row, colCount);
        }

        // 表头行
        XWPFTableRow headerRow = table.getRow(0);
        setCellText(headerRow.getCell(0), "项目");
        for (int i = 0; i < dateColumns.size(); i++) {
            setCellText(headerRow.getCell(1 + i), dateColumns.get(i));
        }
        for (int i = 0; i < growthCols.size(); i++) {
            setCellText(headerRow.getCell(1 + dateColumns.size() + i), growthCols.get(i)[0]);
        }

        // 9 个基本科目
        for (int r = 0; r < PROFIT_SHEET_KEY_ITEMS.size(); r++) {
            String item = PROFIT_SHEET_KEY_ITEMS.get(r);
            XWPFTableRow row = table.getRow(1 + r);
            setCellText(row.getCell(0), item);

            Map<String, BigDecimal> values = itemDateValues.get(item);
            for (int c = 0; c < dateColumns.size(); c++) {
                BigDecimal val = values != null ? values.get(dateColumns.get(c)) : null;
                setCellText(row.getCell(1 + c), formatAmount(val));
            }
            for (int g = 0; g < growthCols.size(); g++) {
                String[] ginfo = growthCols.get(g);
                BigDecimal curVal = values != null ? values.get(ginfo[1]) : null;
                BigDecimal prevVal = values != null ? values.get(ginfo[2]) : null;
                setCellText(row.getCell(1 + dateColumns.size() + g),
                        formatGrowthRate(curVal, prevVal));
            }
        }

        // 计算行
        int base = 1 + PROFIT_SHEET_KEY_ITEMS.size();
        fillRatioRowAt(table, base, itemDateValues, dateColumns, growthCols, RATIO_GROSS_MARGIN);
        fillRatioRowAt(table, base + 1, itemDateValues, dateColumns, growthCols, RATIO_NET_MARGIN);
        fillRatioRowAt(table, base + 2, itemDateValues, dateColumns, growthCols, RATIO_RD);
    }

    /**
     * 填充利润表计算行（毛利润率/净利润率/研发费用占比）的值和增长率。
     */
    private void fillRatioRowAt(XWPFTable table, int rowIdx,
                                 Map<String, Map<String, BigDecimal>> itemDateValues, List<String> dateColumns,
                                 List<String[]> growthCols, String label) {

        XWPFTableRow row = table.getRow(rowIdx);
        setCellText(row.getCell(0), label);

        for (int c = 0; c < dateColumns.size(); c++) {
            String col = dateColumns.get(c);
            BigDecimal ratio = computeRatioForLabel(itemDateValues, col, label);
            setCellText(row.getCell(1 + c), formatRatioPercent(ratio));
        }

        // 增长率
        for (int g = 0; g < growthCols.size(); g++) {
            String[] ginfo = growthCols.get(g);
            BigDecimal curRatio = computeRatioForLabel(itemDateValues, ginfo[1], label);
            BigDecimal prevRatio = computeRatioForLabel(itemDateValues, ginfo[2], label);
            setCellText(row.getCell(1 + dateColumns.size() + g),
                    formatGrowthRate(curRatio, prevRatio));
        }
    }

    /**
     * 计算指定标签（毛利润率/净利润率/研发费用占比）的比率值。
     */
    private BigDecimal computeRatioForLabel(Map<String, Map<String, BigDecimal>> itemDateValues,
                                             String col, String label) {
        if (RATIO_GROSS_MARGIN.equals(label)) {
            Map<String, BigDecimal> revVals = itemDateValues.get(ITEM_REVENUE);
            Map<String, BigDecimal> costVals = itemDateValues.get(ITEM_COST);
            BigDecimal rev = revVals != null ? revVals.get(col) : null;
            BigDecimal cost = costVals != null ? costVals.get(col) : null;
            if (rev != null && cost != null && rev.compareTo(BigDecimal.ZERO) != 0) {
                return rev.subtract(cost).divide(rev, 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"));
            }
        } else if (RATIO_NET_MARGIN.equals(label)) {
            Map<String, BigDecimal> revVals = itemDateValues.get(ITEM_REVENUE);
            Map<String, BigDecimal> profitVals = itemDateValues.get(ITEM_NET_PROFIT);
            BigDecimal rev = revVals != null ? revVals.get(col) : null;
            BigDecimal profit = profitVals != null ? profitVals.get(col) : null;
            if (profit != null && rev != null && rev.compareTo(BigDecimal.ZERO) != 0) {
                return profit.divide(rev, 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"));
            }
        } else if (RATIO_RD.equals(label)) {
            Map<String, BigDecimal> revVals = itemDateValues.get(ITEM_REVENUE);
            Map<String, BigDecimal> rdVals = itemDateValues.get(ITEM_RD_EXPENSE);
            BigDecimal rev = revVals != null ? revVals.get(col) : null;
            BigDecimal rd = rdVals != null ? rdVals.get(col) : null;
            if (rd != null && rev != null && rev.compareTo(BigDecimal.ZERO) != 0) {
                return rd.divide(rev, 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"));
            }
        }
        return null;
    }

    /**
     * 确保表格行有足够的单元格。
     */
    private void ensureCellCount(XWPFTableRow row, int count) {
        while (row.getTableCells().size() < count) {
            row.addNewTableCell();
        }
    }

    /**
     * 设置表格单元格文本。
     * 复用段落中已有的 run 以保留模板预设格式；换行符转为 {@code <w:br/>} 软换行。
     */
    private void setCellText(XWPFTableCell cell, String text) {
        XWPFParagraph p = cell.getParagraphs().isEmpty() ? cell.addParagraph() : cell.getParagraphs().get(0);
        if (p.getRuns().isEmpty()) {
            setRunTextWithBreaks(p.createRun(), text);
        } else {
            XWPFRun firstRun = p.getRuns().get(0);
            // 删除多余的 run，保留第一个 run 的字体样式
            for (int i = p.getRuns().size() - 1; i >= 1; i--) {
                p.removeRun(i);
            }
            setRunTextWithBreaks(firstRun, text);
        }
    }

    /**
     * 构建增长率列信息列表。
     * 每个元素为 String[3]: [header, curCol, prevCol]
     */
    private List<String[]> buildGrowthCols(List<String> dateColumns) {
        List<String> yearEndCols = dateColumns.stream()
                .filter(col -> col.matches("\\d{4}年"))
                .collect(Collectors.toList());
        List<String[]> result = new ArrayList<>();
        for (int i = 0; i < yearEndCols.size() - 1; i++) {
            result.add(new String[]{
                    extractYearFromColumn(yearEndCols.get(i)) + "年增长率",
                    yearEndCols.get(i),
                    yearEndCols.get(i + 1)
            });
        }
        return result;
    }

}
