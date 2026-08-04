package cn.ripplecraft.netherway.forge;

import cn.ripplecraft.netherway.core.BinaryStore;
import cn.ripplecraft.netherway.core.Credentials;
import cn.ripplecraft.netherway.core.Platform;
import cn.ripplecraft.netherway.core.ServeCommand;
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
 * 服务端内置的 serve 进程：随 MC 服务端启动，把本地端口注册为房间代理。
 *
 * <p>参数与下发给客户端的凭证同源（都来自 {@code server.params}），
 * 从根上杜绝「凭证是 test 房间、宿主机却注册着别的房间」这类漂移；
 * 生命周期也跟着服务端走，不再需要 systemd/screen 单独伺候一个进程。
 *
 * <p>frp 掉线会自己重连（LoginFailExit=false），所以这里不做自动重启：
 * 进程退出通常意味着配置错误，重启只会无限刷同一个错。
 */
public final class ServerAgent {

    private static final Logger LOG = LogManager.getLogger(Netherway.MODID);
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final ModConfig config;
    private final AtomicBoolean stopping = new AtomicBoolean(false);

    private volatile Process process;
    private Thread shutdownHook;

    public ServerAgent(ModConfig config) {
        this.config = config;
    }

    /**
     * 启动 serve。失败只记日志——直连是增强功能，绝不能拖垮服务端启动。
     *
     * @param rendezvousPort 内嵌会合点端口（回环）；0 表示不启用，连公网 frps。
     *                       非零时必须与 {@link ConnectionSniffer} 收到的是同一个数。
     */
    public void start(Path cacheDir, int localPort, int rendezvousPort) {
        if (process != null) {
            return;
        }
        if (!Credentials.BACKEND_FRP_XTCP.equals(config.serverBackendId())) {
            LOG.warn("内置 serve 目前仅支持 frp-xtcp（当前 backend: {}），"
                    + "请在宿主机上自行运行对应的隧道服务", config.serverBackendId());
            return;
        }
        try {
            Platform platform = Platform.detect();
            Path exe = new BinaryStore(cacheDir, platform).ensureExtracted();
            List<String> cmd = ServeCommand.build(exe, config.serverParams(), localPort,
                    new ServeCommand.Options()
                            .metaToken(config.serveAuthToken())
                            .proxyProtocol(config.serveProxyProtocol())
                            .rendezvousPort(rendezvousPort)
                            .signingKey(rendezvousPort > 0 ? config.tokenSigningKey() : null));
            LOG.info("启动内置 serve（平台 {}）: {}", platform, ServeCommand.describe(cmd));

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(cacheDir.toFile());
            // serve 没有 stdout 上的 JSON 契约，合并两个流一起转进服务端日志
            pb.redirectErrorStream(true);
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
                    proc.destroy();
                }
            }, "netherway-serve-shutdown");
            try {
                Runtime.getRuntime().addShutdownHook(shutdownHook);
            } catch (IllegalStateException alreadyShuttingDown) {
                // JVM 正在退出，无需再注册
            }
        } catch (Platform.UnsupportedPlatformException e) {
            LOG.warn("当前系统没有内置的 agent 二进制，无法启动 serve: {}", e.getMessage());
        } catch (IOException e) {
            LOG.warn("内置 serve 启动失败", e);
        }
    }

    /** 把 serve 的输出逐行转进服务端日志；frp 的 warn/error 行保持醒目。 */
    private void pumpOutput(Process proc) {
        BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), UTF8));
        try {
            String line;
            while ((line = r.readLine()) != null) {
                String t = line.trim();
                if (t.isEmpty()) {
                    continue;
                }
                if (t.contains(" [W] ") || t.contains(" [E] ")) {
                    LOG.warn("[serve] {}", t);
                } else {
                    LOG.info("[serve] {}", t);
                }
            }
        } catch (IOException ignored) {
            // 进程结束导致管道关闭，属正常路径
        }
        if (!stopping.get()) {
            int code = exitCodeOf(proc);
            LOG.warn("内置 serve 进程退出（码 {}）。frp 掉线会自动重连，进程直接退出"
                    + "通常是配置错误（frps 地址/令牌/密钥），原因见上方 [serve] 日志；"
                    + "修正配置后重启服务端生效", code);
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

    /** 服务端关闭时调用：先请求优雅退出（关掉代理注册），超时强杀。 */
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
        LOG.info("内置 serve 已停止");
    }
}
