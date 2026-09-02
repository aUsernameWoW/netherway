package cn.ripplecraft.netherway.modern;

import cn.ripplecraft.netherway.core.BinaryStore;
import cn.ripplecraft.netherway.core.Credentials;
import cn.ripplecraft.netherway.core.L10n;
import cn.ripplecraft.netherway.core.Platform;
import cn.ripplecraft.netherway.core.ServeCommand;
import cn.ripplecraft.netherway.core.telemetry.QualityObserver;
import cn.ripplecraft.netherway.core.telemetry.QualitySummary;
import cn.ripplecraft.netherway.core.telemetry.ServeTelemetry;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 服务端内置的 serve 进程：随 MC 服务端启动，把本地 Minecraft 端口作为房间发布出去。
 *
 * <p>参数与下发给客户端的凭证同源（都来自 {@code server.params}），
 * 从根上杜绝「凭证是 test 房间、宿主机却发布着别的房间」这类漂移；
 * 生命周期也跟着服务端走，不再需要 systemd/screen 单独伺候一个进程。
 *
 * <p>No automatic restart: goncp2p.Serve retries broker probing and
 * wait/punch cycles internally and only returns on cancellation, so an exit
 * is a configuration error, never a transient network failure worth
 * restarting over (a restart would just loop on the same error).
 */
public final class ServerAgentHost {

    private static final Logger LOG = LogManager.getLogger(NetherwayModern.MODID);
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final ModConfig config;
    private final ServeTelemetry telemetry;
    private final AtomicBoolean stopping = new AtomicBoolean(false);

    private volatile Process process;
    private Thread shutdownHook;

    public ServerAgentHost(ModConfig config, QualityObserver quality) {
        this.config = config;
        this.telemetry = new ServeTelemetry(quality,
                QualitySummary.Backend.fromBackendId(config.serverBackendId()));
    }

    /**
     * 启动 serve。失败只记日志——直连是增强功能，绝不能拖垮服务端启动。
     *
     * @param rendezvousPort loopback port of the embedded rendezvous (the MQTT signaling broker); 0 = off, use the brokers named in server.params.
     *                       非零时必须与 {@link ConnectionSniffer} 收到的是同一个数。
     */
    public void start(Path cacheDir, int localPort, int rendezvousPort) {
        if (process != null) {
            return;
        }
        telemetry.onStartAttempt();
        if (!ServeCommand.supportsBackend(config.serverBackendId())) {
            LOG.warn(L10n.tr("serve.backendUnsupported", config.serverBackendId()));
            telemetry.onStartFailure(QualitySummary.FailureStage.START,
                    QualitySummary.FailureCode.BACKEND_UNKNOWN);
            return;
        }
        Platform platform;
        try {
            platform = Platform.detect();
        } catch (Platform.UnsupportedPlatformException e) {
            LOG.warn(L10n.tr("serve.noBinary", e.getMessage()));
            telemetry.onStartFailure(QualitySummary.FailureStage.PLATFORM,
                    QualitySummary.FailureCode.PLATFORM_UNSUPPORTED);
            return;
        }
        Path exe;
        try {
            exe = new BinaryStore(cacheDir, platform).ensureExtracted();
        } catch (IOException e) {
            LOG.warn(L10n.tr("serve.extractFailed"), e);
            telemetry.onStartFailure(QualitySummary.FailureStage.EXTRACT,
                    QualitySummary.FailureCode.BINARY_EXTRACT_FAILED);
            return;
        }
        try {
            List<String> cmd = ServeCommand.build(exe, config.serverBackendId(), config.serverParams(), localPort,
                    new ServeCommand.Options()
                            .proxyProtocol(config.serveProxyProtocol())
                            .rendezvousPort(rendezvousPort));
            LOG.info(L10n.tr("serve.starting", platform, ServeCommand.describe(cmd)));

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(cacheDir.toFile());
            // serve 没有 stdout 上的 JSON 契约，合并两个流一起转进服务端日志
            pb.redirectErrorStream(true);
            cn.ripplecraft.netherway.core.AgentProcess.applyLanguage(pb);
            final Process proc = pb.start();
            process = proc;

            Thread pump = new Thread(new Runnable() {
                @Override
                public void run() {
                    pumpOutput(proc);
                }
            }, "netherway-serve-log");
            pump.setDaemon(true);
            pump.start();

            shutdownHook = new Thread(new Runnable() {
                @Override
                public void run() {
                    // A JVM-initiated destroy is an expected stop. Mark it
                    // before killing, or the log pump races us and reports a
                    // scary "serve exited (configuration error?)" warning.
                    // Mod servers never see this (their stop() runs in the
                    // server-stopping event, before JVM hooks), but on Bukkit
                    // a SIGTERM runs this hook in parallel with onDisable.
                    stopping.set(true);
                    proc.destroy();
                }
            }, "netherway-serve-shutdown");
            try {
                Runtime.getRuntime().addShutdownHook(shutdownHook);
            } catch (IllegalStateException alreadyShuttingDown) {
                // JVM 正在退出，无需再注册
            }
        } catch (IOException e) {
            LOG.warn(L10n.tr("serve.startFailed"), e);
            telemetry.onStartFailure(QualitySummary.FailureStage.START,
                    QualitySummary.FailureCode.AGENT_START_FAILED);
        }
    }

    /**
     * Forwards serve output line by line into the server log. Lines carrying the
     * language-independent {@code [serve-warn]} marker (the only serve output
     * contract besides {@code [serve-ready]}) stay prominent at WARN; everything
     * else, localised text included, is INFO.
     */
    private void pumpOutput(Process proc) {
        BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), UTF8));
        try {
            String line;
            while ((line = r.readLine()) != null) {
                String t = line.trim();
                if (t.isEmpty()) {
                    continue;
                }
                telemetry.onLogLine(t);
                if (t.startsWith(ServeTelemetry.GONC_WARN_MARKER)) {
                    LOG.warn("[serve] {}", t);
                } else {
                    LOG.info("[serve] {}", t);
                }
            }
        } catch (IOException ignored) {
            // 进程结束导致管道关闭，属正常路径
        }
        telemetry.onExit(stopping.get());
        if (!stopping.get()) {
            int code = exitCodeOf(proc);
            LOG.warn(L10n.tr("serve.exited", code));
        }
    }

    private static int exitCodeOf(Process proc) {
        try {
            return proc.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    /** 服务端关闭时调用：先请求优雅退出（撤下发布的房间），超时强杀。 */
    public void stop() {
        Process proc = process;
        process = null;
        if (proc == null) {
            return;
        }
        stopping.set(true);
        if (shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // JVM 已在退出流程中
            }
        }
        proc.destroy();
        try {
            if (!proc.waitFor(3, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
            }
        } catch (InterruptedException e) {
            proc.destroyForcibly();
            Thread.currentThread().interrupt();
        }
        LOG.info(L10n.tr("serve.stopped"));
    }
}
