package cn.ripplecraft.netherway.core;

import java.nio.file.Path;

/**
 * 客户端侧唯一与 Minecraft 打交道的接口。
 *
 * <p>core 里其余代码都不碰任何游戏类，跨版本、跨加载器时只需为
 * Forge 1.7.10 / Forge 1.12.2 / Fabric 1.16.5 各写一份实现，
 * 每份大约一两百行，其余逻辑原样复用。
 *
 * <p>之所以能这么薄，是因为凭证收发走的是 Minecraft 原生的自定义频道机制，
 * 而编解码在 {@link Credentials} 里用裸字节实现，与加载器的网络 API 无关。
 */
public interface ClientBridge {

    /**
     * 把任务派发到游戏主线程执行。
     *
     * <p>agent 的状态事件到达时在后台线程，而切换连接必须在主线程做——
     * 在别的线程动网络管理器会引发难以复现的崩溃。
     */
    void runOnGameThread(Runnable task);

    /**
     * 断开当前连接并连到新地址。
     *
     * <p>TCP 连接无法迁移，所谓「重定向」实际就是断开重连；
     * 1.20.5+ 有原生的 Transfer 数据包，那些版本的实现会更简单。
     */
    void connectTo(String host, int port);

    /** 给玩家的提示，例如「已切换到直连」。实现可选择聊天栏或 HUD。 */
    void notifyPlayer(String message);

    /**
     * 把一小块数据经凭证同一条自定义频道回传服务端（升级结果回执用）。
     *
     * <p>可能在任意线程被调用；当前没有连接时安静地丢弃即可——回执是
     * 尽力而为的诊断信息，绝不能因为它影响升级流程本身。
     */
    void sendToServer(byte[] payload);

    /** 存放 agent 二进制的目录，通常是游戏目录下的某个子目录。 */
    Path cacheDirectory();

    void info(String message);

    void warn(String message, Throwable error);

    /**
     * 细粒度的过程日志：agent 的每个事件、stderr 内容、启动命令行等。
     * 量大但排查问题时全靠它们；平台实现按自己的配置决定落到哪个级别。
     */
    void debug(String message);
}
