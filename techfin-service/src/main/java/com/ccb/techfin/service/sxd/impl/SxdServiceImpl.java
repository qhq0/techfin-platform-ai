package com.ccb.techfin.service.sxd.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ccb.techfin.common.exception.BusinessException;
import com.ccb.techfin.common.util.UrlSecurityUtils;
import com.ccb.techfin.dao.sxd.AttachmentMapper;
import com.ccb.techfin.dao.sxd.DocEntryMapper;
import com.ccb.techfin.dao.sxd.SxdMapper;
import com.ccb.techfin.model.external.DocBatchAddData;
import com.ccb.techfin.model.external.DocBatchAddItem;
import com.ccb.techfin.model.external.DocDetailData;
import com.ccb.techfin.model.external.DocInfo;
import com.ccb.techfin.model.external.ExternalResponse;
import com.ccb.techfin.model.sxd.dto.request.ConfirmControllerRequest;
import com.ccb.techfin.model.sxd.dto.request.SubmitMaterialsRequest;
import com.ccb.techfin.model.sxd.dto.response.ExtractStatusResponse;
import com.ccb.techfin.model.sxd.entity.SxdAtt;
import com.ccb.techfin.model.sxd.entity.SxdRecord;
import com.ccb.techfin.model.sxd.entity.DocEntry;
import com.ccb.techfin.service.sxd.SxdService;
import com.ccb.techfin.service.external.config.ApiProperties;
import com.ccb.techfin.service.external.config.RestClientConfig;
import com.ccb.techfin.service.sxd.validator.FileValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;


/**
 * 善新贷业务服务实现：材料上传、提交、附件管理、实控人确认、提取状态轮询。
 *
 * @author qiuhaoquan
 * @since 2026-07-23
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SxdServiceImpl implements SxdService {

    private final SxdMapper sxdMapper;
    private final AttachmentMapper attachmentMapper;
    private final DocEntryMapper docEntryMapper;
    private final FileValidator fileValidator;
    private final ApiProperties apiProperties;
    /** 附件上传（multipart，写请求体可达数十 MB） */
    @Qualifier(RestClientConfig.FILE_REST_CLIENT)
    private final RestClient fileRestClient;

    /** 轻量接口：批量新增、资料详情、数据查询、状态轮询 */
    @Qualifier(RestClientConfig.API_REST_CLIENT)
    private final RestClient apiRestClient;


    @Override
    @Transactional(rollbackFor = Exception.class)
    public String uploadFile(MultipartFile file) {
        fileValidator.validate(java.util.Collections.singletonList(file));
        String attId = uploadAttachment(file);

        SxdAtt record = new SxdAtt();
        record.setAttId(attId);
        record.setFileName(file.getOriginalFilename());
        record.setFileSize(file.getSize());
        try {
            attachmentMapper.insert(record);
        } catch (DuplicateKeyException e) {
            // 本表有两个唯一约束 —— PRIMARY KEY(id) 与 uk_att_id(att_id) —— 两者抛的都是
            // DuplicateKeyException，必须区分开，不能一律当成「同一 attId 重复上报」：
            //   · 命中 uk_att_id：外部平台对同一文件重复上传时可能回同一个 attId，按幂等处理，
            //     刷新已有那行的元信息即可（避免「一个 attId 挂多行」导致后续 selectOne 抛
            //     TooManyResultsException）。MySQL 的重复键错误只回滚该语句、不中断事务，
            //     所以这里继续查询并 update 是安全的。
            //   · 命中 PRIMARY KEY(id)：说明雪花 id 撞了（例如两副本 worker-id 配成一样），
            //     此时该 att_id 在库里根本不存在，绝不能被当成幂等吞掉 —— 必须原样抛出，
            //     否则附件元信息会静默不落库，而日志还打「上传成功」。
            SxdAtt existing = attachmentMapper.selectOne(
                    new LambdaQueryWrapper<SxdAtt>().eq(SxdAtt::getAttId, attId));
            if (existing == null) {
                throw e;
            }
            log.info("Attachment already recorded, refreshing metadata: attId={}, fileName={}",
                    attId, file.getOriginalFilename());
            // 只更新元信息两列：整实体回写会把 record 上的新雪花 id 也 SET 进去，
            // 把已有那行的主键改掉。
            attachmentMapper.update(null,
                    new LambdaUpdateWrapper<SxdAtt>()
                            .eq(SxdAtt::getAttId, attId)
                            .set(SxdAtt::getFileName, file.getOriginalFilename())
                            .set(SxdAtt::getFileSize, file.getSize()));
        }

        log.info("File uploaded: attId={}, fileName={}", attId, file.getOriginalFilename());
        return attId;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String submitMaterials(SubmitMaterialsRequest request) {
        validateRequiredParams(request.getCreditCode(), request.getCstId());

        // 从请求体收集文件项，标记 businessType key（"finance"/"business"）
        List<SubmitFileMeta> allItems = new ArrayList<>();
        if (request.getFinanceFiles() != null) {
            for (SubmitMaterialsRequest.SubmitFileItem f : request.getFinanceFiles()) {
                validateSubmitFileItem(f);
                allItems.add(new SubmitFileMeta(f.getAttId(), "finance", f.getReportDate()));
            }
        }
        if (request.getBusinessFile() != null) {
            validateAttId(request.getBusinessFile());
            allItems.add(new SubmitFileMeta(request.getBusinessFile(), "business", null));
        }
        if (allItems.isEmpty()) {
            throw new BusinessException("NO_FILES", "请至少提供一个文件");
        }

        String batchTaskId = generateTaskId();

        // 创建申请记录（以 taskId 为主键）
        SxdRecord record = new SxdRecord();
        record.setTaskId(batchTaskId);
        record.setCreditCode(request.getCreditCode());
        record.setCstId(request.getCstId());
        sxdMapper.insert(record);

        try {
            // 构建批量新增参数（从 sxd_att 查文件名/大小，docTypeId 从 financeFiles/businessFile 分类确定）
            List<DocBatchAddItem> batchItems = buildBatchAddItems(allItems);
            ExternalResponse batchResponse = batchAddDocs(batchItems);

            DocBatchAddData batchData = batchResponse.getDataAs(DocBatchAddData.class);
            if (batchData.getInvalidDocNames() != null
                    && !batchData.getInvalidDocNames().isEmpty()) {
                log.warn("Some documents failed to add for taskId={}: {}",
                        batchTaskId, batchData.getInvalidDocNames());
            }

            // 通过 attId 匹配请求体中的 reportDate 和 businessType（已回填为 docTypeId），创建 DocEntry 并插入
            Map<String, SubmitFileMeta> itemIndex = allItems.stream()
                    .collect(Collectors.toMap(i -> i.attId, i -> i, (a, b) -> a));
            for (DocInfo doc : batchData.getDocList()) {
                assertValidDocId(doc.getId());
                SubmitFileMeta matched = itemIndex.get(doc.getAttId());
                DocEntry entry = new DocEntry(
                        doc.getId(),
                        batchTaskId,
                        matched != null ? matched.businessType : null,
                        matched != null ? matched.reportDate : null);
                docEntryMapper.insert(entry);
            }

            // 提交成功后删除 sxd_att 中对应的附件记录
            for (SubmitFileMeta item : allItems) {
                attachmentMapper.delete(
                        new LambdaQueryWrapper<SxdAtt>()
                                .eq(SxdAtt::getAttId, item.attId));
            }

            log.info("Application record submitted: taskId={}, creditCode={}, docCount={}",
                    batchTaskId, record.getCreditCode(), batchData.getDocList().size());

            return batchTaskId;

        } catch (BusinessException e) {
            log.warn("Submission failed for taskId={}: {}", batchTaskId, e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error submitting taskId={}", batchTaskId, e);
            throw new BusinessException("BATCH_ADD_FAILED",
                    "资料批量新增异常：" + e.getMessage());
        }
    }

    private void validateRequiredParams(String creditCode, String cstId) {
        if (!StringUtils.hasText(creditCode)) {
            throw new BusinessException("PARAM_MISSING", "统一社会信用代码不能为空");
        }
        if (!StringUtils.hasText(cstId)) {
            throw new BusinessException("PARAM_MISSING", "客户编号不能为空");
        }
        if (!creditCode.matches("^[0-9A-Z]{18}$")) {
            throw new BusinessException("INVALID_CREDIT_CODE", "统一社会信用代码格式不正确，必须为18位数字或大写字母");
        }
    }

    /**
     * 校验提交文件项：attId 非空且不含控制字符，reportDate 不含控制字符。
     * 这些字段会被拼入外部批量新增请求，含 CR/LF 可造成 HTTP 请求注入/响应截断。
     */
    private void validateSubmitFileItem(SubmitMaterialsRequest.SubmitFileItem item) {
        if (item == null) {
            throw new BusinessException("PARAM_MISSING", "文件项不能为空");
        }
        validateAttId(item.getAttId());
        UrlSecurityUtils.assertNoCrlf(item.getReportDate(),
                "INVALID_REPORT_DATE", "报告日期含非法控制字符");
    }

    /**
     * 校验附件 ID：非空且不含控制字符。
     */
    private void validateAttId(String attId) {
        if (!StringUtils.hasText(attId)) {
            throw new BusinessException("PARAM_MISSING", "附件 ID 不能为空");
        }
        UrlSecurityUtils.assertNoCrlf(attId,
                "INVALID_ATT_ID", "附件 ID 含非法控制字符");
    }

    private String uploadAttachment(MultipartFile file) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            ByteArrayResource fileResource = new ByteArrayResource(file.getBytes()) {
                @Override
                public String getFilename() {
                    return sanitizeFileName(file.getOriginalFilename());
                }
            };
            body.add("file", fileResource);
            ResponseEntity<ExternalResponse> response = fileRestClient.post()
                    .uri(apiProperties.getAttachmentUploadUrl())
                    .headers(h -> h.addAll(headers))
                    .body(body)
                    .retrieve()
                    .toEntity(ExternalResponse.class);
            ExternalResponse respBody = response.getBody();
            if (respBody == null) {
                throw new BusinessException("ATTACH_UPLOAD_FAILED", "附件上传失败：未知错误");
            }
            if (!respBody.isSuccess()) {
                throw new BusinessException("ATTACH_UPLOAD_FAILED", respBody.getMessage());
            }
            return (String) respBody.getData();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Attachment upload failed for file: {}", file.getOriginalFilename(), e);
            throw new BusinessException("ATTACH_UPLOAD_FAILED", e.getMessage());
        }
    }

    /**
     * 净化文件名中的 CR/LF/NUL 控制字符。
     * <p>
     * 原始文件名会被 Spring 序列化进 multipart 请求的
     * Content-Disposition: form-data; name="file"; filename="&lt;name&gt;" 头，
     * 若含 CR/LF 可突破引号注入/截断外部请求头部（HTTP 请求拆分），故必须清除。
     * </p>
     */
    private static String sanitizeFileName(String fileName) {
        if (fileName == null) {
            return null;
        }
        return fileName.replaceAll("[\\r\\n\\u0000]", "");
    }

    /**
     * 根据请求中的文件项列表构建批量新增请求参数。
     * fileName/fileSize 从 sxd_att 表查询，docTypeId 根据 financeFiles/businessFile 分类从配置获取。
     *
 * @author qiuhaoquan
 * @since 2026-07-23
 */
    private List<DocBatchAddItem> buildBatchAddItems(List<SubmitFileMeta> items) {
        List<DocBatchAddItem> result = new ArrayList<>();
        Map<String, Long> docTypeMap = apiProperties.getDocType();
        Long dirId = apiProperties.getDirId();
        Long sxdProjectId = apiProperties.getSxdProjectId();
        for (SubmitFileMeta item : items) {
            // 根据 businessType key ("finance"/"business") 查找对应的 docTypeId
            Long docTypeId = docTypeMap.get(item.businessType);
            if (docTypeId == null) {
                throw new BusinessException("INVALID_BUSINESS_TYPE",
                        "未知的业务类型：" + item.businessType);
            }
            // 从 sxd_att 查询文件元信息
            SxdAtt att = attachmentMapper.selectOne(
                    new LambdaQueryWrapper<SxdAtt>()
                            .eq(SxdAtt::getAttId, item.attId));
            if (att == null) {
                throw new BusinessException("ATTACH_NOT_FOUND",
                        "附件 " + item.attId + " 不存在，请重新上传");
            }
            // 槽位与文件格式必须匹配：上传时不知道文件属于哪个槽位（FileValidator 只能对两个槽位
            // 求并集），业务类型到这里才确定，所以在这里补上按槽位的校验（需求 §2.2-A / §2.2-B）。
            // ⚠️ 必须放在下一行把 businessType 回填成 docTypeId 之前 —— 回填后就拿不到槽位 key 了。
            // 位置也刻意排在 batchAddDocs 之前：失败时还没产生任何外部副作用。
            fileValidator.validateByBusinessType(att.getFileName(), item.businessType);
            // 回填 businessType 为 docTypeId 值，便于下游 DocEntry 使用
            item.businessType = String.valueOf(docTypeId);
            // 附加 attId 后 6 位确保 docName 全局唯一（外部 API 要求 docName 不重复）
            String uniqueDocName = makeUniqueDocName(att.getFileName(), item.attId);
            result.add(DocBatchAddItem.builder()
                    .attId(item.attId)
                    .dirId(dirId)
                    .docName(uniqueDocName)
                    .docSize(att.getFileSize() / 1024)   // byte → KB
                    .docTypeId(docTypeId)
                    .extraInfo("{}")
                    .projectId(sxdProjectId)
                    .reportDate(item.reportDate)
                    .build());
        }
        return result;
    }

    /**
     * 在文件名末尾附加 attId 后 6 位，确保 docName 全局唯一。
     * 例如：财报.pdf → 财报_a3f2c1.pdf
     */
    private static String makeUniqueDocName(String fileName, String attId) {
        String suffix = attId.substring(Math.max(0, attId.length() - 6));
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            return fileName.substring(0, dot) + "_" + suffix + fileName.substring(dot);
        }
        return fileName + "_" + suffix;
    }

    private ExternalResponse batchAddDocs(List<DocBatchAddItem> items) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<List<DocBatchAddItem>> requestEntity = new HttpEntity<>(items, headers);
            ResponseEntity<ExternalResponse> response = apiRestClient.post()
                    .uri(apiProperties.getDocBatchAddUrl())
                    .headers(h -> h.addAll(requestEntity.getHeaders()))
                    .body(items)
                    .retrieve()
                    .toEntity(ExternalResponse.class);
            ExternalResponse respBody = response.getBody();
            if (respBody == null) {
                throw new BusinessException("BATCH_ADD_FAILED", "资料批量新增失败：未知错误");
            }
            if (!respBody.isSuccess() || respBody.getData() == null) {
                throw new BusinessException("BATCH_ADD_FAILED", respBody.getMessage());
            }
            return respBody;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Batch add documents failed", e);
            throw new BusinessException("BATCH_ADD_FAILED", e.getMessage());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirmControllerName(ConfirmControllerRequest request) {
        if (request == null || !StringUtils.hasText(request.getTaskId())) {
            throw new BusinessException("PARAM_MISSING", "任务 ID 不能为空");
        }
        if (!StringUtils.hasText(request.getActCntlrNm())) {
            throw new BusinessException("PARAM_MISSING", "实际控制人姓名不能为空");
        }

        SxdRecord record = sxdMapper.selectById(request.getTaskId());
        if (record == null) {
            throw new BusinessException("TASK_NOT_FOUND",
                    "任务 [" + request.getTaskId() + "] 不存在");
        }

        // 只更新 act_cntlr_nm 一列：整实体回写会把其它请求 / 其它副本刚改过的列覆盖回去。
        sxdMapper.update(null, new LambdaUpdateWrapper<SxdRecord>()
                .eq(SxdRecord::getTaskId, request.getTaskId())
                .set(SxdRecord::getActCntlrNm, request.getActCntlrNm())
                .set(SxdRecord::getUpdatedAt, LocalDateTime.now()));

        log.info("Controller name confirmed: taskId={}, actCntlrNm={}",
                request.getTaskId(), request.getActCntlrNm());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteAttachment(String attId) {
        if (!StringUtils.hasText(attId)) {
            return false;
        }
        int deleted = attachmentMapper.delete(
                new LambdaQueryWrapper<SxdAtt>()
                        .eq(SxdAtt::getAttId, attId));
        boolean success = deleted > 0;
        if (success) {
            log.info("Attachment deleted: attId={}", attId);
        } else {
            log.warn("Attachment not found for deletion: attId={}", attId);
        }
        return success;
    }

    /**
     * 查询任务的资料提取状态。
     *
     * <p>逐份查询外部资料详情接口，<b>单份失败不中断整次轮询</b>：失败的文档计入
     * {@code pendingDocNames}（以 {@code docId=xxx（状态查询失败）} 标注），使 {@code completed}
     * 保持 {@code false} —— 文档数越多，一次网络抖动导致整屏报错的概率越高，不该让一份拖垮全部。
     * 只有<b>全部</b>文档都查询失败（DIB 不可达 / 鉴权失效 / 池耗尽这类系统性故障）才抛
     * {@code DOC_DETAIL_FAILED}，否则前端会拿着永远不完成的 pending 无休止轮询。
     *
     * <p>因此 {@code pendingDocNames} 里既可能是「确实还在跑」的文档名，也可能是查不到状态的
     * docId 标注项，前端按原样展示即可。
     */
    @Override
    public ExtractStatusResponse queryExtractStatus(String taskId) {
        // 查询该任务下的所有文档
        List<DocEntry> docEntries = docEntryMapper.selectList(
                new LambdaQueryWrapper<DocEntry>()
                        .eq(DocEntry::getTaskId, taskId));

        if (docEntries == null || docEntries.isEmpty()) {
            throw new BusinessException("DOC_NOT_FOUND",
                    "任务 [" + taskId + "] 下未找到文档记录");
        }

        String detailUrlBase = apiProperties.getDocDetailUrl();
        List<String> pendingDocNames = new ArrayList<>();
        int failedCount = 0;

        for (DocEntry entry : docEntries) {
            DocDetailData detail;
            try {
                detail = getDocDetail(detailUrlBase + "/" + entry.getDocId());
            } catch (BusinessException e) {
                // 绝不能把查询失败当作"已完成"：计入 pending 让 completed 保持 false，
                // 并用 docId 标注，前端展示和事后排查都能定位到是哪一份卡住
                failedCount++;
                pendingDocNames.add("docId=" + entry.getDocId() + "（状态查询失败）");
                log.warn("Failed to query extract state for docId={} (taskId={}), treat as pending: {}",
                        entry.getDocId(), taskId, e.getMessage());
                continue;
            }
            String state = detail.getExtractState();
            // U=待执行, I=执行中 — 视为未完成
            if ("U".equals(state) || "I".equals(state)) {
                pendingDocNames.add(detail.getDocName());
            }
        }

        if (failedCount == docEntries.size()) {
            throw new BusinessException("DOC_DETAIL_FAILED",
                    "任务 [" + taskId + "] 下 " + failedCount
                            + " 份文档的状态查询全部失败，外部资料详情接口不可用");
        }

        boolean completed = pendingDocNames.isEmpty();
        return new ExtractStatusResponse(completed, pendingDocNames);
    }

    private DocDetailData getDocDetail(String url) {
        UrlSecurityUtils.assertNoCrlf(url);
        try {
            // 鉴权头由 apiRestClient 的拦截器统一注入
            ResponseEntity<ExternalResponse> response = apiRestClient.get()
                    .uri(url)
                    .retrieve()
                    .toEntity(ExternalResponse.class);
            ExternalResponse respBody = response.getBody();
            if (respBody == null) {
                throw new BusinessException("DOC_DETAIL_FAILED", "资料详情查询失败：未知错误");
            }
            if (!respBody.isSuccess() || respBody.getData() == null) {
                throw new BusinessException("DOC_DETAIL_FAILED", respBody.getMessage());
            }
            return respBody.getDataAs(DocDetailData.class);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to query doc detail for url: {}", url, e);
            throw new BusinessException("DOC_DETAIL_FAILED", "资料详情查询异常：" + e.getMessage());
        }
    }

    /**
     * 校验 docId 仅由数字组成。
     * docId 来自外部 API 且会被拼入对外请求 URL，若含 CR/LF 等控制字符
     * （含百分号编码 %0d/%0a）会造成 HTTP 响应截断/拆分（CRLF 注入），故入库前必须拦截。
     */
    private void assertValidDocId(String docId) {
        if (!StringUtils.hasText(docId) || !docId.matches("^[0-9]+$")) {
            throw new BusinessException("INVALID_DOC_ID",
                    "文档 ID 非法：" + (docId == null ? "null" : docId));
        }
    }

    private String generateTaskId() {
        UUID uuid = UUID.randomUUID();
        return "TASK-" + String.format("%016x%016x",
                uuid.getMostSignificantBits(), uuid.getLeastSignificantBits());
    }

    @lombok.AllArgsConstructor
    private static class SubmitFileMeta {
        final String attId;
        String businessType;          // 初始为 "finance"/"business"，build 时回填为 docTypeId 值
        final String reportDate;
    }
}
