package cn.ripplecraft.netherway.core;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 调 agent 的 {@code prefetch} 子命令预取凭证：mod 在 FML 加载期拿着
 * 游戏会话（{@link SessionIdentity}）向 authbridge 走一遍 hasJoined 预认证，
 * 凭证直接落进 {@link CredentialCache} 的目录，随后的预热打洞就有的用——
 * 首次启动、密钥轮换后都不再需要先经中转进一次服。
 *
 * <p>候选地址来自 {@link BridgeDiscovery}；探测与 HTTP 全在 Go 侧完成，
 * 这里只负责起子进程。accessToken 经环境变量传入，绝不进命令行——
 * 命令行在进程列表里对同机其他用户可见。
 *
 * <p>失败永远安静：预取只是优化，玩家照常走缓存/中转路径。
 */
public final class Prefetcher {

    /** accessToken 的传递通道，与 Go 侧 prefetch 的约定一致。 */
    static final String TOKEN_ENV = "NETHERWAY_ACCESS_TOKEN";

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final ClientBridge bridge;
    private final SessionIdentity session;
    private final List<String> candidates;
    private final Timings timings;

    public Prefetcher(ClientBridge bridge, SessionIdentity session,
                      List<String> candidates, Timings timings) {
        this.bridge = bridge;
        this.session = session;
        this.candidates = candidates;
        this.timings = timings == null ? Timings.defaults() : timings.normalized();
    }

    /**
     * 同步跑一次预取；成功（凭证已写进缓存目录）返回 true。
     * 只在预热的后台线程被调用，任何失败都只记日志、不抛出。
     */
    public boolean refresh(Path exe, Path credentialDir) {
        List<String> cmd = command(exe, candidates, session, credentialDir);
        bridge.debug("预取凭证: " + join(cmd));
        Process proc = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.environment().put(TOKEN_ENV, session.accessToken());
            // 合并输出：prefetch 没有 stdout 契约，两路都是给人看的日志
            pb.redirectErrorStream(true);
            proc = pb.start();

            // 输出必须有人消费，否则管道填满后子进程会永久阻塞（与 agent 同坑）
            final Process p = proc;
            Thread pump = new Thread(new Runnable() {
                @Override
                public void run() {
                    drain(p);
                }
            }, "netherway-prefetch-io");
            pump.setDaemon(true);
            pump.start();

            if (!proc.waitFor(timings.prefetchTimeoutMs(), TimeUnit.MILLISECONDS)) {
                proc.destroyForcibly();
                bridge.info("凭证预取超时（" + timings.prefetchTimeoutMs()
                        + "ms），本轮跳过——不影响正常游戏");
                return false;
            }
            pump.join(500L);
            if (proc.exitValue() == 0) {
                bridge.info("凭证预取成功，缓存已更新");
                return true;
            }
            bridge.debug("凭证预取未成功（退出码 " + proc.exitValue()
                    + "，详情见上方预取日志），退回缓存/中转路径");
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (proc != null) {
                proc.destroyForcibly();
            }
            return false;
        } catch (Exception e) {
            bridge.warn("凭证预取失败（不影响正常游戏）", e);
            if (proc != null) {
                proc.destroyForcibly();
            }
            return false;
        }
    }

    /**
     * 组装 prefetch 命令行。accessToken 刻意不在这里：它走
     * {@link #TOKEN_ENV} 环境变量。{@code -authserver} 也不传，
     * 皮肤站地址由 authbridge 在 /prefetch 响应里告知。
     */
    static List<String> command(Path exe, List<String> bridges,
                                SessionIdentity session, Path credentialDir) {
        List<String> cmd = new ArrayList<String>();
        cmd.add(exe.toAbsolutePath().toString());
        cmd.add("prefetch");
        for (String b : bridges) {
            cmd.add("-bridge");
            cmd.add(b);
        }
        cmd.add("-username");
        cmd.add(session.username());
        cmd.add("-uuid");
        cmd.add(session.uuid());
        cmd.add("-cache-dir");
        cmd.add(credentialDir.toAbsolutePath().toString());
        return cmd;
    }

    private void drain(Process proc) {
        BufferedReader r = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), UTF8));
        try {
            String line;
            while ((line = r.readLine()) != null) {
                String t = line.trim();
                if (!t.isEmpty()) {
                    bridge.debug("预取: " + t);
                }
            }
        } catch (Exception ignored) {
            // 进程结束导致管道关闭，属正常路径
        }
    }

    private static String join(List<String> cmd) {
        StringBuilder sb = new StringBuilder();
        for (String c : cmd) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
