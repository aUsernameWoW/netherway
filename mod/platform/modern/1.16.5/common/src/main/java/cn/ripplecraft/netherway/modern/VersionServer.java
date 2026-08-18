package cn.ripplecraft.netherway.modern;

import net.minecraft.server.MinecraftServer;

/** 服务端逐版差异（1.16.5）。目前只有取端口一处。 */
public final class VersionServer {

    private VersionServer() {
    }

    /**
     * MC 实际监听端口。1.16.5 mojmap 是 {@code getServerPort()}
     * （getPort() 是 1.17+ mojmap 才有的名字）。
     */
    public static int mcPort(MinecraftServer server) {
        return server.getServerPort();
    }
}
