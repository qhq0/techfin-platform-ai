package com.ccb.techfin.service.sxd.util;

import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Cipher;
import java.io.IOException;
import java.io.InputStream;
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
import java.util.Properties;

/**
 * 生成前端鉴权 token 的测试工具（main 方法运行）。
 * <p>
 * 从 classpath 的 {@code application.properties} 读取 {@code rsa.public-key} 公钥，
 * 加密 JSON 载荷：
 * <pre>{@code
 * {"uass":"zhangsan.sz","exp":<当前系统时间戳(毫秒)>}
 * }</pre>
 * 载荷格式、键顺序、加密方式（RSA 公钥 + PKCS1v1.5 填充，与 Java {@code Cipher.getInstance("RSA")} 默认一致）
 * 均与 {@code TokenInterceptor} 的解密/刷新逻辑保持一致，生成的 token 可直接用于测试。
 * 生成的 token 写入 {@code docs/token.txt}。
 *
 * <h3>运行方式</h3>
 * 在 IDE 中直接运行此 main 方法即可（classpath 需包含 {@code techfin-controller} 模块的
 * {@code application.properties}）。也可命令行运行：
 * <pre>{@code
 * mvn -q -pl techfin-service -am compile exec:java \
 *   -Dexec.mainClass=com.ccb.techfin.service.sxd.util.TokenGenerator \
 *   -Dexec.classpathScope=runtime
 * }</pre>
 *
 * <p>本工具为自包含实现，不修改任何公共类。
 *
 * @author qiuhaoquan
 * @since 2026-07-28
 */
public class TokenGenerator {

    /** application.properties 在 classpath 中的路径 */
    private static final String PROPERTIES_RESOURCE = "/application.properties";

    /** 默认输出路径 */
    private static final String DEFAULT_OUTPUT_PATH = "docs/token.txt";

    /** token 载荷中的 uass（对应 msp_user.account） */
    private static final String UASS = "zhangsan.sz";

    public static void main(String[] args) throws Exception {
        String outputPath = args.length > 0 ? args[0] : DEFAULT_OUTPUT_PATH;

        // 1. 读取 RSA 公钥
        String publicKey = loadPublicKey();

        // 2. 构造载荷（键顺序与 TokenInterceptor 刷新逻辑一致：uass -> exp）
        ObjectMapper objectMapper = new ObjectMapper();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("uass", UASS);
        payload.put("exp", Instant.now().toEpochMilli());
        String json = objectMapper.writeValueAsString(payload);

        // 3. 公钥加密（PKCS1v1.5，与 TokenInterceptor 使用的默认填充一致）
        String token = encrypt(publicKey, json);

        // 4. 写入文件
        writeToken(token, outputPath);

        System.out.println("Token generated (uass=" + UASS + "):");
        System.out.println(token);
        System.out.println("Saved to: " + outputPath);
    }

    /**
     * 从 classpath 的 application.properties 读取 rsa.public-key。
     */
    private static String loadPublicKey() throws IOException {
        Properties props = new Properties();
        try (InputStream in = TokenGenerator.class.getResourceAsStream(PROPERTIES_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("classpath 下未找到 application.properties：" + PROPERTIES_RESOURCE);
            }
            props.load(in);
        }
        String publicKey = props.getProperty("rsa.public-key");
        if (publicKey == null || publicKey.isBlank()) {
            throw new IllegalStateException("application.properties 中未配置 rsa.public-key");
        }
        return publicKey;
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
