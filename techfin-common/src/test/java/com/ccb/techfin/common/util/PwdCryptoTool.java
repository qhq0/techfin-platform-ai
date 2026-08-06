package com.ccb.techfin.common.util;

import com.alibaba.druid.filter.config.ConfigTools;

import java.util.Scanner;

/**
 * 数据库密码加密小工具（Druid config 过滤器配套）。
 *
 * <p>用途：更换 MySQL 密码时，为 application.properties 中的
 * {@code spring.datasource.password} 生成加密后的密文。</p>
 *
 * <p>密钥对策略：项目采用【长期固定密钥对】方案，私钥已保存于
 * {@code docs/druid-password-keys.txt}，公钥已写入配置
 * {@code spring.datasource.druid.connect-properties.config.decrypt.key}。
 * 换密码时只需用该私钥重新加密新密码，公钥无需改动。</p>
 *
 * <p>运行方式（交互式，运行后按提示输入新密码和私钥）：</p>
 * <pre>
 * mvn -pl techfin-common exec:java \
 *   -Dexec.mainClass=com.ccb.techfin.common.util.PwdCryptoTool
 * </pre>
 *
 * <p>得到新密文后，仅替换 {@code spring.datasource.password} 即可，
 * {@code decrypt.key}（公钥）保持不变。</p>
 *
 * @author qiuhaoquan
 * @since 2026-08-06
 */
public final class PwdCryptoTool {

    private PwdCryptoTool() {
    }

    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);

        System.out.print("请输入新数据库密码: ");
        String plainPwd = scanner.nextLine().trim();

        System.out.print("请输入私钥（从 docs/druid-password-keys.txt 复制 privateKey 后的内容粘贴）: ");
        String privateKey = scanner.nextLine().trim();

        if (plainPwd.isEmpty() || privateKey.isEmpty()) {
            System.err.println("密码和私钥均不能为空，已退出。");
            return;
        }

        String encrypted = ConfigTools.encrypt(privateKey, plainPwd);
        System.out.println("====== 新密码密文 ======");
        System.out.println(encrypted);
        System.out.println("---------------------------------------");
        System.out.println("请将上面密文填入配置：spring.datasource.password=<密文>");
        System.out.println("公钥保持不变，无需改动 decrypt.key。");
    }
}
