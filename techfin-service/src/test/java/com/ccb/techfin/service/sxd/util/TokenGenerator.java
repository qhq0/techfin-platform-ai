package com.ccb.techfin.service.sxd.util;

import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Cipher;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 生成前端鉴权 token 的测试工具（main 方法运行）。
 * <p>
 * 使用内置的 RSA 公钥加密 JSON 载荷：
 * <pre>{@code
 * {"userAccount":"<base64(zhangsan.sz)>","exp":<当前系统时间戳(毫秒)>}
 * }</pre>
 * userAccount 为统一身份认证账号的 Base64 编码。
 * 载荷格式、键顺序、加密方式（RSA 公钥 + PKCS1v1.5 填充，与 Java {@code Cipher.getInstance("RSA")} 默认一致）
 * 均与 {@code TokenInterceptor} 的解密/刷新逻辑保持一致，生成的 token 可直接用于测试。
 * 生成的 token 写入 {@code docs/token.txt}。
 *
 * <h3>运行方式</h3>
 * 在 IDE 中直接运行此 main 方法即可。也可命令行运行：
 * <pre>{@code
 * mvn -q -pl techfin-service -am compile exec:java \
 *   -Dexec.mainClass=com.ccb.techfin.service.sxd.util.TokenGenerator \
 *   -Dexec.classpathScope=runtime
 * }</pre>
 *
 * <p>本工具为自包含实现，公钥已内置，不依赖 application.properties，也不修改任何公共类。
 *
 * @author qiuhaoquan
 * @since 2026-07-28
 */
public class TokenGenerator {

    /** RSA 公钥（X.509 PEM，与 application.properties 中 rsa.public-key 保持一致） */
    private static final String PUBLIC_KEY =
            """
            -----BEGIN PUBLIC KEY-----
            MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA2Lr9Na7j5d/w2fiHaP1V
            qXBpoooWRdnWguSYtxtPH9Z8kKz6XOyu1/6IBM52BMIOxww9G9H1lSN5jZTFY7KH
            DZ0xUh5xYyxakiRtXe6JuitZLQvCHYzyPsMFcUT4GGCWkWATklBj5ZyKEiT/oEcJ
            l0O6f/xxvkfzqmXSfSirDoaCukYSSOop+7IkMcTLdsQKMM1M2AiwTvd+cZQZjdh7
            7dEgzAF2tuEFGGIKc969fqWprMnnoQa+yYgK7X10ea6sMKT3QMcNIisCHp4Evf1o
            heEEsljuuTugJLUp31R0ezBgH9fCCXo3kaYwJEZOdGe3kLn9CKXDDn8tLrKD7hdd
            OwIDAQAB
            -----END PUBLIC KEY-----
            """;

    /** 默认输出路径 */
    private static final String DEFAULT_OUTPUT_PATH = "docs/token.txt";

    /** 统一身份认证账号（对应 msp_user.account），生成 token 时需 Base64 编码后放入载荷 */
    private static final String USER_ACCOUNT = "zhangsan.sz";

    public static void main(String[] args) throws Exception {
        String outputPath = args.length > 0 ? args[0] : DEFAULT_OUTPUT_PATH;

        // 1. 读取 RSA 公钥
        String publicKey = getPublicKey();

        // 2. 构造载荷（键顺序与 TokenInterceptor 刷新逻辑一致：userAccount -> exp）
        ObjectMapper objectMapper = new ObjectMapper();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userAccount", Base64.getEncoder().encodeToString(USER_ACCOUNT.getBytes(StandardCharsets.UTF_8)));
        payload.put("exp", Instant.now().toEpochMilli());
        String json = objectMapper.writeValueAsString(payload);

        // 3. 公钥加密（PKCS1v1.5，与 TokenInterceptor 使用的默认填充一致）
        String token = encrypt(publicKey, json);

        // 4. 写入文件
        writeToken(token, outputPath);

        System.out.println("Token generated (userAccount=" + USER_ACCOUNT + ", base64=" + Base64.getEncoder().encodeToString(USER_ACCOUNT.getBytes(StandardCharsets.UTF_8)) + "):");
        System.out.println(token);
        System.out.println("Saved to: " + outputPath);
    }

    /**
     * 返回内置的 RSA 公钥。
     */
    private static String getPublicKey() {
        return PUBLIC_KEY;
    }

    /**
     * 用 RSA 公钥加密，PKCS1v1.5 填充，Base64 编码输出。
     * 与 {@code RsaUtils.encrypt} 行为一致。
     */
    private static String encrypt(String publicKeyPem, String plainData) throws Exception {
        String content = publicKeyPem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] keyBytes = Base64.getDecoder().decode(content);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
        PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(spec);

        Cipher cipher = Cipher.getInstance("RSA");
        cipher.init(Cipher.ENCRYPT_MODE, publicKey);
        byte[] encryptedBytes = cipher.doFinal(plainData.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(encryptedBytes);
    }

    /**
     * 把 token 写入文件。
     */
    private static void writeToken(String token, String outputPath) throws IOException {
        Path path = Paths.get(outputPath);
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.writeString(path, token, StandardCharsets.UTF_8);
    }
}
