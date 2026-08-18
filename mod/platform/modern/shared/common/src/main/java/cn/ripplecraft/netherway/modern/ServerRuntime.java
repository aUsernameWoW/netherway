package cn.ripplecraft.netherway.modern;

import cn.ripplecraft.netherway.core.Credentials;
import cn.ripplecraft.netherway.core.L10n;
import cn.ripplecraft.netherway.core.PreauthService;
import cn.ripplecraft.netherway.core.TokenIssuer;
import cn.ripplecraft.netherway.core.telemetry.TelemetryCollector;
import cn.ripplecraft.netherway.core.telemetry.TelemetryEnvironment;
import cn.ripplecraft.netherway.core.telemetry.TelemetryFlusher;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 服务端接线，Forge/Fabric 共用。相当于 forge-1.7.10 Netherway 里
 * serverStarted/serverStopping 两段：挑会合点端口、装嗅探器 Context、
 * 起内置 serve。入口在 ServerStarted 事件里提供 MC 端口与运行目录。
 *
 * <p>嗅探 handler 的真正挂载由共享 Mixin（ServerConnectionInitMixin）对每条
 * accept 连接完成，本类只负责 {@link SnifferCore#install} 组装运行期 Context。
 */
public final class ServerRuntime {

    private static final Logger LOG = LogManager.getLogger(NetherwayModern.MODID);

    private final ModConfig config;
    private final TelemetryWiring telemetryWiring;
    private final SnifferCore.AddressRewriter addressRewriter;
    private ServerAgentHost agent;

    /**
     * @param addressRewriter platform hook for the PROXY-protocol address
     *                        write-back (see {@link SnifferCore.AddressRewriter});
     *                        mod entries pass {@link ConnectionAddressRewriter}
     */
    public ServerRuntime(ModConfig config, TelemetryWiring telemetryWiring,
                         SnifferCore.AddressRewriter addressRewriter) {
        this.config = config;
        this.telemetryWiring = telemetryWiring;
        this.addressRewriter = addressRewriter;
    }

    /**
     * 服务器就绪后调用。
     *
     * @param mcPort MC 实际监听端口（config.localPort 为 0 时的回退值）
     */
    public void onServerStarted(int mcPort) {
        if (!config.serverEnabled()) {
            return;
        }
        // agent 工作目录 = 运行目录/netherway。MC 服务端的 CWD 就是运行目录，
        // 与 1.7.10 的 server.getFile("netherway") 等价，且避免逐版本的
        // MinecraftServer 目录 API 差异。
        Path workDir = java.nio.file.Paths.get("netherway").toAbsolutePath();
        logServerConfig();

        PreauthHost host = config.serverPreauth() ? new PreauthHost(config) : null;
        if (host != null) {
            LOG.info(L10n.tr("fserver.preauthOn"));
        }
        int rendezvousPort = resolveRendezvousPort();
        SnifferCore.install(host == null ? null : new PreauthService(host),
                !config.serveProxyProtocol().isEmpty(), rendezvousPort, addressRewriter);

        if (!config.serverRunAgent()) {
            return;
        }
        int port = config.serverLocalPort() > 0 ? config.serverLocalPort() : mcPort;
        if (port <= 0) {
            LOG.warn(L10n.tr("fserver.noPort"));
            return;
        }
        TelemetryCollector telemetry = telemetryWiring.collector(config,
                TelemetryEnvironment.Role.DEDICATED_SERVER);
        TelemetryFlusher.start(telemetry, 60L);
        agent = new ServerAgentHost(config, telemetry);
        agent.start(workDir, port, rendezvousPort);
    }

    public void onServerStopping() {
        SnifferCore.shutdown();
        if (agent != null) {
            agent.stop();
            agent = null;
        }
    }

    /**
     * 挑一个空闲的回环端口给内嵌会合点。返回 0 表示不启用。语义同
     * forge-1.7.10 的 resolveRendezvousPort：让系统分配、挑完即释放，
     * 随后由 agent 绑上。
     */
    private int resolveRendezvousPort() {
        if (!config.serverRendezvous()) {
            return 0;
        }
        try {
            ServerSocket probe = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            int port;
            try {
                port = probe.getLocalPort();
            } finally {
                probe.close();
            }
            LOG.info(L10n.tr("fserver.rendezvousPort", port));
            return port;
        } catch (IOException e) {
            LOG.warn(L10n.tr("fserver.rendezvousPortFailed"), e);
            return 0;
        }
    }

    private void logServerConfig() {
        Credentials cred = config.serverCredentials();
        if (cred == null) {
            LOG.warn(L10n.tr("fserver.incompleteCred"));
            return;
        }
        LOG.info(L10n.tr("fserver.enabled", cred));
        if (!config.tokenSigningKey().isEmpty()) {
            LOG.info(L10n.tr("fserver.tokenIssuing", config.tokenTtlDays(),
                    TokenIssuer.keyFingerprint(config.tokenSigningKey())));
        }
        if (config.serverRunAgent()) {
            LOG.info(L10n.tr("fserver.willRunServe", cred.room()));
        } else {
            LOG.info(L10n.tr("fserver.noRunAgent", cred.room()));
        }
    }
}
