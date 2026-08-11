package cn.ripplecraft.netherway.core;

/**
 * 玩家会话的二元组：玩家名 / UUID，由平台层从游戏会话取出纯字符串后传入
 * （core 不碰 Minecraft 类型）。凭证预取用它向服务端自报身份，服务端据此
 * 签发绑定该玩家的每玩家令牌——不做身份验证，真实性由 MC 服务端保证。
 */
public final class SessionIdentity {

    private final String username;
    private final String uuid;

    private SessionIdentity(String username, String uuid) {
        this.username = username == null ? "" : username;
        this.uuid = uuid == null ? "" : uuid;
    }

    public static SessionIdentity of(String username, String uuid) {
        return new SessionIdentity(username, uuid);
    }

    public String username() {
        return username;
    }

    /** 玩家 UUID，带不带连字符都可能（prefetch 侧会归一化）。 */
    public String uuid() {
        return uuid;
    }

    @Override
    public String toString() {
        return "SessionIdentity{" + username + "/" + uuid + "}";
    }
}
