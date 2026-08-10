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

    /**
     * HTTP 头值安全字符集：字母数字及 URL-safe / base64 常用符号
     * （JWT、UUID、base64 等不透明 token 均在此范围内）。
     */
    private static final String SAFE_HEADER_CHARS = "A-Za-z0-9\\-_.~+/=";

    /**
     * 白名单净化：移除值中所有非安全字符（含 CR/LF/NUL 等控制字符）。
     * 返回值是净化后的值，须在写入 HTTP 头时使用净化后的结果。
     * 对合法 token（字母数字/base64/JWT/UUID）为无副作用操作。
     *
     * @param value 原始值（可能为 null）
     * @return 仅含安全字符的值；null 原样返回
     */
    public static String sanitizeHeaderValue(String value) {
        if (value == null) {
            return null;
        }
        return value.replaceAll("[^" + SAFE_HEADER_CHARS + "]", "");
    }

    /**
     * 白名单校验：值是否完全由安全字符组成（等价于净化后无任何丢失）。
     * 用于配置加载等场景，可对不含法的配置快速失败。
     *
     * @param value 待校验的值（null 视为合法）
     * @return true 表示仅含安全字符
     */
    public static boolean isSafeHeaderValue(String value) {
        if (value == null) {
            return true;
        }
        return value.equals(sanitizeHeaderValue(value));
    }
}
