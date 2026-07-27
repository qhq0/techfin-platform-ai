package com.ccb.techfin.controller.sxd.interceptor;

import com.ccb.techfin.common.util.RsaUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Token 拦截器：从请求头 Authorization: Bearer &lt;encrypted-token&gt; 中提取并解密 token。
 * <p>
 * token 由其他后端签发，前端携带。后端使用 RSA 私钥解密并校验 2 小时有效期，
 * 校验通过后用公钥重新签发新 token（刷新 exp），通过响应头 {@code X-Auth-Token} 返回。
 * </p>
 *
 * <h3>Token 载荷格式</h3>
 * <pre>{@code
 * {
 *   "staffCode": "员工编号",
 *   "exp": 1721980800000    // 当前时间戳（毫秒）
 * }
 * }</pre>
 *
 * <h3>校验流程</h3>
 * <ol>
 *   <li>RSA 私钥解密 → 得到 JSON</li>
 *   <li>解析 staffCode 和 exp</li>
 *   <li>校验 {@code now - exp ≤ 2 小时}</li>
 *   <li>存入 request attribute "staffCode"，供业务层使用</li>
 *   <li>用 RSA 公钥重新加密 {@code {staffCode, exp: now}}，返回在响应头 X-Auth-Token</li>
 * </ol>
 *
 * @author qiuhaoquan
 * @since 2026-07-26
 */
@Slf4j
@Component
public class TokenInterceptor implements HandlerInterceptor {

    /** Token 有效期：2 小时（毫秒） */
    private static final long TOKEN_VALIDITY_MS = 2 * 60 * 60 * 1000L;

    private final ObjectMapper objectMapper;

    public TokenInterceptor(ObjectMapper objectMapper,
                            @Value("${rsa.private-key}") String rsaPrivateKey,
                            @Value("${rsa.public-key}") String rsaPublicKey) {
        this.objectMapper = objectMapper;
        try {
            RsaUtils.init(rsaPrivateKey, rsaPublicKey);
            log.info("RSA 密钥初始化完成（私钥解密 + 公钥加密）");
        } catch (Exception e) {
            log.error("RSA 密钥初始化失败，请检查 rsa.private-key / rsa.public-key 配置", e);
            throw new RuntimeException("RSA 密钥初始化失败", e);
        }
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        String authHeader = request.getHeader("Authorization");
        if (!StringUtils.hasText(authHeader)) {
            log.warn("缺少 Authorization 请求头: {}", request.getRequestURI());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }

        String token;
        if (authHeader.startsWith("Bearer ")) {
            token = authHeader.substring(7).trim();
        } else {
            token = authHeader.trim();
        }

        if (!StringUtils.hasText(token)) {
            log.warn("Authorization 头中 token 为空");
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }

        try {
            // 1. RSA 私钥解密
            String json = RsaUtils.decrypt(token);

            // 2. 解析 JSON 载荷 {staffCode, exp}
            JsonNode payload = objectMapper.readTree(json);
            JsonNode staffCodeNode = payload.get("staffCode");
            JsonNode expNode = payload.get("exp");
            if (staffCodeNode == null || expNode == null) {
                log.warn("Token 载荷缺少 staffCode 或 exp 字段");
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return false;
            }
            String staffCode = staffCodeNode.asText();
            long exp = expNode.asLong();

            // 3. 校验 2 小时有效期
            long now = System.currentTimeMillis();
            long elapsed = now - exp;
            if (elapsed > TOKEN_VALIDITY_MS) {
                log.warn("Token 已过期: staffCode={}, exp={}, now={}, elapsed={}ms",
                        staffCode, exp, now, elapsed);
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return false;
            }

            // 4. 存入 request 属性供业务层使用
            request.setAttribute("staffCode", staffCode);

            // 5. 刷新 token：用公钥重新加密 {staffCode, exp: now}，返回在响应头
            Map<String, Object> newPayload = new LinkedHashMap<>();
            newPayload.put("staffCode", staffCode);
            newPayload.put("exp", now);
            String newToken = RsaUtils.encrypt(objectMapper.writeValueAsString(newPayload));
            response.setHeader("X-Auth-Token", newToken);
            response.setHeader("Access-Control-Expose-Headers", "X-Auth-Token");

            if (log.isDebugEnabled()) {
                log.debug("Token 校验通过: staffCode={}, elapsed={}ms", staffCode, elapsed);
            }
        } catch (Exception e) {
            log.warn("Token 校验失败: {} - {}", request.getRequestURI(), e.getMessage());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }

        return true;
    }
}
