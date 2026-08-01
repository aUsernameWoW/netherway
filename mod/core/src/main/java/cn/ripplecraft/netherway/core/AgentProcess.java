package cn.ripplecraft.netherway.core;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 管理 agent 子进程：启动、读取状态、停止。
 *
 * <p>只用 {@link ProcessBuilder} 和标准 IO，不碰任何 JDK 内部 API——
 * 这套代码编译成 Java 8 字节码，但在 GTNH 的 lwjgl3ify 下会运行在 Java 17+ 上，
 * Java 16 起对内部 API 的强封装会让反射那类做法直接失败。
 */
public final class AgentProcess implements Closeable {

    /** 事件回调，用于把进度反映到游戏界面。实现必须能容忍在任意线程被调用。 */
    public interface Listener {
        void onEvent(AgentEvent event);

        /**
         * agent 在 stderr 上输出了一行诊断信息（参数键清单、STUN 选择结果等）。
         * 转进游戏日志后，玩家不用翻临时目录就能看到打洞失败在哪一步。
         */
        void onStderrLine(String line);
    }

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int STDERR_TAIL_LINES = 8;

    private final List<String> stderrTail = new ArrayList<String>();

    private final Process process;
    private final Thread stdoutPump;
    private final Thread stderrPump;
    private final BlockingQueue<AgentEvent> terminal = new ArrayBlockingQueue<AgentEvent>(4);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Thread shutdownHook;

    private AgentProcess(Process process, Listener listener) {
        this.process = process;

        this.stdoutPump = new Thread(new Runnable() {
            @Override
            public void run() {
                pumpStdout(listener);
            }
        }, "netherway-agent-stdout");
        this.stdoutPump.setDaemon(true);

        // stderr 必须也被消费：管道缓冲区填满后子进程写日志会永久阻塞，
        // 表现为隧道莫名其妙卡住，且极难排查。
        // 内容不能直接丢弃——agent 启动失败时原因只在这里，
        // 丢掉的话排查就只剩「进程退出了」这种毫无信息量的结论。
        this.stderrPump = new Thread(new Runnable() {
            @Override
            public void run() {
                drainKeepingTail(process.getErrorStream(), listener);
            }
        }, "netherway-agent-stderr");
        this.stderrPump.setDaemon(true);

        // 游戏进程被强杀时（崩溃、任务管理器结束），守护线程来不及清理，
        // 这个钩子保证不会留下孤儿 agent 占着端口。
        this.shutdownHook = new Thread(new Runnable() {
            @Override
            public void run() {
                process.destroy();
            }
        }, "netherway-agent-shutdown");

        this.stdoutPump.start();
        this.stderrPump.start();
        try {
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        } catch (IllegalStateException alreadyShuttingDown) {
            // JVM 正在退出，无需再注册
        }
    }

    /**
     * 启动 agent 并开始打洞。
     *
     * @param exe      已释放到磁盘的可执行文件
     * @param cred     服务端下发的凭证
     * @param timings  客户端配置的时间参数
     * @param workDir  工作目录，可为 null
     * @param logFile  agent 详细日志的落盘位置，null 时由 agent 自选临时目录
     * @param listener 状态回调，可为 null
     */
    public static AgentProcess start(Path exe, Credentials cred, Timings timings,
                                     Path workDir, Path logFile, Listener listener) throws IOException {
        return start(exe, cred, timings, workDir, logFile, 0, listener);
    }

    /**
     * 同上，另指定隧道要绑定的本地端口。
     *
     * @param bindPort 期望的本地端口，0 表示由 agent 自动分配。被占用时
     *                 agent 会自动回落到空闲端口，实际端口以 STARTING/READY
     *                 事件里的为准——调用方永远不要假设请求的端口就是结果。
     */
    public static AgentProcess start(Path exe, Credentials cred, Timings timings,
                                     Path workDir, Path logFile, int bindPort,
                                     Listener listener) throws IOException {
        List<String> cmd = buildCommand(exe, cred, timings, logFile, bindPort);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (workDir != null) {
            pb.directory(workDir.toFile());
        }
        // 不合并 stderr：stdout 是 JSON 状态流，掺进日志就没法解析了
        pb.redirectErrorStream(false);

        return new AgentProcess(pb.start(), listener);
    }

    static List<String> buildCommand(Path exe, Credentials cred, Timings timings, Path logFile) {
        return buildCommand(exe, cred, timings, logFile, 0);
    }

    static List<String> buildCommand(Path exe, Credentials cred, Timings timings,
                                     Path logFile, int bindPort) {
        Timings t = timings == null ? Timings.defaults() : timings.normalized();

        // 服务端下发的超时优先：它更清楚自己这端的网络状况；
        // 未下发（<=0）时用客户端配置。
        long punchMs = cred.punchTimeoutMs() > 0 ? cred.punchTimeoutMs() : t.punchTimeoutMs();

        List<String> cmd = new ArrayList<String>();
        cmd.add(exe.toAbsolutePath().toString());
        cmd.add("tunnel");
        // backend 与参数原样转交，这里不解释含义——新增隧道方案时不用动这段
        cmd.add("-backend");
        cmd.add(cred.backendId());
        for (Map.Entry<String, String> e : cred.params().entrySet()) {
            cmd.add("-O");
            cmd.add(e.getKey() + "=" + e.getValue());
        }
        if (bindPort > 0) {
            // 预热隧道用：条目地址尽量稳定。agent 在端口被占用时自动回落
            cmd.add("-port");
            cmd.add(Integer.toString(bindPort));
        }
        cmd.add("-timeout");        cmd.add(seconds(punchMs));
        cmd.add("-probe-interval"); cmd.add(seconds(t.probeIntervalMs()));
        cmd.add("-probe-timeout");  cmd.add(seconds(t.probeTimeoutMs()));
        // 固定开 debug：这些日志只进文件不进游戏日志，没有噪音代价，
        // 而打洞失败后这份文件是唯一能还原过程的材料
        cmd.add("-v");
        if (logFile != null) {
            cmd.add("-log-file");
            cmd.add(logFile.toAbsolutePath().toString());
        }
        return cmd;
    }

    /**
     * 供日志输出的命令行描述：{@code -O} 携带的参数值一律抹掉。
     * token 与密钥都在其中，而 core 不解释参数含义、分不清哪个敏感，
     * 只能全部只留键名——与 {@link Credentials#toString()} 同一条纪律。
     */
    static String describeCommand(List<String> cmd) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cmd.size(); i++) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            String arg = cmd.get(i);
            if ("-O".equals(arg) && i + 1 < cmd.size()) {
                String kv = cmd.get(++i);
                int eq = kv.indexOf('=');
                sb.append("-O ").append(eq < 0 ? kv : kv.substring(0, eq + 1) + "***");
                continue;
            }
            sb.append(arg);
        }
        return sb.toString();
    }

    /** agent 的时间参数以秒为单位，且要避免本地化小数点（德语环境会写成 "1,5"）。 */
    private static String seconds(long millis) {
        return String.format(java.util.Locale.ROOT, "%.3f", millis / 1000.0);
    }

    private void pumpStdout(Listener listener) {
        BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(), UTF8));
        try {
            String line;
            while ((line = r.readLine()) != null) {
                AgentEvent e = AgentEvent.parse(line);
                if (e == null) {
                    continue; // 非 JSON 行直接忽略，不让意外输出中断升级
                }
                if (listener != null) {
                    try {
                        listener.onEvent(e);
                    } catch (RuntimeException ignored) {
                        // 回调里的异常不能拖垮读取线程
                    }
                }
                if (e.type() == AgentEvent.Type.READY || e.type() == AgentEvent.Type.FAILED) {
                    terminal.offer(e);
                }
            }
        } catch (IOException ignored) {
            // 进程结束导致管道关闭，属正常路径
        } finally {
            // 进程没给出终态就退出了，唤醒等待方避免它一直等到超时。
            // 先给 stderr 读取线程一点时间把最后几行收完，否则诊断信息会是空的。
            try {
                stderrPump.join(500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            terminal.offer(earlyExitEvent());
        }
    }

    /** 进程没给出终态就结束了，带上 stderr 尾部，否则调用方无从判断原因。 */
    private AgentEvent earlyExitEvent() {
        StringBuilder sb = new StringBuilder("agent 未给出结果即退出");
        String tail = stderrTail();
        if (!tail.isEmpty()) {
            sb.append("：").append(tail);
        }
        // 走 AgentEvent.parse 会碰上引号转义问题，这里直接构造
        return AgentEvent.failed(sb.toString());
    }

    /** 返回 stderr 最近若干行，用于诊断。 */
    public String stderrTail() {
        synchronized (stderrTail) {
            if (stderrTail.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (String line : stderrTail) {
                if (sb.length() > 0) {
                    sb.append(" | ");
                }
                sb.append(line);
            }
            return sb.toString();
        }
    }

    private void drainKeepingTail(InputStream in, Listener listener) {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, UTF8));
        try {
            String line;
            while ((line = r.readLine()) != null) {
                String t = line.trim();
                if (t.isEmpty()) {
                    continue;
                }
                synchronized (stderrTail) {
                    stderrTail.add(t);
                    // 只留最近几行：agent 正常运行时 stderr 可能很长，
                    // 全存下来没意义，出错时也只有最后几行说明问题。
                    while (stderrTail.size() > STDERR_TAIL_LINES) {
                        stderrTail.remove(0);
                    }
                }
                if (listener != null) {
                    try {
                        listener.onStderrLine(t);
                    } catch (RuntimeException ignored) {
                        // 回调里的异常不能拖垮读取线程
                    }
                }
            }
        } catch (IOException ignored) {
            // 进程结束导致管道关闭，属正常路径
        }
    }

    /**
     * 等待隧道就绪。
     *
     * @return READY 或 FAILED 事件；等待超时返回 null
     */
    public AgentEvent awaitOutcome(long timeoutMs) throws InterruptedException {
        return terminal.poll(timeoutMs, TimeUnit.MILLISECONDS);
    }

    public boolean isAlive() {
        try {
            process.exitValue();
            return false;
        } catch (IllegalThreadStateException stillRunning) {
            return true;
        }
    }

    /**
     * 阻塞到进程退出。预热的重试循环用它守望就绪隧道：进程一死
     * （网络断、frps 重启、被杀）立即醒来进入下一轮重打。
     */
    public void awaitExit() throws InterruptedException {
        process.waitFor();
    }

    /**
     * 停止 agent。先请求优雅退出，超时未退再强杀。
     *
     * <p>Unix 上 destroy() 发 SIGTERM，agent 会关掉隧道再退出；
     * Windows 上没有等价语义，直接终止即可，隧道自然断开。
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException ignored) {
            // JVM 已在退出流程中
        }

        process.destroy();
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }

        stdoutPump.interrupt();
        stderrPump.interrupt();
    }
}
