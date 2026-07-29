package cn.ripplecraft.xtcpinmc.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * core 的自检。
 *
 * <p>刻意不依赖 JUnit：core 的价值之一就是零第三方依赖，测试也保持同样标准，
 * 一条 javac + java 就能跑，不需要先把 Gradle 和 1.7.10 那套工具链拉起来。
 *
 * <p>运行：{@code java -cp <classes> cn.ripplecraft.xtcpinmc.core.SelfTest}
 */
public final class SelfTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        testPlatformDetect();
        testPlatformResourcePaths();
        testJsonBasics();
        testJsonEscapes();
        testJsonRejectsNested();
        testAgentEventParsing();
        testAgentEventTolerance();
        testCredentialsRoundTrip();
        testCredentialsValidation();
        testCredentialsHidesSecrets();
        testBuildCommand();
        testTimingsNormalization();
        testUpgradeGivesUpWithoutBinary();
        testUpgradeIgnoresDuplicateCredentials();

        System.out.println();
        System.out.println("通过 " + passed + "，失败 " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ---------- Platform ----------

    private static void testPlatformDetect() {
        check("Windows/amd64", Platform.detect("Windows 10", "amd64").toString().equals("windows-amd64"));
        check("Windows/x86_64 别名", Platform.detect("Windows 11", "x86_64").toString().equals("windows-amd64"));
        check("macOS/aarch64 别名", Platform.detect("Mac OS X", "aarch64").toString().equals("macos-arm64"));
        check("macOS/arm64", Platform.detect("Mac OS X", "arm64").toString().equals("macos-arm64"));
        check("Linux/amd64", Platform.detect("Linux", "amd64").toString().equals("linux-amd64"));

        boolean threwOs = false;
        try {
            Platform.detect("Solaris", "amd64");
        } catch (Platform.UnsupportedPlatformException e) {
            threwOs = true;
        }
        check("未知系统应抛异常", threwOs);

        boolean threwArch = false;
        try {
            Platform.detect("Linux", "riscv64");
        } catch (Platform.UnsupportedPlatformException e) {
            threwArch = true;
        }
        check("未知架构应抛异常", threwArch);
    }

    private static void testPlatformResourcePaths() {
        Platform win = Platform.detect("Windows 10", "amd64");
        check("Windows 资源路径带 .exe",
                win.resourcePath().equals("natives/windows-amd64/xtcpinmc.exe"));
        check("Windows 可执行名带 .exe",
                win.executableName().equals("xtcpinmc-windows-amd64.exe"));
        check("Windows 判定", win.isWindows());

        Platform mac = Platform.detect("Mac OS X", "arm64");
        check("macOS 资源路径无后缀",
                mac.resourcePath().equals("natives/macos-arm64/xtcpinmc"));
        check("macOS 非 Windows", !mac.isWindows());
    }

    // ---------- Json ----------

    private static void testJsonBasics() {
        java.util.Map<String, String> m =
                Json.parseObject("{\"event\":\"ready\",\"port\":63128,\"rttMs\":31}");
        check("解析字符串值", "ready".equals(m.get("event")));
        check("解析数字值", "63128".equals(m.get("port")));
        check("解析第三个字段", "31".equals(m.get("rttMs")));
        check("空对象", Json.parseObject("{}").isEmpty());
        check("含空白", Json.parseObject("{ \"a\" : \"b\" }").get("a").equals("b"));
    }

    private static void testJsonEscapes() {
        // Go 的 encoding/json 默认把 < > & 转成 Unicode 转义序列，必须能还原。
        // 注意本行不能直接写出那个反斜杠加 u 的字面形式——Java 在词法分析之前
        // 就会处理它，哪怕出现在注释里也会导致编译失败。
        java.util.Map<String, String> m = Json.parseObject(
                "{\"reason\":\"read tcp 127.0.0.1:1-\\u003e127.0.0.1:2: timeout\"}");
        check("还原 \\u 转义", m.get("reason").contains("->"));

        java.util.Map<String, String> q = Json.parseObject("{\"a\":\"say \\\"hi\\\"\"}");
        check("还原转义引号", q.get("a").equals("say \"hi\""));

        java.util.Map<String, String> n = Json.parseObject("{\"a\":\"l1\\nl2\\tx\"}");
        check("还原换行与制表", n.get("a").equals("l1\nl2\tx"));
    }

    private static void testJsonRejectsNested() {
        boolean threw = false;
        try {
            Json.parseObject("{\"a\":{\"b\":\"c\"}}");
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check("嵌套对象应报错而非静默出错", threw);
    }

    // ---------- AgentEvent ----------

    private static void testAgentEventParsing() {
        AgentEvent ready = AgentEvent.parse(
                "{\"event\":\"ready\",\"port\":63128,\"elapsedMs\":1792,\"rttMs\":31,"
                        + "\"version\":\"1.7.10\",\"online\":1}");
        check("识别 ready", ready != null && ready.type() == AgentEvent.Type.READY);
        check("解析端口", ready.port() == 63128);
        check("解析耗时", ready.elapsedMs() == 1792L);
        check("解析延迟", ready.rttMs() == 31L);
        check("解析版本", "1.7.10".equals(ready.version()));

        AgentEvent failed = AgentEvent.parse("{\"event\":\"failed\",\"reason\":\"打洞超时\"}");
        check("识别 failed", failed != null && failed.type() == AgentEvent.Type.FAILED);
        check("解析原因", "打洞超时".equals(failed.reason()));

        AgentEvent starting = AgentEvent.parse("{\"event\":\"starting\",\"port\":1}");
        check("识别 starting", starting != null && starting.type() == AgentEvent.Type.STARTING);
    }

    private static void testAgentEventTolerance() {
        check("忽略 null", AgentEvent.parse(null) == null);
        check("忽略空行", AgentEvent.parse("   ") == null);
        check("忽略非 JSON 行", AgentEvent.parse("2026-07-29 [I] some log line") == null);
        check("忽略坏 JSON", AgentEvent.parse("{\"event\":") == null);
        check("忽略无 event 字段", AgentEvent.parse("{\"port\":1}") == null);

        // 新版 agent 增加事件类型时，老 mod 不应崩溃
        AgentEvent unknown = AgentEvent.parse("{\"event\":\"someFutureEvent\"}");
        check("未知事件归为 UNKNOWN", unknown != null && unknown.type() == AgentEvent.Type.UNKNOWN);

        // 字段类型不符时退化为 0，而不是抛异常中断整条流水线
        AgentEvent weird = AgentEvent.parse("{\"event\":\"ready\",\"port\":\"abc\"}");
        check("非法数字退化为 0", weird != null && weird.port() == 0);
    }

    // ---------- Credentials ----------

    private static void testCredentialsRoundTrip() throws Exception {
        Credentials orig = new Credentials("203.0.113.10", 7000, "tok3n",
                "stun.miwifi.com:3478", "gtnh", "s3cr3t", 15000);
        Credentials back = Credentials.decode(orig.encode());

        check("往返 serverAddr", back.serverAddr().equals(orig.serverAddr()));
        check("往返 serverPort", back.serverPort() == 7000);
        check("往返 token", back.token().equals("tok3n"));
        check("往返 stun", back.stunServer().equals("stun.miwifi.com:3478"));
        check("往返 room", back.roomName().equals("gtnh"));
        check("往返 secret", back.secretKey().equals("s3cr3t"));
        check("往返 timeout", back.punchTimeoutMs() == 15000);

        // 中文和特殊字符要能安全通过（writeUTF 是 modified UTF-8）
        Credentials cn = new Credentials("a.example.com", 1, "令牌", "s:1", "涟漪GT", "密钥#1", 0);
        Credentials cnBack = Credentials.decode(cn.encode());
        check("往返中文房间名", cnBack.roomName().equals("涟漪GT"));
        check("往返中文密钥", cnBack.secretKey().equals("密钥#1"));
    }

    private static void testCredentialsValidation() {
        boolean threw = false;
        try {
            new Credentials("", 7000, "t", "s", "r", "k", 0);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check("空 serverAddr 应拒绝", threw);

        boolean threwEmpty = false;
        try {
            Credentials.decode(new byte[0]);
        } catch (Exception e) {
            threwEmpty = true;
        }
        check("空数据应拒绝", threwEmpty);
    }

    private static void testCredentialsHidesSecrets() {
        Credentials c = new Credentials("host", 7000, "SUPER_TOKEN",
                "stun:1", "room", "SUPER_SECRET", 0);
        String s = c.toString();
        check("toString 不含 token", !s.contains("SUPER_TOKEN"));
        check("toString 不含密钥", !s.contains("SUPER_SECRET"));
        check("toString 含房间名便于排查", s.contains("room"));
    }

    // ---------- AgentProcess 命令行 ----------

    private static void testBuildCommand() {
        Credentials cred = new Credentials("1.2.3.4", 7000, "tok",
                "stun.miwifi.com:3478", "gtnh", "sec", 0);
        List<String> cmd = AgentProcess.buildCommand(
                Paths.get("/tmp/xtcpinmc"), cred, Timings.defaults());

        check("首个参数是可执行文件", cmd.get(0).endsWith("xtcpinmc"));
        check("子命令是 tunnel", "tunnel".equals(cmd.get(1)));
        check("包含 -server", cmd.contains("-server") && cmd.contains("1.2.3.4"));
        check("包含 -room", cmd.contains("-room") && cmd.contains("gtnh"));
        check("包含 -secret", cmd.contains("-secret") && cmd.contains("sec"));
        check("默认超时 15s", cmd.contains("15.000"));

        // 服务端下发的超时应当覆盖客户端默认值
        Credentials override = new Credentials("1.2.3.4", 7000, "tok",
                "stun:1", "gtnh", "sec", 3000);
        List<String> cmd2 = AgentProcess.buildCommand(
                Paths.get("/tmp/xtcpinmc"), override, Timings.defaults());
        check("服务端超时优先", cmd2.contains("3.000") && !cmd2.contains("15.000"));
    }

    private static void testTimingsNormalization() {
        Timings zeroed = new Timings(0, 0, 0, 0).normalized();
        check("零值回填打洞超时", zeroed.punchTimeoutMs() == 15000L);
        check("零值回填探测间隔", zeroed.probeIntervalMs() == 250L);
        check("等待总时长大于打洞超时",
                zeroed.outcomeWaitMs() > zeroed.punchTimeoutMs());

        Timings custom = new Timings(30000, 500, 3000, 2000).normalized();
        check("保留自定义值", custom.punchTimeoutMs() == 30000L);
        check("自定义等待总时长", custom.outcomeWaitMs() == 32000L);
    }

    // ---------- UpgradeController ----------

    /** 记录调用的假 bridge，避免测试触碰真实游戏。 */
    private static final class FakeBridge implements ClientBridge {
        final List<String> logs = new ArrayList<String>();
        final List<String> connects = new ArrayList<String>();
        final CountDownLatch settled = new CountDownLatch(1);
        private final Path dir;

        FakeBridge(Path dir) {
            this.dir = dir;
        }

        @Override
        public void runOnGameThread(Runnable task) {
            task.run();
        }

        @Override
        public void connectTo(String host, int port) {
            synchronized (connects) {
                connects.add(host + ":" + port);
            }
        }

        @Override
        public void notifyPlayer(String message) {
            // 测试里不关心
        }

        @Override
        public Path cacheDirectory() {
            return dir;
        }

        @Override
        public void info(String message) {
            synchronized (logs) {
                logs.add(message);
            }
            if (message.startsWith("放弃直连")) {
                settled.countDown();
            }
        }

        @Override
        public void warn(String message, Throwable error) {
            synchronized (logs) {
                logs.add("WARN " + message);
            }
        }
    }

    private static void testUpgradeGivesUpWithoutBinary() throws Exception {
        Path tmp = Files.createTempDirectory("xtcpinmc-test");
        FakeBridge bridge = new FakeBridge(tmp);
        UpgradeController c = new UpgradeController(bridge, Timings.defaults());

        Credentials cred = new Credentials("127.0.0.1", 7000, "t",
                "stun:1", "room-a", "k", 1000);
        check("首次凭证启动升级", c.onCredentials(cred));

        // jar 里没有 natives 资源，应当迅速放弃而不是卡住等超时
        check("在超时前就放弃", bridge.settled.await(10, TimeUnit.SECONDS));
        check("终态是 GAVE_UP", c.state() == UpgradeController.State.GAVE_UP);
        check("没有触发连接切换", bridge.connects.isEmpty());
        c.shutdown();
    }

    private static void testUpgradeIgnoresDuplicateCredentials() throws Exception {
        Path tmp = Files.createTempDirectory("xtcpinmc-test2");
        FakeBridge bridge = new FakeBridge(tmp);
        UpgradeController c = new UpgradeController(bridge, Timings.defaults());

        Credentials cred = new Credentials("127.0.0.1", 7000, "t",
                "stun:1", "room-b", "k", 1000);
        c.onCredentials(cred);
        bridge.settled.await(10, TimeUnit.SECONDS);

        // 同房间已放弃后再次下发不应重新开始——否则每次重连都会重试一遍，
        // 在打不通的网络下会变成无休止的折腾
        check("放弃后忽略同房间重复凭证", !c.onCredentials(cred));
        c.shutdown();
        check("shutdown 后回到 IDLE", c.state() == UpgradeController.State.IDLE);
    }

    // ---------- 断言 ----------

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  ok   " + name);
        } else {
            failed++;
            System.out.println("  FAIL " + name);
        }
    }
}
