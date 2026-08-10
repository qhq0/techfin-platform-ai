package com.ccb.techfin.service.sxd.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ccb.techfin.common.exception.BusinessException;
import com.ccb.techfin.common.util.UrlSecurityUtils;
import com.ccb.techfin.dao.sxd.AttachmentMapper;
import com.ccb.techfin.dao.sxd.DocEntryMapper;
import com.ccb.techfin.dao.sxd.SxdMapper;
import com.ccb.techfin.model.sxd.dto.external.DocBatchAddData;
import com.ccb.techfin.model.sxd.dto.external.DocBatchAddItem;
import com.ccb.techfin.model.sxd.dto.external.DocDetailData;
import com.ccb.techfin.model.sxd.dto.external.DocInfo;
import com.ccb.techfin.model.sxd.dto.external.ExternalResponse;
import com.ccb.techfin.model.sxd.dto.request.ConfirmControllerRequest;
import com.ccb.techfin.model.sxd.dto.request.SubmitMaterialsRequest;
import com.ccb.techfin.model.sxd.dto.response.ExtractStatusResponse;
import com.ccb.techfin.model.sxd.entity.SxdAtt;
import com.ccb.techfin.model.sxd.entity.SxdRecord;
import com.ccb.techfin.model.sxd.entity.DocEntry;
import com.ccb.techfin.service.sxd.SxdService;
import com.ccb.techfin.service.sxd.config.ApiProperties;
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
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

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
    private final RestTemplate restTemplate;


    @Override
    @Transactional(rollbackFor = Exception.class)
    public String uploadFile(MultipartFile file) {
        fileValidator.validate(java.util.Collections.singletonList(file));
        String token = apiProperties.getDefaultToken();
        String attId = uploadAttachment(file, token);

        SxdAtt record = new SxdAtt();
        record.setAttId(attId);
        record.setFileName(file.getOriginalFilename());
        record.setFileSize(file.getSize());
        attachmentMapper.insert(record);

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
        String token = apiProperties.getDefaultToken();

        // 创建申请记录（以 taskId 为主键）
        SxdRecord record = new SxdRecord();
        record.setTaskId(batchTaskId);
        record.setCreditCode(request.getCreditCode());
        record.setCstId(request.getCstId());
        sxdMapper.insert(record);

        try {
            // 构建批量新增参数（从 sxd_att 查文件名/大小，docTypeId 从 financeFiles/businessFile 分类确定）
            List<DocBatchAddItem> batchItems = buildBatchAddItems(allItems);
            ExternalResponse batchResponse = batchAddDocs(batchItems, token);

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

    private String uploadAttachment(MultipartFile file, String token) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            if (StringUtils.hasText(token)) {
                UrlSecurityUtils.assertNoCrlf(token, "INVALID_TOKEN", "token 含非法控制字符");
                headers.set("c1-token", token);
            }
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            ByteArrayResource fileResource = new ByteArrayResource(file.getBytes()) {
                @Override
                public String getFilename() {
                    return sanitizeFileName(file.getOriginalFilename());
                }
            };
            body.add("file", fileResource);
            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);
            ResponseEntity<ExternalResponse> response = restTemplate.exchange(
                    apiProperties.getAttachmentUploadUrl(),
                    HttpMethod.POST,
                    requestEntity,
                    ExternalResponse.class);
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
        Long projectId = apiProperties.getProjectId();
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
                    .projectId(projectId)
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

    private ExternalResponse batchAddDocs(List<DocBatchAddItem> items, String token) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (StringUtils.hasText(token)) {
                UrlSecurityUtils.assertNoCrlf(token, "INVALID_TOKEN", "token 含非法控制字符");
                headers.set("c1-token", token);
            }
            HttpEntity<List<DocBatchAddItem>> requestEntity = new HttpEntity<>(items, headers);
            ResponseEntity<ExternalResponse> response = restTemplate.exchange(
                    apiProperties.getDocBatchAddUrl(),
                    HttpMethod.POST,
                    requestEntity,
                    ExternalResponse.class);
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

        record.setActCntlrNm(request.getActCntlrNm());
        sxdMapper.updateById(record);

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

        String token = apiProperties.getDefaultToken();
        String detailUrlBase = apiProperties.getDocDetailUrl();
        List<String> pendingDocNames = new ArrayList<>();

        for (DocEntry entry : docEntries) {
            DocDetailData detail = getDocDetail(detailUrlBase + "/" + entry.getDocId(), token);
            String state = detail.getExtractState();
            // U=待执行, I=执行中 — 视为未完成
            if ("U".equals(state) || "I".equals(state)) {
                pendingDocNames.add(detail.getDocName());
            }
        }

        boolean completed = pendingDocNames.isEmpty();
        return new ExtractStatusResponse(completed, pendingDocNames);
    }

    private DocDetailData getDocDetail(String url, String token) {
        UrlSecurityUtils.assertNoCrlf(url);
        try {
            HttpHeaders headers = new HttpHeaders();
            if (StringUtils.hasText(token)) {
                UrlSecurityUtils.assertNoCrlf(token, "INVALID_TOKEN", "token 含非法控制字符");
                headers.set("c1-token", token);
            }
            HttpEntity<Void> requestEntity = new HttpEntity<>(headers);
            ResponseEntity<ExternalResponse> response = restTemplate.exchange(
                    url, HttpMethod.GET, requestEntity, ExternalResponse.class);
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
