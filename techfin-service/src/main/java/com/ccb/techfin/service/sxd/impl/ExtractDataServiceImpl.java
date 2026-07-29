package com.ccb.techfin.service.sxd.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ccb.techfin.common.exception.BusinessException;
import com.ccb.techfin.dao.sxd.DocEntryMapper;
import com.ccb.techfin.dao.sxd.ExtractDataMapper;
import com.ccb.techfin.dao.sxd.SxdMapper;
import com.ccb.techfin.model.sxd.dto.external.*;
import com.ccb.techfin.model.sxd.dto.response.ExtractDataItem;
import com.ccb.techfin.model.sxd.entity.*;
import com.ccb.techfin.service.sxd.CustomerService;
import com.ccb.techfin.service.sxd.ExtractDataService;
import com.ccb.techfin.service.sxd.config.ApiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.xmlbeans.XmlCursor;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
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
    private final RestTemplate restTemplate;
    private final CustomerService customerService;
    private final ResourceLoader resourceLoader;

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

    /** 占位符名称 -> CustomerProfile 字段值提取函数 */
    private static final Map<String, Function<CustomerProfile, String>> PROFILE_FIELD_GETTERS = Map.ofEntries(
            Map.entry("cst_nm", CustomerProfile::getCstNm),
            Map.entry("credit_code", CustomerProfile::getCreditCode),
            Map.entry("fd_dt", CustomerProfile::getFdDt),
            Map.entry("lgl_rprs_nm", CustomerProfile::getLglRprsNm),
            Map.entry("rgst_cpamt", CustomerProfile::getRgstCpamt),
            Map.entry("arcptl_cpamt", CustomerProfile::getArcptlCpamt),
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
            Map.entry("if_loan", CustomerProfile::getIfLoan),
            Map.entry("product_name", CustomerProfile::getProductName),
            Map.entry("loan_amount", CustomerProfile::getLoanAmount),
            Map.entry("loan_term", CustomerProfile::getLoanTerm),
            Map.entry("loan_balance", CustomerProfile::getLoanBalance),
            Map.entry("dep_bal", CustomerProfile::getDepBal),
            Map.entry("dep_bal_dt", CustomerProfile::getDepBalDt),
            Map.entry("dep_aadbal", CustomerProfile::getDepAadbal),
            Map.entry("acc_start_dt", CustomerProfile::getAccStartDt),
            Map.entry("acc_type", CustomerProfile::getAccType),
            Map.entry("isug_pnum", CustomerProfile::getIsugPnum),
            Map.entry("avg_12_isug_amt", CustomerProfile::getAvg12IsugAmt),
            Map.entry("if_yuqi", CustomerProfile::getIfYuqi),
            Map.entry("ltgtrltd_ind", CustomerProfile::getLtgtrltdInd),
            Map.entry("if_rad_alarm", CustomerProfile::getIfRadAlarm)
    );

    /** 无管户权时需清空的敏感字段占位符 */
    private static final Set<String> OWNERSHIP_SENSITIVE_FIELDS = Set.of(
            "tech_tag", "tech_flow", "kc_score",
            "entp_ptnt_num", "entp_prct_new_tp_ptnt_num", "entp_ivt_ptnt_num",
            "clst_5yr_inn_rs_wcopr_num",
            "if_loan", "product_name", "loan_amount", "loan_term", "loan_balance",
            "dep_bal", "dep_bal_dt", "dep_aadbal",
            "acc_start_dt", "acc_type", "isug_pnum", "avg_12_isug_amt",
            "if_yuqi", "ltgtrltd_ind", "if_rad_alarm"
    );

    private static final String ITEM_ASSET_TOTAL = "资产总计";
    private static final String ITEM_LIABILITY_TOTAL = "负债合计";

    private static final String ITEM_REVENUE = "营业收入";
    private static final String ITEM_COST = "营业成本";
    private static final String ITEM_NET_PROFIT = "净利润";
    private static final String ITEM_RD_EXPENSE = "研发费用";

    /** 元 转 万元 */
    private static final BigDecimal TEN_THOUSAND = new BigDecimal("10000");

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
        if (r.getPosition() != null && !r.getPosition().isEmpty()) {
            sb.append("职位：").append(r.getPosition());
        }
        if (r.getResume() != null && !r.getResume().isEmpty()) {
            if (sb.length() > 0) sb.append("，");
            sb.append("简介：").append(r.getResume());
        }
        return sb.toString();
    }

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

        // 只有缓存包含全部 BUSINESS_PLAN_TABLES 才直接返回，否则视为不完整，删除旧缓存重新拉取
        if (cachedList != null && !cachedList.isEmpty()) {
            Set<String> cachedTables = cachedList.stream()
                    .map(ExtractData::getTableName)
                    .collect(Collectors.toSet());
            if (cachedTables.containsAll(BUSINESS_PLAN_TABLES)) {
                return buildResponseFromCache(cachedList);
            }
            log.info("Cache incomplete for taskId={} ({} of {} tables), re-fetching from external API",
                    taskId, cachedTables.size(), BUSINESS_PLAN_TABLES.size());
            extractDataMapper.delete(new LambdaQueryWrapper<ExtractData>()
                    .eq(ExtractData::getTaskId, taskId));
        }

        // 缓存未命中或不完整，调外部 API 并写入缓存
        String token = apiProperties.getDefaultToken();

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
                List<String> texts = queryBusinessExtractDataByTable(docId, tableName, token);
                String mergedText = String.join("\n", texts);
                ExtractData extractData = new ExtractData();
                extractData.setTaskId(taskId);
                extractData.setDocId(entry.getDocId());
                extractData.setTableName(tableName);
                extractData.setText(mergedText);
                extractDataMapper.insert(extractData);
            }
        }

        // 从缓存表读取并构建响应
        cachedList = extractDataMapper.selectList(
                new LambdaQueryWrapper<ExtractData>()
                        .eq(ExtractData::getTaskId, taskId));
        return buildResponseFromCache(cachedList);
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
    public byte[] exportFinanceExtractData(String taskId) {
        String financeDocType = String.valueOf(apiProperties.getDocType().get("finance"));
        List<DocEntry> entries = docEntryMapper.selectList(
                new LambdaQueryWrapper<DocEntry>()
                        .eq(DocEntry::getTaskId, taskId)
                        .eq(DocEntry::getBusinessType, financeDocType));

        if (entries == null || entries.isEmpty()) {
            throw new BusinessException("DOC_NOT_FOUND",
                    "任务 [" + taskId + "] 下未找到财务报表文档");
        }

        // 逐个下载每个财务报表的 xlsx 并打包为 zip，命名格式：财务报表_{reportDate}.xlsx
        Map<String, Integer> dateCounter = new HashMap<>();
        List<AbstractMap.SimpleEntry<String, byte[]>> fileEntries = new ArrayList<>();
        for (DocEntry entry : entries) {
            byte[] data;
            try {
                data = downloadExportFile(entry.getDocId());
            } catch (BusinessException e) {
                log.warn("Failed to download export file for docId={}, skipping: {}", entry.getDocId(), e.getMessage());
                continue;
            }
            // 处理空日期和重复日期
            String baseName = entry.getReportDate() != null && !entry.getReportDate().isEmpty()
                    ? entry.getReportDate() : "未知日期";
            int count = dateCounter.merge(baseName, 1, Integer::sum);
            String fileName = count == 1
                    ? "财务报表_" + baseName + ".xlsx"
                    : "财务报表_" + baseName + "_" + count + ".xlsx";
            fileEntries.add(new AbstractMap.SimpleEntry<>(fileName, data));
        }

        if (fileEntries.isEmpty()) {
            throw new BusinessException("DOC_EXPORT_FAILED",
                    "任务 [" + taskId + "] 下所有财务报表导出均失败");
        }

        return createFinanceZip(fileEntries);
    }

    /**
     * 调用外部导出资料接口，下载单个文档的 xlsx 文件字节流。
     */
    private byte[] downloadExportFile(String docId) {
        String url = apiProperties.getDocExportDataUrl() + "/" + docId;
        try {
            HttpHeaders headers = new HttpHeaders();
            if (StringUtils.hasText(apiProperties.getDefaultToken())) {
                headers.set("c1-token", apiProperties.getDefaultToken());
            }
            HttpEntity<Void> requestEntity = new HttpEntity<>(headers);
            ResponseEntity<byte[]> response = restTemplate.exchange(
                    url, HttpMethod.GET, requestEntity, byte[].class);

            byte[] body = response.getBody();
            if (body == null || body.length == 0) {
                throw new BusinessException("DOC_EXPORT_FAILED",
                        "文档 " + docId + " 导出数据为空");
            }
            log.info("Exported doc data: docId={}, size={} bytes", docId, body.length);
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
     * 将多个 xlsx 文件打包成 zip 压缩包。
     *
     * @param fileEntries entry 列表，每个 entry 的 key 为文件名，value 为文件字节数据
     */
    private byte[] createFinanceZip(List<AbstractMap.SimpleEntry<String, byte[]>> fileEntries) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (AbstractMap.SimpleEntry<String, byte[]> fileEntry : fileEntries) {
                String entryName = fileEntry.getKey();
                byte[] data = fileEntry.getValue();
                ZipEntry zipEntry = new ZipEntry(entryName);
                zos.putNextEntry(zipEntry);
                zos.write(data);
                zos.closeEntry();
            }
        } catch (IOException e) {
            log.error("Failed to create zip archive", e);
            throw new BusinessException("ZIP_CREATE_FAILED",
                    "财务报表压缩包创建失败：" + e.getMessage());
        }
        return baos.toByteArray();
    }

    /**
     * 调用外部提取数据查询 API，返回该 tableName 下的文本列表。
     */
    private List<String> queryBusinessExtractDataByTable(Long docId, String tableName, String token) {
        BpExtractRequest request = new BpExtractRequest(docId, tableName);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (StringUtils.hasText(token)) {
                headers.set("c1-token", token);
            }

            HttpEntity<BpExtractRequest> requestEntity = new HttpEntity<>(request, headers);
            ResponseEntity<ExternalResponse> response = restTemplate.exchange(
                    apiProperties.getDocQueryDataUrl(),
                    HttpMethod.POST,
                    requestEntity,
                    ExternalResponse.class);

            ExternalResponse respBody = response.getBody();
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

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Failed to query extract data for docId={}, tableName={}: {}", docId, tableName, e.getMessage());
            return Collections.emptyList();
        }
    }

    public byte[] generateReport(String taskId, String cstId) {
        // ============ 校验任务存在 ============
        SxdRecord record = sxdMapper.selectById(taskId);
        if (record == null) {
            throw new BusinessException("TASK_NOT_FOUND",
                    "任务 [" + taskId + "] 不存在");
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

        String token = apiProperties.getDefaultToken();

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
                states = queryDocTableStates(docId, token);
            } catch (BusinessException e) {
                log.warn("Query table states failed for docId={}: {}", docId, e.getMessage());
                continue;
            }
            String bsTableName = determineSheetTable(docId, states, token,
                    "dib_fin_balance", "dib_fin_balance_parent", "资产负债表");
            String psTableName = determineSheetTable(docId, states, token,
                    "dib_fin_profit_statement", "dib_fin_profit_statement_parent", "利润表");

            // 查询资产负债表数据
            List<FinanceRecord> bsRecords = null;
            try {
                bsRecords = queryFinanceData(docId, bsTableName, token);
            } catch (BusinessException e) {
                log.warn("Query failed for docId={} balance sheet: {}", docId, e.getMessage());
            }
            if (bsRecords == null || bsRecords.isEmpty()) {
                log.warn("No balance sheet data for docId={}", docId);
            }

            // 查询利润表数据
            List<FinanceRecord> psRecords = null;
            try {
                psRecords = queryFinanceData(docId, psTableName, token);
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
                fillLastAmountColumn(bsRecords, bsItemDateValues, bsDateColumns, dateCol, reportDate);
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
                fillLastAmountColumnForProfit(psRecords, psItemDateValues, psDateColumns, dateCol, reportDate);
            }
        }

        if (bsDateColumns.isEmpty()) {
            log.warn("No balance sheet data found for taskId={}, placeholder will be empty", taskId);
        }
        if (psDateColumns.isEmpty()) {
            log.warn("No profit sheet data found for taskId={}, placeholder will be empty", taskId);
        }

        // ============ 从缓存表读取商业计划书提取数据（在清理之前） ============
        Map<String, String> extractTextMap = loadExtractDataFromCache(taskId);

        // ============ 从 sxd_record 读取实控人姓名和管户权 ============
        String actCntlrNm = record.getActCntlrNm();
        boolean hasOwnership = "1".equals(record.getHasOwnership());

        // ============ 生成 Word 文档 ============
        byte[] document = createWordDocument(customerProfile, actCntlrNm, hasOwnership,
                bsItemDateValues, bsDateColumns,
                psItemDateValues, psDateColumns, extractTextMap);

        // ============ 更新任务状态 + 清理文档记录和缓存 ============
        docEntryMapper.delete(new LambdaQueryWrapper<DocEntry>()
                .eq(DocEntry::getTaskId, taskId));
        extractDataMapper.delete(new LambdaQueryWrapper<ExtractData>()
                .eq(ExtractData::getTaskId, taskId));
        record.setStatus("1");
        sxdMapper.updateById(record);

        // ============ 删除外部系统中的文档 ============
        deleteExternalDocs(entries, token);

        return document;
    }

    /**
     * 查询文档的表格提取状态列表。
     */
    private List<DocTableStateRecord> queryDocTableStates(Long docId, String token) {
        String url = apiProperties.getDocTableExtractStateUrl() + "/" + docId;
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (StringUtils.hasText(token)) {
                headers.set("c1-token", token);
            }
            HttpEntity<Void> requestEntity = new HttpEntity<>(headers);
            ResponseEntity<ExternalResponse> response = restTemplate.exchange(
                    url, HttpMethod.POST, requestEntity, ExternalResponse.class);
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
     */
    private String determineSheetTable(Long docId, List<DocTableStateRecord> states, String token,
                                       String mergeTableName, String parentTableName, String reportTypeName) {
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
            String scope = queryFinanceReportScope(docId, token);
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
     * 从审计报告附注表（dib_intervening_y_auditreport_jh）中查询"财务报表口径"的值。
     *
     * @return "1"(单一) 或 "2"(合并)，查询失败返回 null
     */
    private String queryFinanceReportScope(Long docId, String token) {
        BpExtractRequest request = new BpExtractRequest(docId,
                "dib_intervening_y_auditreport_jh");
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (StringUtils.hasText(token)) {
                headers.set("c1-token", token);
            }
            HttpEntity<BpExtractRequest> requestEntity = new HttpEntity<>(request, headers);
            ResponseEntity<ExternalResponse> response = restTemplate.exchange(
                    apiProperties.getDocQueryDataUrl(),
                    HttpMethod.POST,
                    requestEntity,
                    ExternalResponse.class);
            ExternalResponse respBody = response.getBody();
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
    private List<FinanceRecord> queryFinanceData(Long docId, String tableName, String token) {
        BpExtractRequest request = new BpExtractRequest(docId, tableName);
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (StringUtils.hasText(token)) {
                headers.set("c1-token", token);
            }
            HttpEntity<BpExtractRequest> requestEntity = new HttpEntity<>(request, headers);
            ResponseEntity<ExternalResponse> response = restTemplate.exchange(
                    apiProperties.getDocQueryDataUrl(),
                    HttpMethod.POST,
                    requestEntity,
                    ExternalResponse.class);
            ExternalResponse respBody = response.getBody();
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
     * 单个文档删除失败仅告警，不影响整体流程。
     */
    private void deleteExternalDocs(List<DocEntry> entries, String token) {
        for (DocEntry entry : entries) {
            try {
                deleteExternalDoc(entry.getDocId(), token);
            } catch (Exception e) {
                log.warn("Failed to delete external doc {}: {}", entry.getDocId(), e.getMessage());
            }
        }
    }

    /**
     * 调用外部 API 删除单个文档。
     */
    private void deleteExternalDoc(String docId, String token) {
        String url = apiProperties.getDocDeleteUrl() + "/" + docId;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (StringUtils.hasText(token)) {
            headers.set("c1-token", token);
        }
        HttpEntity<Void> requestEntity = new HttpEntity<>(headers);
        restTemplate.exchange(url, HttpMethod.POST, requestEntity, ExternalResponse.class);
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
                                      String currentDateCol, String reportDate) {
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
                                               String currentDateCol, String reportDate) {
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
     * 计算增长率并格式化。
     * (current - previous) / |previous| * 100，保留2位小数加百分号。
     * previous=0 → "9999999"；任一为 null → "-"
     */
    private String formatGrowthRate(BigDecimal current, BigDecimal previous) {
        if (current == null || previous == null) return "-";
        if (previous.compareTo(BigDecimal.ZERO) == 0) return "-";
        BigDecimal rate = current.subtract(previous)
                .divide(previous.abs(), 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));
        return String.format("%.2f%%", rate);
    }

    /**
     * 计算资产负债率并格式化为百分比。负债合计 / 资产总计 * 100%
     */
    private String formatLiabilityRatio(BigDecimal assetTotal, BigDecimal liabilityTotal) {
        if (assetTotal == null || liabilityTotal == null) return "-";
        if (assetTotal.compareTo(BigDecimal.ZERO) == 0) return "-";
        BigDecimal ratio = liabilityTotal.divide(assetTotal, 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));
        return String.format("%.2f%%", ratio);
    }

    /**
     * 计算资产负债率的数值（用于增长率计算）。null 表示无法计算。
     */
    private BigDecimal calcLiabilityRatio(BigDecimal assetTotal, BigDecimal liabilityTotal) {
        if (assetTotal == null || liabilityTotal == null || assetTotal.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return liabilityTotal.divide(assetTotal, 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));
    }

    // ========== 利润表辅助方法 ==========

    /**
     * 从利润表记录中按 item_standard 精确匹配指定科目的 current_amount。
     * 如"营业收入"匹配 item_standard="其中：营业收入"。
     */
    private BigDecimal findProfitItemValue(List<FinanceRecord> records, String displayName) {
        List<String> keys = PROFIT_ITEM_SEARCH_KEYS.get(displayName);
        if (keys == null || records == null) return null;

        for (FinanceRecord r : records) {
            if (r.getItemStandard() != null && keys.contains(r.getItemStandard())) {
                return r.getCurrentAmount();
            }
        }
        return null;
    }

    /**
     * 从利润表记录中按 item_standard 匹配指定科目的 last_amount。
     */
    private BigDecimal findProfitItemLastAmount(List<FinanceRecord> records, String displayName) {
        List<String> keys = PROFIT_ITEM_SEARCH_KEYS.get(displayName);
        if (keys == null || records == null) return null;

        for (FinanceRecord r : records) {
            if (r.getItemStandard() != null && keys.contains(r.getItemStandard())) {
                return r.getLastAmount();
            }
        }
        return null;
    }

    /**
     * 计算比率并格式化为百分比。numerator / denominator × 100%
     * 任一参数为 null 或 denominator=0 时返回 "-"
     */
    private String formatRatio(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null) return "-";
        if (denominator.compareTo(BigDecimal.ZERO) == 0) return "-";
        BigDecimal ratio = numerator.divide(denominator, 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));
        return String.format("%.2f%%", ratio);
    }

    /**
     * 构建利润表关键科目的 markdown 表格。
     * <p>
     * 包含 9 个基本科目（营业收入 ~ 净利润）+ 3 个计算比例行（毛利润率、净利润率、研发费用占比）。
     * 增长率规则同资产负债表关键科目：取最后两个年末（12-31）列计算同比。
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
        String templatePath = apiProperties.getReportTemplatePath();
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
                replacePlaceholderText(doc, "{{balance_sheet_key_items}}", "");
            }

            // 4. 替换利润表关键科目占位符 -> 插入表格或清空
            if (!psDateColumns.isEmpty()) {
                replacePlaceholderWithTable(doc, "{{profit_sheet_key_items}}",
                        table -> fillProfitSheetTable(table, psItemDateValues, psDateColumns));
            } else {
                replacePlaceholderText(doc, "{{profit_sheet_key_items}}", "");
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
     * 使用 {@link XWPFParagraph#getText()} 拼接所有 run 后再替换，避免占位符被拆分到多个 run 中无法匹配。
     */
    private void replaceProfilePlaceholders(XWPFDocument doc, CustomerProfile profile, String actCntlrNm, boolean hasOwnership) {
        for (XWPFParagraph para : doc.getParagraphs()) {
            String paraText = para.getText();
            if (paraText == null || !paraText.contains("{{")) continue;

            String replaced = paraText;
            boolean changed = false;
            for (Map.Entry<String, Function<CustomerProfile, String>> entry : PROFILE_FIELD_GETTERS.entrySet()) {
                String placeholder = "{{" + entry.getKey() + "}}";
                if (replaced.contains(placeholder)) {
                    String value;
                    if (!hasOwnership && OWNERSHIP_SENSITIVE_FIELDS.contains(entry.getKey())) {
                        value = "";
                    } else {
                        value = profile != null ? entry.getValue().apply(profile) : "";
                        value = value != null ? value : "";
                    }
                    replaced = replaced.replace(placeholder, value);
                    changed = true;
                }
            }
            // 实控人姓名从 sxd_record 表读取
            if (replaced.contains("{{act_cntlr_nm}}")) {
                replaced = replaced.replace("{{act_cntlr_nm}}",
                        actCntlrNm != null ? actCntlrNm : "");
                changed = true;
            }
            if (changed) {
                // 清除段落中所有 run，用替换后的完整文本创建新 run
                for (int i = para.getRuns().size() - 1; i >= 0; i--) {
                    para.removeRun(i);
                }
                para.createRun().setText(replaced, 0);
            }
        }
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
     * 替换文档段落中 {{dib_director_keyresume}} 等商业计划书提取数据占位符。
     * 使用 {@link XWPFParagraph#getText()} 拼接所有 run 后再替换，避免占位符被拆分到多个 run 中无法匹配。
     */
    private void replaceExtractDataPlaceholders(XWPFDocument doc, Map<String, String> extractTextMap) {
        for (XWPFParagraph para : doc.getParagraphs()) {
            String paraText = para.getText();
            if (paraText == null || !paraText.contains("{{")) continue;

            String replaced = paraText;
            boolean changed = false;
            for (Map.Entry<String, String> entry : extractTextMap.entrySet()) {
                String placeholder = "{{" + entry.getKey() + "}}";
                if (replaced.contains(placeholder)) {
                    String value = entry.getValue();
                    replaced = replaced.replace(placeholder, value != null ? value : "");
                    changed = true;
                }
            }
            if (changed) {
                // 清除段落中所有 run，用替换后的完整文本创建新 run
                for (int i = para.getRuns().size() - 1; i >= 0; i--) {
                    para.removeRun(i);
                }
                para.createRun().setText(replaced, 0);
            }
        }
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
                    XmlCursor cursor = para.getCTP().newCursor();
                    XWPFTable table = doc.insertNewTbl(cursor);
                    cursor.dispose();

                    tableFiller.accept(table);

                    doc.removeBodyElement(i + 1);
                    return;
                }
            }
        }
        log.warn("Placeholder not found in template: {}", placeholder);
    }

    /**
     * 将文档中指定占位符替换为给定的文本。
     */
    private void replacePlaceholderText(XWPFDocument doc, String placeholder, String replacement) {
        for (XWPFParagraph para : doc.getParagraphs()) {
            for (XWPFRun run : para.getRuns()) {
                String text = run.getText(0);
                if (text != null && text.contains(placeholder)) {
                    run.setText(text.replace(placeholder, replacement), 0);
                    return;
                }
            }
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
        while (table.getRows().size() > 0) {
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
        setCellText(ratioRow.getCell(0), "资产负债率");
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
        while (table.getRows().size() > 0) {
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
        fillRatioRowAt(table, base, itemDateValues, dateColumns, growthCols, "毛利润率");
        fillRatioRowAt(table, base + 1, itemDateValues, dateColumns, growthCols, "净利润率");
        fillRatioRowAt(table, base + 2, itemDateValues, dateColumns, growthCols, "研发费用占比");
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
            String val;
            if ("毛利润率".equals(label)) {
                Map<String, BigDecimal> revVals = itemDateValues.get(ITEM_REVENUE);
                Map<String, BigDecimal> costVals = itemDateValues.get(ITEM_COST);
                BigDecimal rev = revVals != null ? revVals.get(col) : null;
                BigDecimal cost = costVals != null ? costVals.get(col) : null;
                val = (rev != null && cost != null && rev.compareTo(BigDecimal.ZERO) != 0)
                        ? formatRatio(rev.subtract(cost), rev) : "-";
            } else if ("净利润率".equals(label)) {
                Map<String, BigDecimal> revVals = itemDateValues.get(ITEM_REVENUE);
                Map<String, BigDecimal> profitVals = itemDateValues.get(ITEM_NET_PROFIT);
                val = formatRatio(
                        profitVals != null ? profitVals.get(col) : null,
                        revVals != null ? revVals.get(col) : null);
            } else {
                Map<String, BigDecimal> revVals = itemDateValues.get(ITEM_REVENUE);
                Map<String, BigDecimal> rdVals = itemDateValues.get(ITEM_RD_EXPENSE);
                val = formatRatio(
                        rdVals != null ? rdVals.get(col) : null,
                        revVals != null ? revVals.get(col) : null);
            }
            setCellText(row.getCell(1 + c), val);
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
        if ("毛利润率".equals(label)) {
            Map<String, BigDecimal> revVals = itemDateValues.get(ITEM_REVENUE);
            Map<String, BigDecimal> costVals = itemDateValues.get(ITEM_COST);
            BigDecimal rev = revVals != null ? revVals.get(col) : null;
            BigDecimal cost = costVals != null ? costVals.get(col) : null;
            if (rev != null && cost != null && rev.compareTo(BigDecimal.ZERO) != 0) {
                return rev.subtract(cost).divide(rev, 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"));
            }
        } else if ("净利润率".equals(label)) {
            Map<String, BigDecimal> revVals = itemDateValues.get(ITEM_REVENUE);
            Map<String, BigDecimal> profitVals = itemDateValues.get(ITEM_NET_PROFIT);
            BigDecimal rev = revVals != null ? revVals.get(col) : null;
            BigDecimal profit = profitVals != null ? profitVals.get(col) : null;
            if (profit != null && rev != null && rev.compareTo(BigDecimal.ZERO) != 0) {
                return profit.divide(rev, 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"));
            }
        } else {
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
     * 复用段落中已有的 run 以保留模板预设格式，仅写入文本内容。
     */
    private void setCellText(XWPFTableCell cell, String text) {
        XWPFParagraph p = cell.getParagraphs().isEmpty() ? cell.addParagraph() : cell.getParagraphs().get(0);
        // 清除段落中的已有 run，避免模板占位符残留
        for (int i = p.getRuns().size() - 1; i >= 0; i--) {
            p.removeRun(i);
        }
        XWPFRun r = p.createRun();
        r.setText(text != null ? text : "");
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
