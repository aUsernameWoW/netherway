package cn.ripplecraft.netherway.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 打通 Java 与真实 agent 的端到端验证：释放二进制 → 启动子进程 →
 * 打洞 → 解析状态 → 触发连接切换。
 *
 * <p>需要真实的 frps 与服务端 agent 在运行，因此不在 {@link SelfTest} 里，
 * 由调用方显式传参运行：
 *
 * <pre>
 * java -cp classes:testres cn.ripplecraft.netherway.core.IntegrationTest \
 *      &lt;frps地址&gt; &lt;端口&gt; &lt;令牌&gt; &lt;stun&gt; &lt;房间&gt; &lt;密钥&gt;
 * </pre>
 *
 * <p>classpath 里需包含 {@code natives/<平台>/netherway}，
 * 正式构建时它由 mod jar 提供。
 */
public final class IntegrationTest {

    public static void main(String[] args) throws Exception {
        if (args.length < 6) {
            System.err.println("用法: IntegrationTest <frps地址> <端口> <令牌> <stun> <房间> <密钥>");
            System.exit(2);
        }

        Credentials cred = Credentials.frpXtcp(args[0], Integer.parseInt(args[1]), args[2],
                args[3], args[4], args[5], 0);
        System.out.println("凭证: " + cred);

        Path cache = Files.createTempDirectory("netherway-it");
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<String> target = new AtomicReference<String>();

        ClientBridge bridge = new ClientBridge() {
            @Override
            public void runOnGameThread(Runnable task) {
                task.run();
            }

            @Override
            public void connectTo(String host, int port) {
                target.set(host + ":" + port);
                done.countDown();
            }

            @Override
            public void notifyPlayer(String message) {
                System.out.println("  [玩家提示] " + message);
            }

            @Override
            public void sendToServer(byte[] payload) {
                System.out.println("  [结果回执] " + payload.length + " 字节");
            }

            @Override
            public Path cacheDirectory() {
                return cache;
            }

            @Override
            public void info(String message) {
                System.out.println("  [信息] " + message);
                if (message.startsWith("放弃直连")) {
                    done.countDown();
                }
            }

            @Override
            public void warn(String message, Throwable error) {
                System.out.println("  [警告] " + message
                        + (error == null ? "" : " / " + error));
            }

            @Override
            public void debug(String message) {
                System.out.println("  [详细] " + message);
            }
        };

        System.out.println("平台: " + Platform.detect());
        UpgradeController controller = new UpgradeController(bridge, Timings.defaults());

        long t0 = System.currentTimeMillis();
        if (!controller.onCredentials(cred)) {
            System.err.println("升级流程未启动");
            System.exit(1);
        }

        boolean settled = done.await(40, TimeUnit.SECONDS);
        long elapsed = System.currentTimeMillis() - t0;

        System.out.println();
        System.out.println("状态: " + controller.state());
        System.out.println("耗时: " + elapsed + " ms");
        System.out.println("切换目标: " + target.get());

        boolean ok = settled
                && controller.state() == UpgradeController.State.UPGRADED
                && target.get() != null
                && target.get().startsWith("127.0.0.1:");

        // 二进制确实被释放到了缓存目录
        boolean extracted = Files.list(cache).findAny().isPresent();
        System.out.println("二进制已释放: " + extracted);

        controller.shutdown();
        System.out.println(ok && extracted ? "\n端到端验证通过" : "\n端到端验证失败");
        System.exit(ok && extracted ? 0 : 1);
    }
}
