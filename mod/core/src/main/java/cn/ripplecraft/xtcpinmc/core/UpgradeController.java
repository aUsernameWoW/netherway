package cn.ripplecraft.xtcpinmc.core;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 客户端升级流程的状态机：收到凭证 → 打洞 → 成功则切换连接。
 *
 * <p>整个过程对玩家是后台进行的：此刻他已经通过既有的中转隧道正常游戏，
 * 升级失败就安静地留在原连接上，不打断任何事情。
 *
 * <p>这个类不引用任何 Minecraft 类型，因此可以脱离游戏做单元测试。
 */
public final class UpgradeController {

    public enum State {
        /** 未开始，或已回到初始态。 */
        IDLE,
        /** agent 正在打洞。 */
        PUNCHING,
        /** 已切换到 P2P 连接。 */
        UPGRADED,
        /** 本次会话已放弃升级，不再重试。 */
        GAVE_UP
    }

    private final ClientBridge bridge;
    private final Timings timings;
    private final AtomicReference<State> state = new AtomicReference<State>(State.IDLE);

    private volatile AgentProcess agent;
    private volatile String activeKey;

    public UpgradeController(ClientBridge bridge, Timings timings) {
        this.bridge = bridge;
        this.timings = timings == null ? Timings.defaults() : timings.normalized();
    }

    public State state() {
        return state.get();
    }

    /**
     * 收到服务端下发的凭证。
     *
     * <p>切换连接后玩家会重新登录一次，服务端会再下发一次凭证——必须识别出
     * 这是同一个房间的重复下发并忽略，否则会陷入「升级→重连→再升级」的死循环。
     *
     * @return true 表示本次调用启动了升级流程
     */
    public boolean onCredentials(final Credentials cred) {
        if (cred == null) {
            return false;
        }

        if (cred.dedupKey().equals(activeKey)) {
            State s = state.get();
            if (s == State.UPGRADED || s == State.PUNCHING) {
                bridge.info("已在处理房间 " + cred.room() + " 的直连，忽略重复凭证");
                return false;
            }
            if (s == State.GAVE_UP) {
                // 本次会话已判定此房间打洞不通，别反复折腾玩家的网络
                return false;
            }
        }

        if (!state.compareAndSet(State.IDLE, State.PUNCHING)) {
            return false;
        }
        activeKey = cred.dedupKey();

        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                runUpgrade(cred);
            }
        }, "xtcpinmc-upgrade");
        worker.setDaemon(true);
        worker.start();
        return true;
    }

    private void runUpgrade(Credentials cred) {
        AgentProcess proc = null;
        try {
            Platform platform = Platform.detect();
            bridge.info("准备直连：平台 " + platform + "，房间 " + cred.room()
                    + "（" + cred.backendId() + "）");

            Path exe = new BinaryStore(bridge.cacheDirectory(), platform).ensureExtracted();
            proc = AgentProcess.start(exe, cred, timings, bridge.cacheDirectory(), null);
            agent = proc;

            AgentEvent outcome = proc.awaitOutcome(timings.outcomeWaitMs());

            if (outcome == null) {
                giveUp(proc, "等待直连结果超时");
                return;
            }
            if (outcome.type() != AgentEvent.Type.READY) {
                String why = outcome.reason() == null ? "打洞未成功" : outcome.reason();
                giveUp(proc, why);
                return;
            }

            // 到这里隧道已经通过 Minecraft 握手验证过，切换是安全的
            final int port = outcome.port();
            long rtt = outcome.rttMs();
            bridge.info("直连就绪，端口 " + port + "，延迟 " + rtt + "ms，用时 "
                    + outcome.elapsedMs() + "ms");

            state.set(State.UPGRADED);
            final String msg = rtt > 0
                    ? "已建立直连（延迟 " + rtt + "ms），正在切换…"
                    : "已建立直连，正在切换…";
            bridge.runOnGameThread(new Runnable() {
                @Override
                public void run() {
                    bridge.notifyPlayer(msg);
                    bridge.connectTo("127.0.0.1", port);
                }
            });

        } catch (Platform.UnsupportedPlatformException e) {
            // 没有对应平台的二进制，重试也没意义
            giveUp(proc, "当前系统不支持直连: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            giveUp(proc, "升级过程被中断");
        } catch (Exception e) {
            bridge.warn("建立直连失败", e);
            giveUp(proc, e.getMessage());
        }
    }

    private void giveUp(AgentProcess proc, String reason) {
        state.set(State.GAVE_UP);
        if (proc != null) {
            proc.close();
        }
        agent = null;
        // 升级失败对玩家无感：他仍在原有的中转连接上正常游戏，
        // 所以只记日志，不去打扰他。
        bridge.info("放弃直连，继续使用当前线路（" + reason + "）");
    }

    /**
     * 玩家离开服务器时调用：停掉 agent 并复位。
     *
     * <p>注意升级导致的重连也会触发断开，此时不能停掉隧道——正是它在承载
     * 即将建立的新连接。靠状态区分：UPGRADED 表示这次断开是我们自己造成的。
     */
    public void onDisconnected() {
        if (state.get() == State.UPGRADED) {
            return;
        }
        shutdown();
    }

    /** 彻底停止并复位，游戏退出或玩家切换服务器时调用。 */
    public void shutdown() {
        AgentProcess proc = agent;
        agent = null;
        activeKey = null;
        state.set(State.IDLE);
        if (proc != null) {
            proc.close();
        }
    }
}
