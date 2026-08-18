package cn.ripplecraft.netherway.modern;

import net.minecraft.server.MinecraftServer;

/** 服务端逐版差异（1.20.1）。目前只有取端口一处。 */
public final class VersionServer {

    private VersionServer() {
    }

    /** MC 实际监听端口。1.20.1 mojmap 是 {@code getPort()}。 */
    public static int mcPort(MinecraftServer server) {
        return server.getPort();
    }
}
