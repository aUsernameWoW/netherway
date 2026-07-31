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
        testCredentialsGenericBackend();
        testCredentialsV1Compat();
        testCredentialsForwardCompat();
        testCredentialsValidation();
        testCredentialsHidesSecrets();
        testCredentialsDedupKey();
        testFrpXtcpParamKeys();
        testUpgradeReportRoundTrip();
        testUpgradeReportSanitizes();
        testUpgradeReportForwardCompat();
        testBuildCommand();
        testDescribeCommandMasksValues();
        testServeCommand();
        testTimingsNormalization();
        testUpgradeGivesUpWithoutBinary();
        testUpgradeIgnoresDuplicateCredentials();
        testCredentialCacheRoundTrip();
        testCredentialCacheKeepsMostRecent();
        testCredentialCacheSkipsCorrupt();
        testCredentialCachePrunes();
        testBuildCommandBindPort();
        testAdoptDirectConnection();
        testUpgradeReusesWarmTunnel();
        testTokenIssuer();
        testCredentialsWithExtraParams();
        testServeCommandMetaToken();
        testServeCommandProxyProtocol();
        testProxyProtocolV1();
        testProxyProtocolV2();
        testProxyProtocolSniff();
        testProxyProtocolInvalid();

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
        Credentials orig = Credentials.frpXtcp("203.0.113.10", 7000, "tok3n",
                "stun.miwifi.com:3478", "gtnh", "s3cr3t", 15000);
        Credentials back = Credentials.decode(orig.encode());

        check("往返 backend", Credentials.BACKEND_FRP_XTCP.equals(back.backendId()));
        check("往返 server 参数", "203.0.113.10".equals(back.param("server")));
        check("往返 serverPort 参数", "7000".equals(back.param("serverPort")));
        check("往返 token 参数", "tok3n".equals(back.param("token")));
        check("往返 stun 参数", "stun.miwifi.com:3478".equals(back.param("stun")));
        check("往返 room", back.room().equals("gtnh"));
        check("往返 secret 参数", "s3cr3t".equals(back.param("secret")));
        check("往返 timeout", back.punchTimeoutMs() == 15000);
        check("参数保持下发顺序",
                new ArrayList<String>(back.params().keySet()).get(0).equals("server"));

        // 中文和特殊字符要能安全通过（writeUTF 是 modified UTF-8）
        Credentials cn = Credentials.frpXtcp("a.example.com", 1, "令牌", "s:1",
                "涟漪GT", "密钥#1", 0);
        Credentials cnBack = Credentials.decode(cn.encode());
        check("往返中文房间名", cnBack.room().equals("涟漪GT"));
        check("往返中文密钥", "密钥#1".equals(cnBack.param("secret")));
    }

    private static void testCredentialsGenericBackend() throws Exception {
        // 核心承诺：新增隧道方案时 core 无需任何改动。
        // 用一个 core 从未听说过的 backend 走一遍编解码验证这一点。
        java.util.Map<String, String> p = new java.util.LinkedHashMap<String, String>();
        p.put(Credentials.PARAM_ROOM, "gtnh");
        p.put("endpoint", "relay.example.com:443");
        p.put("auth", "k3y");
        Credentials back = Credentials.decode(new Credentials("hysteria2", p, 8000).encode());
        check("未知 backend 原样往返", back.backendId().equals("hysteria2"));
        check("未知参数原样往返", "relay.example.com:443".equals(back.param("endpoint")));
        check("未知 backend 也有房间名", back.room().equals("gtnh"));
    }

    private static void testCredentialsV1Compat() throws Exception {
        // 老服务端下发的 v1（frp 专用布局）必须仍能解出来
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream out = new java.io.DataOutputStream(buf);
        out.writeByte(1);
        out.writeUTF("1.2.3.4");
        out.writeInt(7000);
        out.writeUTF("tok");
        out.writeUTF("stun:1");
        out.writeUTF("gtnh");
        out.writeUTF("sec");
        out.writeInt(3000);

        Credentials v1 = Credentials.decode(buf.toByteArray());
        check("v1 识别为 frp-xtcp", Credentials.BACKEND_FRP_XTCP.equals(v1.backendId()));
        check("v1 字段映射到参数", "1.2.3.4".equals(v1.param("server"))
                && "sec".equals(v1.param("secret")));
        check("v1 房间名", v1.room().equals("gtnh"));
        check("v1 超时", v1.punchTimeoutMs() == 3000);
    }

    private static void testCredentialsForwardCompat() throws Exception {
        // 未来版本在尾部追加字段时，老客户端读已知前缀、忽略其余
        byte[] v2 = Credentials.frpXtcp("h", 1, "t", "s:1", "gtnh", "k", 0).encode();
        byte[] v3 = new byte[v2.length + 5];
        System.arraycopy(v2, 0, v3, 0, v2.length);
        v3[0] = 3;
        Credentials fut = Credentials.decode(v3);
        check("未来版本读已知前缀", fut.room().equals("gtnh"));
        check("未来版本参数完整", "t".equals(fut.param("token")));
    }

    private static void testCredentialsValidation() {
        boolean threw = false;
        try {
            Credentials.frpXtcp("", 7000, "t", "s", "r", "k", 0);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check("空 serverAddr 应拒绝", threw);

        boolean threwBackend = false;
        try {
            new Credentials("", new java.util.LinkedHashMap<String, String>(), 0);
        } catch (IllegalArgumentException e) {
            threwBackend = true;
        }
        check("空 backendId 应拒绝", threwBackend);

        boolean threwRoom = false;
        try {
            java.util.Map<String, String> p = new java.util.LinkedHashMap<String, String>();
            p.put("endpoint", "x");
            new Credentials("b", p, 0);
        } catch (IllegalArgumentException e) {
            threwRoom = true;
        }
        check("缺 room 参数应拒绝", threwRoom);

        boolean threwKey = false;
        try {
            java.util.Map<String, String> p = new java.util.LinkedHashMap<String, String>();
            p.put(Credentials.PARAM_ROOM, "r");
            p.put("a=b", "x"); // 会破坏 -O key=value 的拆分
            new Credentials("b", p, 0);
        } catch (IllegalArgumentException e) {
            threwKey = true;
        }
        check("键含等号应拒绝", threwKey);

        boolean threwEmpty = false;
        try {
            Credentials.decode(new byte[0]);
        } catch (Exception e) {
            threwEmpty = true;
        }
        check("空数据应拒绝", threwEmpty);

        boolean threwOld = false;
        try {
            Credentials.decode(new byte[]{0});
        } catch (Exception e) {
            threwOld = true;
        }
        check("未知旧版本应拒绝", threwOld);
    }

    private static void testCredentialsHidesSecrets() {
        Credentials c = Credentials.frpXtcp("host", 7000, "SUPER_TOKEN",
                "stun:1", "room-x", "SUPER_SECRET", 0);
        String s = c.toString();
        check("toString 不含 token", !s.contains("SUPER_TOKEN"));
        check("toString 不含密钥", !s.contains("SUPER_SECRET"));
        check("toString 含房间名便于排查", s.contains("room-x"));
        check("toString 含 backend", s.contains(Credentials.BACKEND_FRP_XTCP));
    }

    private static void testCredentialsDedupKey() {
        Credentials a = Credentials.frpXtcp("h1", 1, "t1", "s:1", "gtnh", "k1", 0);
        Credentials b = Credentials.frpXtcp("h2", 2, "t2", "s:2", "gtnh", "k2", 9);
        Credentials c = Credentials.frpXtcp("h1", 1, "t1", "s:1", "other", "k1", 0);
        check("同 backend 同房间去重键一致", a.dedupKey().equals(b.dedupKey()));
        check("不同房间去重键不同", !a.dedupKey().equals(c.dedupKey()));
    }

    // ---------- AgentProcess 命令行 ----------

    private static void testBuildCommand() {
        Credentials cred = Credentials.frpXtcp("1.2.3.4", 7000, "tok",
                "stun.miwifi.com:3478", "gtnh", "sec", 0);
        List<String> cmd = AgentProcess.buildCommand(
                Paths.get("/tmp/xtcpinmc"), cred, Timings.defaults(),
                Paths.get("/tmp/tunnel.log"));

        check("首个参数是可执行文件", cmd.get(0).endsWith("xtcpinmc"));
        check("子命令是 tunnel", "tunnel".equals(cmd.get(1)));
        int at = cmd.indexOf("-backend");
        check("指定 backend", at >= 0 && Credentials.BACKEND_FRP_XTCP.equals(cmd.get(at + 1)));
        check("server 经 -O 传递", cmd.contains("-O") && cmd.contains("server=1.2.3.4"));
        check("room 经 -O 传递", cmd.contains("room=gtnh"));
        check("secret 经 -O 传递", cmd.contains("secret=sec"));
        check("默认超时 15s", cmd.contains("15.000"));
        check("固定开启 agent 详细日志", cmd.contains("-v"));
        int lf = cmd.indexOf("-log-file");
        check("传递日志文件路径", lf >= 0 && cmd.get(lf + 1).endsWith("tunnel.log"));

        // 服务端下发的超时应当覆盖客户端默认值
        Credentials override = Credentials.frpXtcp("1.2.3.4", 7000, "tok",
                "stun:1", "gtnh", "sec", 3000);
        List<String> cmd2 = AgentProcess.buildCommand(
                Paths.get("/tmp/xtcpinmc"), override, Timings.defaults(), null);
        check("服务端超时优先", cmd2.contains("3.000") && !cmd2.contains("15.000"));
        check("未指定日志文件则不传 -log-file", !cmd2.contains("-log-file"));
    }

    private static void testDescribeCommandMasksValues() {
        Credentials cred = Credentials.frpXtcp("203.0.113.7", 7000, "SUPER_TOKEN",
                "stun:1", "gtnh", "SUPER_SECRET", 0);
        String desc = AgentProcess.describeCommand(AgentProcess.buildCommand(
                Paths.get("/tmp/xtcpinmc"), cred, Timings.defaults(),
                Paths.get("/tmp/tunnel.log")));

        check("命令行描述不含 token 值", !desc.contains("SUPER_TOKEN"));
        check("命令行描述不含密钥值", !desc.contains("SUPER_SECRET"));
        check("命令行描述不含服务器地址值", !desc.contains("203.0.113.7"));
        check("命令行描述保留参数键名",
                desc.contains("token=") && desc.contains("secret="));
        check("命令行描述保留非敏感旗标",
                desc.contains("-backend") && desc.contains("-timeout"));
    }

    private static void testFrpXtcpParamKeys() {
        Credentials c = Credentials.frpXtcp("h", 1, "t", "s:1", "r", "k", 0);
        check("契约键集与工厂产出一致",
                Credentials.frpXtcpParamKeys().equals(c.params().keySet()));
        check("契约键集含 secret", Credentials.frpXtcpParamKeys().contains("secret"));
        check("契约键集不含 key", !Credentials.frpXtcpParamKeys().contains("key"));
    }

    // ---------- UpgradeReport ----------

    private static void testUpgradeReportRoundTrip() throws Exception {
        UpgradeReport ok = UpgradeReport.decode(
                UpgradeReport.upgraded("gtnh", 31, 1792).encode());
        check("成功回执往返", ok.isUpgraded());
        check("成功回执房间", "gtnh".equals(ok.room()));
        check("成功回执延迟", ok.rttMs() == 31);
        check("成功回执耗时", ok.elapsedMs() == 1792);
        check("成功回执无原因", ok.reason().isEmpty());

        UpgradeReport bad = UpgradeReport.decode(
                UpgradeReport.gaveUp("gtnh", "打洞超时").encode());
        check("失败回执往返", !bad.isUpgraded());
        check("失败回执原因", "打洞超时".equals(bad.reason()));
    }

    private static void testUpgradeReportSanitizes() throws Exception {
        // 控制字符换成空格：防止恶意客户端往服务端日志里注入伪造行
        UpgradeReport r = UpgradeReport.decode(
                UpgradeReport.gaveUp("room", "第一行\n[INFO] 伪造的日志行").encode());
        check("原因中的换行被清洗", !r.reason().contains("\n"));

        StringBuilder longReason = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            longReason.append('x');
        }
        UpgradeReport t = UpgradeReport.decode(
                UpgradeReport.gaveUp("room", longReason.toString()).encode());
        check("超长原因被截断", t.reason().length() <= 301);
    }

    private static void testUpgradeReportForwardCompat() throws Exception {
        // 未来版本在尾部追加字段，老服务端读已知前缀仍能解出
        byte[] v1 = UpgradeReport.upgraded("gtnh", 31, 1792).encode();
        byte[] extended = new byte[v1.length + 4];
        System.arraycopy(v1, 0, extended, 0, v1.length);
        extended[0] = 9; // 假装是版本 9，尾部多 4 字节新字段
        UpgradeReport r = UpgradeReport.decode(extended);
        check("高版本回执读已知前缀", r.isUpgraded() && "gtnh".equals(r.room()));

        boolean threw = false;
        try {
            UpgradeReport.decode(new byte[]{0});
        } catch (Exception e) {
            threw = true;
        }
        check("过旧版本回执应拒绝", threw);
    }

    private static void testServeCommand() {
        java.util.Map<String, String> params = new java.util.LinkedHashMap<String, String>();
        params.put("server", "frps.example.com");
        params.put("serverPort", "7000");
        params.put("token", "SUPER_TOKEN");
        params.put("room", "test");
        params.put("secret", "SUPER_SECRET");
        params.put("futureKey", "whatever"); // 未知键应被忽略

        List<String> cmd = ServeCommand.build(Paths.get("/srv/xtcpinmc"), params, 25570);
        check("serve 子命令", "serve".equals(cmd.get(1)));
        int server = cmd.indexOf("-server");
        check("server 映射为 -server",
                server >= 0 && "frps.example.com".equals(cmd.get(server + 1)));
        int sp = cmd.indexOf("-server-port");
        check("serverPort 映射为 -server-port", sp >= 0 && "7000".equals(cmd.get(sp + 1)));
        int room = cmd.indexOf("-room");
        check("room 映射为 -room", room >= 0 && "test".equals(cmd.get(room + 1)));
        int port = cmd.indexOf("-port");
        check("本地端口经 -port 传递", port >= 0 && "25570".equals(cmd.get(port + 1)));
        check("未知键被忽略", !cmd.contains("futureKey") && !cmd.contains("whatever"));
        check("缺失的键不传旗标", !cmd.contains("-stun"));

        String desc = ServeCommand.describe(cmd);
        check("serve 描述不含 token 值", !desc.contains("SUPER_TOKEN"));
        check("serve 描述不含密钥值", !desc.contains("SUPER_SECRET"));
        check("serve 描述保留 frps 地址", desc.contains("frps.example.com"));
        check("serve 描述保留房间名", desc.contains("-room test"));
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
        final List<byte[]> reports = new ArrayList<byte[]>();
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
        public void sendToServer(byte[] payload) {
            synchronized (reports) {
                reports.add(payload);
            }
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

        @Override
        public void debug(String message) {
            synchronized (logs) {
                logs.add("DEBUG " + message);
            }
        }
    }

    private static void testUpgradeGivesUpWithoutBinary() throws Exception {
        Path tmp = Files.createTempDirectory("xtcpinmc-test");
        FakeBridge bridge = new FakeBridge(tmp);
        UpgradeController c = new UpgradeController(bridge, Timings.defaults());

        Credentials cred = Credentials.frpXtcp("127.0.0.1", 7000, "t",
                "stun:1", "room-a", "k", 1000);
        check("首次凭证启动升级", c.onCredentials(cred));

        // jar 里没有 natives 资源，应当迅速放弃而不是卡住等超时
        check("在超时前就放弃", bridge.settled.await(10, TimeUnit.SECONDS));
        check("终态是 GAVE_UP", c.state() == UpgradeController.State.GAVE_UP);
        check("没有触发连接切换", bridge.connects.isEmpty());

        // 失败时应当把结果回执发给服务端，让服主在自己的日志里看到原因
        byte[] payload;
        synchronized (bridge.reports) {
            check("失败后回传了结果", bridge.reports.size() == 1);
            payload = bridge.reports.isEmpty() ? null : bridge.reports.get(0);
        }
        if (payload != null) {
            UpgradeReport report = UpgradeReport.decode(payload);
            check("回执标记为失败", !report.isUpgraded());
            check("回执带房间名", "room-a".equals(report.room()));
            check("回执带失败原因", !report.reason().isEmpty());
        }
        c.shutdown();
    }

    private static void testUpgradeIgnoresDuplicateCredentials() throws Exception {
        Path tmp = Files.createTempDirectory("xtcpinmc-test2");
        FakeBridge bridge = new FakeBridge(tmp);
        UpgradeController c = new UpgradeController(bridge, Timings.defaults());

        Credentials cred = Credentials.frpXtcp("127.0.0.1", 7000, "t",
                "stun:1", "room-b", "k", 1000);
        c.onCredentials(cred);
        bridge.settled.await(10, TimeUnit.SECONDS);

        // 同房间已放弃后再次下发不应重新开始——否则每次重连都会重试一遍，
        // 在打不通的网络下会变成无休止的折腾
        check("放弃后忽略同房间重复凭证", !c.onCredentials(cred));
        c.shutdown();
        check("shutdown 后回到 IDLE", c.state() == UpgradeController.State.IDLE);
    }

    // ---------- CredentialCache ----------

    private static Credentials sampleCred(String room, String secret) {
        return Credentials.frpXtcp("1.2.3.4", 7000, "tok", "stun:1", room, secret, 15000);
    }

    private static java.util.List<Path> listCredFiles(Path dir) throws Exception {
        java.util.List<Path> out = new ArrayList<Path>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        java.nio.file.DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.cred");
        try {
            for (Path p : ds) {
                out.add(p);
            }
        } finally {
            ds.close();
        }
        return out;
    }

    /** 把指定房间的缓存文件改成指定 mtime——mtime 分辨率可能只有秒级，测试里显式拉开。 */
    private static void touch(Path dir, String room, long mtimeMs) throws Exception {
        for (Path f : listCredFiles(dir)) {
            if (room.equals(Credentials.decode(Files.readAllBytes(f)).room())) {
                Files.setLastModifiedTime(f,
                        java.nio.file.attribute.FileTime.fromMillis(mtimeMs));
            }
        }
    }

    private static void testCredentialCacheRoundTrip() throws Exception {
        Path dir = Files.createTempDirectory("xtcpinmc-cache").resolve("credentials");
        CredentialCache cache = new CredentialCache(dir);
        check("空缓存返回 null", cache.loadMostRecent() == null);

        cache.store(sampleCred("gtnh", "s1"));
        Credentials back = cache.loadMostRecent();
        check("缓存往返房间名", back != null && back.room().equals("gtnh"));
        check("缓存往返密钥", back != null && "s1".equals(back.param("secret")));

        // 同一房间重复缓存应覆盖同一个文件，而不是越攒越多
        cache.store(sampleCred("gtnh", "s2"));
        check("同房间覆盖后取到新值", "s2".equals(cache.loadMostRecent().param("secret")));
        check("同房间只留一个文件", listCredFiles(dir).size() == 1);
    }

    private static void testCredentialCacheKeepsMostRecent() throws Exception {
        Path dir = Files.createTempDirectory("xtcpinmc-cache2");
        CredentialCache cache = new CredentialCache(dir);
        cache.store(sampleCred("room-old", "k"));
        cache.store(sampleCred("room-new", "k"));
        touch(dir, "room-old", 1000L);
        touch(dir, "room-new", 2000L);
        check("取最近使用的房间", cache.loadMostRecent().room().equals("room-new"));
        touch(dir, "room-old", 3000L);
        check("旧房间刷新后重新领先", cache.loadMostRecent().room().equals("room-old"));
    }

    private static void testCredentialCacheSkipsCorrupt() throws Exception {
        Path dir = Files.createTempDirectory("xtcpinmc-cache3");
        CredentialCache cache = new CredentialCache(dir);
        cache.store(sampleCred("good", "k"));
        touch(dir, "good", 1000L);
        // 版本号对但内容残缺：解码会在半截处 EOF
        Path bad = dir.resolve("deadbeef00000000.cred");
        Files.write(bad, new byte[]{2, 0, 1});
        Files.setLastModifiedTime(bad, java.nio.file.attribute.FileTime.fromMillis(2000L));
        check("坏文件被跳过，取到完好的缓存", cache.loadMostRecent().room().equals("good"));
        check("坏文件已被清除", !Files.exists(bad));
    }

    private static void testCredentialCachePrunes() throws Exception {
        Path dir = Files.createTempDirectory("xtcpinmc-cache4");
        CredentialCache cache = new CredentialCache(dir);
        for (int i = 0; i < 6; i++) {
            cache.store(sampleCred("room-" + i, "k"));
            touch(dir, "room-" + i, 1000L * (i + 1));
        }
        check("超出上限的旧缓存被清理", listCredFiles(dir).size() <= 4);
        check("最新的房间仍在", cache.loadMostRecent().room().equals("room-5"));
    }

    // ---------- 预热与采认 ----------

    private static void testBuildCommandBindPort() {
        Credentials cred = sampleCred("gtnh", "sec");
        List<String> cmd = AgentProcess.buildCommand(
                Paths.get("/tmp/xtcpinmc"), cred, Timings.defaults(), null, 25595);
        int at = cmd.indexOf("-port");
        check("指定预热端口经 -port 传递", at >= 0 && "25595".equals(cmd.get(at + 1)));

        List<String> auto = AgentProcess.buildCommand(
                Paths.get("/tmp/xtcpinmc"), cred, Timings.defaults(), null);
        check("未指定端口则不传 -port", !auto.contains("-port"));
    }

    private static void testAdoptDirectConnection() throws Exception {
        Path tmp = Files.createTempDirectory("xtcpinmc-adopt");
        FakeBridge bridge = new FakeBridge(tmp);
        UpgradeController c = new UpgradeController(bridge, Timings.defaults());

        Credentials cred = sampleCred("room-adopt", "k");
        AgentEvent ready = AgentEvent.parse(
                "{\"event\":\"ready\",\"port\":25595,\"rttMs\":31,\"elapsedMs\":1792}");
        check("空参不采认", !c.adoptDirectConnection(null, ready));
        check("IDLE 下采认成功", c.adoptDirectConnection(cred, ready));
        check("采认后状态为 UPGRADED", c.state() == UpgradeController.State.UPGRADED);
        check("采认后不能重复采认", !c.adoptDirectConnection(cred, ready));

        // 进服后服务端照常下发凭证：应命中重复分支、不再启动升级，并回执成功
        check("采认后重复凭证被忽略", !c.onCredentials(cred));
        byte[] payload;
        synchronized (bridge.reports) {
            check("采认后回执了升级结果", bridge.reports.size() == 1);
            payload = bridge.reports.isEmpty() ? null : bridge.reports.get(0);
        }
        if (payload != null) {
            UpgradeReport report = UpgradeReport.decode(payload);
            check("回执标记为成功", report.isUpgraded());
            check("回执带预热隧道的延迟", report.rttMs() == 31);
        }
        check("再次下发仍被忽略", !c.onCredentials(cred));
        synchronized (bridge.reports) {
            check("成功回执只发一次", bridge.reports.size() == 1);
        }
        c.shutdown();
        check("shutdown 后回到 IDLE", c.state() == UpgradeController.State.IDLE);
    }

    private static void testUpgradeReusesWarmTunnel() throws Exception {
        Path tmp = Files.createTempDirectory("xtcpinmc-warm");
        FakeBridge bridge = new FakeBridge(tmp);
        WarmupController warmup = new WarmupController(bridge,
                new CredentialCache(tmp.resolve("credentials")),
                Timings.defaults(), null, 0);
        Credentials cred = sampleCred("room-warm", "k");
        AgentEvent ready = AgentEvent.parse(
                "{\"event\":\"ready\",\"port\":25595,\"rttMs\":31,\"elapsedMs\":1792}");

        check("未就绪时查询端口为 null", warmup.readyPort(cred.dedupKey()) == null);
        warmup.injectReadyForTest(cred, ready);
        check("就绪后按去重键查到端口",
                Integer.valueOf(25595).equals(warmup.readyPort(cred.dedupKey())));
        check("其他房间查不到", warmup.readyPort("frp-xtcp:other") == null);
        check("按端口反查得到凭证", warmup.credentialsForPort(25595) == cred);
        check("错误端口反查为 null", warmup.credentialsForPort(1) == null);

        // 玩家经中转进服、服务端下发同房间凭证：应复用预热隧道直接切换，
        // 而不是对同一房间再起一个 agent
        UpgradeController c = new UpgradeController(bridge, Timings.defaults(), null, warmup);
        check("收到凭证启动升级", c.onCredentials(cred));
        long deadline = System.currentTimeMillis() + 5000L;
        boolean connected = false;
        while (System.currentTimeMillis() < deadline && !connected) {
            synchronized (bridge.connects) {
                connected = bridge.connects.contains("127.0.0.1:25595");
            }
            Thread.sleep(20L);
        }
        check("复用预热隧道直接切换", connected);
        check("复用后状态为 UPGRADED", c.state() == UpgradeController.State.UPGRADED);
        c.shutdown();
        check("升级复位不影响预热隧道", warmup.readyPort(cred.dedupKey()) != null);
        warmup.shutdown();
        check("预热 shutdown 后查询为 null", warmup.readyPort(cred.dedupKey()) == null);
    }

    // ---------- 每玩家令牌 ----------

    private static void testTokenIssuer() {
        // 跨语言已知答案：与 Go 侧 internal/authplugin 的测试用同一组常量。
        // 任何一侧改了算法都会先在这里撞车，而不是玩家连不上时才发现。
        String user = "069a79f4-44e9-4726-a5be-fca90e38aaf5";
        String want = "1893456000."
                + "0b91c0bf30d621d5c890a011253ea4b83c918422d19689334a18508e9b6db088";
        check("跨语言已知答案一致", TokenIssuer.issue("k3y", user, 1893456000L).equals(want));
        check("密钥指纹与 Go 侧一致", TokenIssuer.keyFingerprint("k3y").equals("a49b1287"));

        String token = TokenIssuer.issue("key-a", user, 1893456000L);
        check("令牌以过期时间开头", token.startsWith("1893456000."));
        check("签名为 64 位十六进制",
                token.substring(token.indexOf('.') + 1).matches("[0-9a-f]{64}"));
        check("不同 user 令牌不同",
                !token.equals(TokenIssuer.issue("key-a", "other", 1893456000L)));
        check("不同过期时间令牌不同",
                !token.equals(TokenIssuer.issue("key-a", user, 1893456001L)));
        check("不同密钥令牌不同",
                !token.equals(TokenIssuer.issue("key-b", user, 1893456000L)));

        boolean threw = false;
        try {
            TokenIssuer.issue("", user, 1L);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check("空签发密钥应拒绝", threw);
    }

    private static void testCredentialsWithExtraParams() throws Exception {
        Credentials base = sampleCred("gtnh", "sec");
        java.util.Map<String, String> extra = new java.util.LinkedHashMap<String, String>();
        extra.put(Credentials.PARAM_USER, "uuid-1");
        extra.put(Credentials.PARAM_USER_TOKEN, "1893456000.abcd");
        Credentials withId = base.withExtraParams(extra);

        check("附加后含 user 参数", "uuid-1".equals(withId.param(Credentials.PARAM_USER)));
        check("附加后原参数保留", "sec".equals(withId.param("secret")));
        check("原凭证不受影响", base.param(Credentials.PARAM_USER) == null);
        check("去重键不因身份参数改变", base.dedupKey().equals(withId.dedupKey()));

        // 老客户端拿到带身份参数的凭证也能正常编解码并原样转交
        Credentials back = Credentials.decode(withId.encode());
        check("身份参数经编解码往返", "uuid-1".equals(back.param(Credentials.PARAM_USER)));
        check("身份参数排在原参数之后", new ArrayList<String>(
                back.params().keySet()).indexOf(Credentials.PARAM_USER) > 0);
        check("toString 不含令牌值", !withId.toString().contains("1893456000.abcd"));
    }

    private static void testServeCommandMetaToken() {
        java.util.Map<String, String> params = new java.util.LinkedHashMap<String, String>();
        params.put("server", "frps.example.com");
        params.put("room", "test");

        List<String> plain = ServeCommand.build(Paths.get("/srv/xtcpinmc"), params, 25570);
        check("未配置静态令牌则不传旗标", !plain.contains("-meta-token"));

        List<String> cmd = ServeCommand.build(Paths.get("/srv/xtcpinmc"), params, 25570,
                "SERVE_STATIC");
        int at = cmd.indexOf("-meta-token");
        check("静态令牌经 -meta-token 传递", at >= 0 && "SERVE_STATIC".equals(cmd.get(at + 1)));
        check("serve 描述抹掉静态令牌值",
                !ServeCommand.describe(cmd).contains("SERVE_STATIC"));
    }

    private static void testServeCommandProxyProtocol() {
        java.util.Map<String, String> params = new java.util.LinkedHashMap<String, String>();
        params.put("server", "frps.example.com");
        params.put("room", "test");

        List<String> plain = ServeCommand.build(Paths.get("/srv/xtcpinmc"), params, 25570,
                null, null);
        check("未配置 PROXY protocol 则不传旗标", !plain.contains("-proxy-protocol"));

        List<String> cmd = ServeCommand.build(Paths.get("/srv/xtcpinmc"), params, 25570,
                null, "v2");
        int at = cmd.indexOf("-proxy-protocol");
        check("PROXY protocol 版本经 -proxy-protocol 传递",
                at >= 0 && "v2".equals(cmd.get(at + 1)));
        check("旧的四参 build 不受影响",
                !ServeCommand.build(Paths.get("/srv/xtcpinmc"), params, 25570, "T")
                        .contains("-proxy-protocol"));
    }

    // ---------- ProxyProtocol ----------

    private static byte[] ascii(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }

    /** v2 头：12 字节签名 + 版本/命令 + 地址族 + 长度 + 负载。 */
    private static byte[] v2Header(int verCmd, int famProto, byte[] payload) {
        byte[] out = new byte[16 + payload.length];
        byte[] sig = {0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A, 0x51, 0x55, 0x49, 0x54, 0x0A};
        System.arraycopy(sig, 0, out, 0, 12);
        out[12] = (byte) verCmd;
        out[13] = (byte) famProto;
        out[14] = (byte) (payload.length >>> 8);
        out[15] = (byte) payload.length;
        System.arraycopy(payload, 0, out, 16, payload.length);
        return out;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static void testProxyProtocolV1() {
        byte[] header = ascii("PROXY TCP4 198.51.100.7 203.0.113.4 40000 25565\r\n");
        byte[] stream = concat(header, new byte[]{0x10, 0x00}); // 头后跟着 MC 握手片段

        ProxyProtocol.Result r = ProxyProtocol.parse(stream);
        check("v1 TCP4 识别为 PRESENT", r.status == ProxyProtocol.Status.PRESENT);
        check("v1 headerLength 恰为头部字节数", r.headerLength == header.length);
        check("v1 来源地址正确", r.source != null
                && "198.51.100.7".equals(r.source.getAddress().getHostAddress())
                && r.source.getPort() == 40000);

        // 半包：逐字节喂都不允许提前下错定论
        boolean progressive = true;
        for (int n = 1; n < header.length; n++) {
            progressive &= ProxyProtocol.parse(header, n).status == ProxyProtocol.Status.NEED_MORE;
        }
        check("v1 半包始终 NEED_MORE", progressive);

        ProxyProtocol.Result v6 = ProxyProtocol.parse(
                ascii("PROXY TCP6 2001:db8::7 2001:db8::1 40000 25565\r\n"));
        check("v1 TCP6 来源地址正确", v6.status == ProxyProtocol.Status.PRESENT
                && v6.source != null
                && "2001:db8:0:0:0:0:0:7".equals(v6.source.getAddress().getHostAddress()));

        ProxyProtocol.Result unknown = ProxyProtocol.parse(
                ascii("PROXY UNKNOWN whatever else\r\nrest"));
        check("v1 UNKNOWN 剥头但无地址", unknown.status == ProxyProtocol.Status.PRESENT
                && unknown.source == null
                && unknown.headerLength == ascii("PROXY UNKNOWN whatever else\r\n").length);
    }

    private static void testProxyProtocolV2() {
        byte[] addr4 = {
            (byte) 198, (byte) 51, 100, 7,      // src
            (byte) 203, 0, 113, 4,              // dst
            (byte) (40000 >>> 8), (byte) 40000, // sport
            0x63, (byte) 0xDD,                  // dport 25565
        };
        byte[] header = v2Header(0x21, 0x11, addr4); // PROXY 命令 + TCP over IPv4
        byte[] stream = concat(header, new byte[]{0x10, 0x00});

        ProxyProtocol.Result r = ProxyProtocol.parse(stream);
        check("v2 IPv4 识别为 PRESENT", r.status == ProxyProtocol.Status.PRESENT);
        check("v2 headerLength 恰为头部字节数", r.headerLength == header.length);
        check("v2 来源地址正确", r.source != null
                && "198.51.100.7".equals(r.source.getAddress().getHostAddress())
                && r.source.getPort() == 40000);

        boolean progressive = true;
        for (int n = 1; n < header.length; n++) {
            progressive &= ProxyProtocol.parse(header, n).status == ProxyProtocol.Status.NEED_MORE;
        }
        check("v2 半包始终 NEED_MORE", progressive);

        // 地址块后带 TLV：整段跳过，不解释
        byte[] withTlv = v2Header(0x21, 0x11, concat(addr4, new byte[]{0x04, 0x00, 0x01, 0x00}));
        ProxyProtocol.Result tlv = ProxyProtocol.parse(withTlv);
        check("v2 TLV 一并计入 headerLength", tlv.status == ProxyProtocol.Status.PRESENT
                && tlv.headerLength == withTlv.length && tlv.source != null);

        ProxyProtocol.Result local = ProxyProtocol.parse(v2Header(0x20, 0x00, new byte[0]));
        check("v2 LOCAL 剥头但无地址", local.status == ProxyProtocol.Status.PRESENT
                && local.source == null && local.headerLength == 16);
    }

    private static void testProxyProtocolSniff() {
        // MC 现代握手：VarInt 长度 + 包 id 0x00。即便长度碰上 'P'(0x50) 或
        // 0x0D，第 2 字节也必然与两种头分叉——这正是嗅探安全的依据。
        check("MC 握手(0x50 开头)不误判", ProxyProtocol.parse(
                new byte[]{0x50, 0x00, 0x2F}).status == ProxyProtocol.Status.NOT_PRESENT);
        check("MC 握手(0x0D 开头)不误判", ProxyProtocol.parse(
                new byte[]{0x0D, 0x00, 0x2F}).status == ProxyProtocol.Status.NOT_PRESENT);
        check("legacy ping(0xFE)不误判", ProxyProtocol.parse(
                new byte[]{(byte) 0xFE, 0x01}).status == ProxyProtocol.Status.NOT_PRESENT);
        check("空输入 NEED_MORE", ProxyProtocol.parse(
                new byte[0]).status == ProxyProtocol.Status.NEED_MORE);
        check("单字节 'P' 尚无定论", ProxyProtocol.parse(
                new byte[]{0x50}).status == ProxyProtocol.Status.NEED_MORE);
    }

    private static void testProxyProtocolInvalid() {
        check("v1 端口越界为 INVALID", ProxyProtocol.parse(
                ascii("PROXY TCP4 1.2.3.4 5.6.7.8 99999 25565\r\n"))
                .status == ProxyProtocol.Status.INVALID);
        check("v1 地址不是字面量为 INVALID", ProxyProtocol.parse(
                ascii("PROXY TCP6 abcd efgh 1 2\r\n"))
                .status == ProxyProtocol.Status.INVALID);
        // 前缀匹配但 107 字节内没有 CRLF：不能无限等下去
        byte[] runaway = new byte[120];
        byte[] prefix = ascii("PROXY TCP4 ");
        System.arraycopy(prefix, 0, runaway, 0, prefix.length);
        for (int i = prefix.length; i < runaway.length; i++) {
            runaway[i] = '1';
        }
        check("v1 超长无 CRLF 为 INVALID",
                ProxyProtocol.parse(runaway).status == ProxyProtocol.Status.INVALID);
        check("v2 版本号异常为 INVALID", ProxyProtocol.parse(
                v2Header(0x31, 0x11, new byte[12])).status == ProxyProtocol.Status.INVALID);
        check("v1 字段缺失为 INVALID", ProxyProtocol.parse(
                ascii("PROXY TCP4 1.2.3.4 5.6.7.8 40000\r\n"))
                .status == ProxyProtocol.Status.INVALID);
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
