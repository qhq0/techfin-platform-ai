package com.ccb.techfin.common.util;

import javax.crypto.Cipher;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * RSA 加解密工具类。
 * <p>
 * 用于解密前端请求头 Authorization: Bearer &lt;encrypted-token&gt;，
 * 以及在响应头 X-Auth-Token 中返回刷新后的 token。
 * </p>
 * <ul>
 *   <li>解密：使用 RSA 私钥</li>
 *   <li>加密（刷新 token）：使用 RSA 公钥</li>
 * </ul>
 * 密钥在 application.properties 中配置（PEM 格式，含头尾）。
 *
 * @author qiuhaoquan
 * @since 2026-07-26
 */
public final class RsaUtils {

    private static PrivateKey privateKey;
    private static PublicKey publicKey;
    private static boolean initialized;

    private RsaUtils() {
    }

    /**
     * 仅用私钥初始化（仅解密，不刷新 token）。
     */
    public static void init(String privateKeyPem) throws Exception {
        privateKey = buildPrivateKey(privateKeyPem);
        initialized = true;
    }

    /**
     * 同时配置私钥和公钥（解密 + 加密刷新 token）。
     */
    public static void init(String privateKeyPem, String publicKeyPem) throws Exception {
        privateKey = buildPrivateKey(privateKeyPem);
        if (publicKeyPem != null && !publicKeyPem.isBlank()) {
            publicKey = buildPublicKey(publicKeyPem);
        }
        initialized = true;
    }

    /**
     * 解密数据（使用 RSA 私钥）。
     *
     * @param encryptedData Base64 编码的 RSA 密文
     * @return 解密后的明文字符串
     */
    public static String decrypt(String encryptedData) throws Exception {
        checkInitialized("privateKey");
        Cipher cipher = Cipher.getInstance("RSA");
        cipher.init(Cipher.DECRYPT_MODE, privateKey);
        byte[] decryptedBytes = cipher.doFinal(Base64.getDecoder().decode(encryptedData));
        return new String(decryptedBytes);
    }

    /**
     * 加密数据（使用 RSA 公钥），用于重新签发 token。
     *
     * @param plainData 明文字符串
     * @return Base64 编码的 RSA 密文
     */
    public static String encrypt(String plainData) throws Exception {
        if (publicKey == null) {
            throw new IllegalStateException("RsaUtils 公钥未配置，无法加密");
        }
        Cipher cipher = Cipher.getInstance("RSA");
        cipher.init(Cipher.ENCRYPT_MODE, publicKey);
        byte[] encryptedBytes = cipher.doFinal(plainData.getBytes());
        return Base64.getEncoder().encodeToString(encryptedBytes);
    }

    // ==================== 内部方法 ====================

    private static void checkInitialized(String key) {
        if (!initialized) {
            throw new IllegalStateException("RsaUtils 未初始化，请先调用 init()");
        }
    }

    private static PrivateKey buildPrivateKey(String pem) throws Exception {
        String content = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] keyBytes = Base64.getDecoder().decode(content);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
        return KeyFactory.getInstance("RSA").generatePrivate(spec);
    }

    private static PublicKey buildPublicKey(String pem) throws Exception {
        String content = pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] keyBytes = Base64.getDecoder().decode(content);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
        return KeyFactory.getInstance("RSA").generatePublic(spec);
    }
}
