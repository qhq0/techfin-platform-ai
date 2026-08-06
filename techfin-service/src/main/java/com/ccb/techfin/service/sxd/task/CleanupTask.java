package com.ccb.techfin.service.sxd.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ccb.techfin.dao.sxd.AttachmentMapper;
import com.ccb.techfin.dao.sxd.DocEntryMapper;
import com.ccb.techfin.dao.sxd.ExtractDataMapper;
import com.ccb.techfin.dao.sxd.SxdMapper;
import com.ccb.techfin.model.sxd.entity.SxdAtt;
import com.ccb.techfin.model.sxd.entity.SxdRecord;
import com.ccb.techfin.model.sxd.entity.DocEntry;
import com.ccb.techfin.model.sxd.entity.ExtractData;
import com.ccb.techfin.service.sxd.config.ApiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 数据清理逻辑（不再由定时任务触发，可手工调用或接入外部调度）。
 * <ul>
 *   <li>清理 sxd_att 中创建超过 24 小时的孤立记录</li>
 *   <li>清理 sxd_doc 中关联状态为 UNFINISHED 的记录（含外部系统文件）</li>
 *   <li>清理 sxd_extract_data 中关联状态为 UNFINISHED 的记录</li>
 * </ul>
 *
 * @author qiuhaoquan
 * @since 2026-07-23
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CleanupTask {

    private final AttachmentMapper attachmentMapper;
    private final DocEntryMapper docEntryMapper;
    private final ExtractDataMapper extractDataMapper;
    private final SxdMapper sxdMapper;
    private final ApiProperties apiProperties;
    private final RestTemplate restTemplate;

    @Value("${sxd.cleanup.orphan-attachment-retention-hours}")
    private int retentionHours;

    /**
     * 清理 sxd_att 中创建时间超过 24 小时且未被引用的孤立附件记录。
     */
    private void cleanupOrphanAttachments() {
        LocalDateTime deadline = LocalDateTime.now().minusHours(retentionHours);
        List<SxdAtt> oldRecords = attachmentMapper.selectList(
                new LambdaQueryWrapper<SxdAtt>()
                        .isNotNull(SxdAtt::getCreatedAt)
                        .lt(SxdAtt::getCreatedAt, deadline));

        if (oldRecords.isEmpty()) {
            log.debug("No orphan attachments to clean up");
            return;
        }

        List<Long> ids = oldRecords.stream()
                .map(SxdAtt::getId)
                .collect(Collectors.toList());
        int deleted = attachmentMapper.delete(
                new LambdaQueryWrapper<SxdAtt>()
                        .in(SxdAtt::getId, ids));
        log.info("Cleaned up {} orphan attachment(s) older than {}h", deleted, retentionHours);
    }

    /**
     * 清理 sxd_doc 中关联任务状态为 UNFINISHED 的记录。
     * 先查找所有 UNFINISHED 的 task_id，删除外部系统中的文档，再删除本地记录。
     */
    private void cleanupUnfinishedDocEntries() {
        List<SxdRecord> unfinishedRecords = sxdMapper.selectList(
                new LambdaQueryWrapper<SxdRecord>()
                        .eq(SxdRecord::getStatus, "0"));

        if (unfinishedRecords.isEmpty()) {
            log.debug("No unfinished tasks to clean up doc entries");
            return;
        }

        List<String> taskIds = unfinishedRecords.stream()
                .map(SxdRecord::getTaskId)
                .collect(Collectors.toList());

        int deleted = 0;
        for (String taskId : taskIds) {
            List<DocEntry> entries = docEntryMapper.selectList(
                    new LambdaQueryWrapper<DocEntry>()
                            .eq(DocEntry::getTaskId, taskId));
            // 先删除外部系统中的文档
            for (DocEntry entry : entries) {
                try {
                    deleteExternalDoc(entry.getDocId());
                } catch (Exception e) {
                    log.warn("Failed to delete external doc {} for taskId={}: {}",
                            entry.getDocId(), taskId, e.getMessage());
                }
            }
            int count = docEntryMapper.delete(
                    new LambdaQueryWrapper<DocEntry>()
                            .eq(DocEntry::getTaskId, taskId));
            deleted += count;
            if (count > 0) {
                log.info("Cleaned up {} doc entry(ies) for unfinished taskId={}", count, taskId);
            }
        }
        log.info("Cleaned up total {} doc entry(ies) for unfinished tasks", deleted);
    }

    /**
     * 调用外部 API 删除单个文档。
     */
    private void deleteExternalDoc(String docId) {
        String url = apiProperties.getDocDeleteUrl() + "/" + docId;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String token = apiProperties.getDefaultToken();
        if (StringUtils.hasText(token)) {
            headers.set("c1-token", token);
        }
        HttpEntity<Void> requestEntity = new HttpEntity<>(headers);
        restTemplate.exchange(url, HttpMethod.POST, requestEntity, String.class);
        log.info("Deleted external doc: docId={}", docId);
    }

    /**
     * 清理 sxd_extract_data 中关联任务状态为 UNFINISHED 的记录。
     * 先查找所有 UNFINISHED 的 task_id，再批量删除对应的提取数据缓存记录。
     */
    private void cleanupUnfinishedExtractData() {
        List<SxdRecord> unfinishedRecords = sxdMapper.selectList(
                new LambdaQueryWrapper<SxdRecord>()
                        .eq(SxdRecord::getStatus, "0"));

        if (unfinishedRecords.isEmpty()) {
            log.debug("No unfinished tasks to clean up extract data");
            return;
        }

        List<String> taskIds = unfinishedRecords.stream()
                .map(SxdRecord::getTaskId)
                .collect(Collectors.toList());

        int deleted = 0;
        for (String taskId : taskIds) {
            int count = extractDataMapper.delete(
                    new LambdaQueryWrapper<ExtractData>()
                            .eq(ExtractData::getTaskId, taskId));
            deleted += count;
            if (count > 0) {
                log.info("Cleaned up {} extract data record(s) for unfinished taskId={}", count, taskId);
            }
        }
        log.info("Cleaned up total {} extract data record(s) for unfinished tasks", deleted);
    }
}
