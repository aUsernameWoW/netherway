package cn.ripplecraft.netherway.modern.client;

/**
 * 客户端把回执字节发回服务端的口子。Forge（EventNetworkChannel）与
 * Fabric（ClientPlayNetworking）各提供一份实现；调用点已保证在主线程且
 * 存在连接。
 */
public interface ClientNetwork {

    void sendToServer(byte[] payload);
}
