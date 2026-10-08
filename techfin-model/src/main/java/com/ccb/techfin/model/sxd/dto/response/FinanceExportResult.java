package com.ccb.techfin.model.sxd.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 财务报表导出结果。
 *
 * <p>{@code content} 是 zip 字节；{@code failures} 是没能打包进 zip 的文档说明
 * （同一份清单也会写进 zip 内的「导出失败清单.txt」，打开压缩包的人能直接看到）。
 * 调用方据此判断"这次导出是不是完整的"，而不是只看"有没有报错"。
 *
 * @author qiuhaoquan
 * @since 2026-07-23
 */
@Data
@AllArgsConstructor
public class FinanceExportResult {

    /** zip 压缩包字节 */
    private final byte[] content;

    /** 未能导出的文档（空列表表示全部成功） */
    private final List<String> failures;
}
