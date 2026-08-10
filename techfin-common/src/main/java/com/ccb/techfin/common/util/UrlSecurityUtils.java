package com.ccb.techfin.common.util;

import com.ccb.techfin.common.exception.BusinessException;

import java.util.Locale;

/**
 * HTTP URL 安全校验工具类。
 * <p>
 * 防止 HTTP 响应截断/拆分（CRLF 注入）：外部系统返回的 ID 等不可信数据
 * 会被拼入对外请求 URL，若含 CR/LF 控制字符（含百分号编码 %0d/%0a），
 * 攻击者可注入伪造响应头、拆分 HTTP 响应，造成缓存污染、反射型 XSS 等风险。
 * 所有拼接不可信数据后发起的 HTTP 请求，调用前必须经 {@link #assertNoCrlf(String)} 校验。
 * </p>
 *
 * @author qiuhaoquan
 * @since 2026-08-10
 */
public final class UrlSecurityUtils {

    private UrlSecurityUtils() {
    }

    /**
     * 校验 URL 不含 CR/LF 控制字符（含百分号编码 %0d/%0a，大小写不敏感）。
     * 含非法字符时抛出 {@link BusinessException}（code=INVALID_URL）。
     *
     * @param url 待校验的完整请求 URL
     */
    public static void assertNoCrlf(String url) {
        assertNoCrlf(url, "INVALID_URL", "URL 含非法控制字符，已拒绝请求");
    }

    /**
     * 校验任意请求字段不含 CR/LF 控制字符（含百分号编码 %0d/%0a，大小写不敏感）。
     * 用户可控的 ID、日期等字段会被拼入对外 HTTP 请求（URL/头/体），
     * 含控制字符时可造成请求头部注入或响应截断，必须在边界拒绝。
     *
     * @param value   待校验的字段值（null/空串视为合法，不抛异常）
     * @param code    异常业务码
     * @param message 异常提示信息
     */
    public static void assertNoCrlf(String value, String code, String message) {
        if (value != null && !value.isEmpty()
                && (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0
                    || value.toLowerCase(Locale.ROOT).contains("%0d")
                    || value.toLowerCase(Locale.ROOT).contains("%0a"))) {
            throw new BusinessException(code, message);
        }
    }
}
