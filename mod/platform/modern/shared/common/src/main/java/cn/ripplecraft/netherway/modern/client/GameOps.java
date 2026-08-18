package cn.ripplecraft.netherway.modern.client;

/**
 * modern 客户端桥里少数逐版本不同的操作。1.16.5/1.18.2/1.20.1 各提供一份
 * 实现（约几十行），其余状态机逻辑全部在 {@link ModernClientBridge} 共享。
 *
 * <p>只有两处真正分叉：程序化连接的入口（ConnectScreen 的构造器 / 4 参
 * startConnecting / 5 参 startConnecting，签名逐版不同）与聊天文本的构造
 * （{@code new TextComponent} vs {@code Component.literal}）。
 */
public interface GameOps {

    /**
     * 断开当前世界并连到指定地址。实现须先退出当前世界（clearLevel）再打开
     * 连接界面；调用方已在此之前存好 switchOrigin，故这里只管发起连接。
     */
    void connect(String host, int port);

    /** 在聊天栏给玩家一条提示（已带绿色前缀由调用方拼好，此处只负责显示）。 */
    void sendChat(String message);
}
