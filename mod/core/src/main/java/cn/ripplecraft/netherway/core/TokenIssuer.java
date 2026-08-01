package cn.ripplecraft.netherway.core;

import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 每玩家令牌的签发（服务端用）。
 *
 * <p>令牌格式与 Go 侧 {@code internal/authplugin} 的校验<b>逐字节一致</b>，
 * 改动必须两边同步——两侧各有一个用同一组常量的已知答案测试钉住这一点：
 *
 * <pre>{@code <过期秒级时间戳>.<hex(HMAC-SHA256(签发密钥, user + "\n" + 过期时间戳))>}</pre>
 *
 * <p>校验方无状态：过期时间就在令牌里，authplugin 只需持有同一把签发密钥。
 * 每次登录都重新签发（下发即续签），有效期只需覆盖玩家两次游玩的间隔；
 * 全局作废 = 换签发密钥。
 *
 * <p>只用 {@code javax.crypto} 的公共稳定 API，Java 8–25 全可用，零依赖。
 */
public final class TokenIssuer {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private TokenIssuer() {
    }

    /**
     * 签发绑定 user、到 expiryEpochSeconds 为止有效的令牌。
     *
     * @param user 身份标识，约定用玩家 UUID 字符串
     */
    public static String issue(String signingKey, String user, long expiryEpochSeconds) {
        if (signingKey == null || signingKey.isEmpty()) {
            throw new IllegalArgumentException("签发密钥不能为空");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey.getBytes(UTF8), "HmacSHA256"));
            byte[] sum = mac.doFinal((user + "\n" + expiryEpochSeconds).getBytes(UTF8));
            return expiryEpochSeconds + "." + hex(sum);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("运行环境缺少 HmacSHA256", e);
        }
    }

    /**
     * 签发密钥的短指纹（SHA-256 前 4 字节）。两侧启动日志都打印它，
     * 服主一眼就能核对「密钥是否配成同一把」而不暴露密钥本身。
     * 与 Go 侧 {@code authplugin.KeyFingerprint} 一致。
     */
    public static String keyFingerprint(String signingKey) {
        try {
            byte[] sum = MessageDigest.getInstance("SHA-256").digest(signingKey.getBytes(UTF8));
            byte[] head = new byte[4];
            System.arraycopy(sum, 0, head, 0, 4);
            return hex(head);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("运行环境缺少 SHA-256", e);
        }
    }

    private static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
