package cn.ripplecraft.netherway.core;

/**
 * 玩家会话的三元组：玩家名 / UUID / accessToken，由平台层从游戏会话取出
 * 纯字符串后传入（core 不碰 Minecraft 类型）。凭证预取用它向皮肤站证明
 * 「这是一次真实登录」，与原版进服验证同款材料。
 *
 * <p>accessToken 等价于登录态本身：绝不进 {@link #toString()}，也绝不进
 * 子进程命令行（{@link Prefetcher} 经环境变量传递）。
 */
public final class SessionIdentity {

    private final String username;
    private final String uuid;
    private final String accessToken;

    private SessionIdentity(String username, String uuid, String accessToken) {
        this.username = username == null ? "" : username;
        this.uuid = uuid == null ? "" : uuid;
        this.accessToken = accessToken == null ? "" : accessToken;
    }

    public static SessionIdentity of(String username, String uuid, String accessToken) {
        return new SessionIdentity(username, uuid, accessToken);
    }

    /**
     * 会话形状是否像一次真实的正版登录。
     *
     * <p>离线/演示会话的占位值五花八门（1.7.10 的 Session 缺名字时会填
     * "NotValid"，离线启动器常传 "0" 或 "-"），这里只做形状粗筛：拿这种
     * 会话去皮肤站 join 必然失败，提前跳过省一次注定失败的网络往返。
     * 最终裁决永远在皮肤站的 join/hasJoined。
     */
    public boolean usable() {
        if (username.isEmpty() || accessToken.isEmpty()) {
            return false;
        }
        if ("0".equals(accessToken) || "-".equals(accessToken)
                || "NotValid".equals(accessToken)) {
            return false;
        }
        return isHex32(uuid.replace("-", ""));
    }

    public String username() {
        return username;
    }

    /** 玩家 UUID，带不带连字符都可能（prefetch 侧会归一化）。 */
    public String uuid() {
        return uuid;
    }

    public String accessToken() {
        return accessToken;
    }

    private static boolean isHex32(String s) {
        if (s.length() != 32) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return "SessionIdentity{" + username + "/" + uuid + "}";
    }
}
