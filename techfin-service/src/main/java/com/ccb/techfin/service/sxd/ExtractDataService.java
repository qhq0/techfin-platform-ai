package com.ccb.techfin.service.sxd;

import com.ccb.techfin.model.sxd.dto.response.ExtractDataItem;
import com.ccb.techfin.model.sxd.dto.response.FinanceExportResult;

import java.util.List;

/**
 * 提取数据服务接口：要素提取、导出、报告生成。
 *
 * @author qiuhaoquan
 * @since 2026-07-23
 */
public interface ExtractDataService {

    /**
     * 查询商业计划书的提取数据。先从缓存表读取，缓存未命中时调用外部 API 并写入缓存。
     *
     * @param taskId 申请记录的任务 ID
     * @return 提取数据列表（按 tableName 分组）
     */
    List<ExtractDataItem> queryBusinessExtractData(String taskId);

    /**
     * 导出商业计划书的提取结果 xlsx 文件。
     *
     * @param taskId 申请记录的任务 ID
     * @return xlsx 文件字节数组
     */
    byte[] exportBusinessExtractData(String taskId);

    /**
     * 导出财务报表的提取结果 zip 压缩包。
     *
     * <p>单个文档失败不影响其余文档打包，但会体现在返回值里（{@code failures}），
     * 同时写进 zip 内的失败清单 —— 调用方不能只看"有没有抛异常"。
     *
     * @param taskId 申请记录的任务 ID
     * @return zip 字节 + 未能导出的文档说明
     */
    FinanceExportResult exportFinanceExtractData(String taskId);

    /**
     * 生成 Word 报告，包含企业基本信息、商业计划书提取文本、资产负债表关键科目和利润表关键科目。
     *
     * <p>报告中的「企业主营业务情况」由商业计划书的「主营业务和产品」与「经营情况介绍」两段
     * 提取文本经大模型二次总结编辑后合并而成；大模型不可用或返回空内容时该段落留空，
     * 不影响报告生成。
     *
     * @param taskId 申请记录的任务 ID
     * @param cstId  客户编号
     * @param userId 调用方账号，透传给大模型网关用于身份识别，可空
     * @return Word 文档（.docx）字节数组
     */
    byte[] generateReport(String taskId, String cstId, String userId);
}