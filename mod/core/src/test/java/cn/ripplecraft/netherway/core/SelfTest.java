package cn.ripplecraft.netherway.core;

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
 * <p>运行：{@code java -cp <classes> cn.ripplecraft.netherway.core.SelfTest}
 */
public final class SelfTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        testPlatformDetect();
        testPlatformResourcePaths();
        testPlatformEmulationFallback();
        testBinaryStoreExtractsAndCleans();
        testBinaryStoreEmulationFallback();
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
        testCredentialsRendezvousAddress();
        testServeCommandRendezvous();
        testTlsRecordDetection();
        testTimingsNormalization();
        testCredAwareWaitWindows();
        testUpgradeGivesUpWithoutBinary();
        testUpgradeIgnoresDuplicateCredentials();
        testAddresslessCredentialDoesNotClobberCache();
        testStaleWorkerCannotOverrideReset();
        testCredentialCacheRoundTrip();
        testCredentialCacheKeepsMostRecent();
        testCredentialCacheSkipsCorrupt();
        testCredentialCacheMigratesLegacyKey();
        testCredentialCachePrunes();
        testBuildCommandBindPort();
        testAdoptDirectConnection();
        testUpgradeReusesWarmTunnel();
        testWarmupListenerLifecycle();
        testTokenIssuer();
        testCredentialsWithExtraParams();
        testServeCommandMetaToken();
        testServeCommandProxyProtocol();
        testProxyProtocolV1();
        testProxyProtocolV2();
        testProxyProtocolSniff();
        testProxyProtocolInvalid();
        testServerCandidatesFromServerList();
        testServerCandidatesConfigFirst();
        testServerCandidatesCap();
        testPrefetchStoresAllServices();
        testPreauthFrameRoundTrip();
        testPreauthFrameSniff();
        testPreauthFrameIncremental();
        testPreauthFrameRejects();
        testPreauthIdentityValidation();
        testCredentialV2StillDecodes();
        testWarmupRetryBackoff();
        testTelemetryQualityWindow();
        testTelemetryFailureCodeContract();
        testTelemetryCollector();
        testTelemetryConcurrentFlush();
        testTelemetryBackendNatDimensions();
        testAgentEventNatField();
        testServeTelemetryLifecycle();
        testUpgradeTelemetryLanding();
        testUpgradeTelemetryStaleRedirect();
        testUpgradeTelemetryRedirectFailure();

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
        check("Linux/aarch64 别名", Platform.detect("Linux", "aarch64").toString().equals("linux-arm64"));

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
                win.resourcePath().equals("natives/windows-amd64/netherway.exe"));
        check("Windows 可执行名带 .exe",
                win.executableName().equals("netherway-windows-amd64.exe"));
        check("Windows 判定", win.isWindows());

        Platform mac = Platform.detect("Mac OS X", "arm64");
        check("macOS 资源路径无后缀",
                mac.resourcePath().equals("natives/macos-arm64/netherway"));
        check("macOS 非 Windows", !mac.isWindows());

        Platform linuxArm64 = Platform.detect("Linux", "aarch64");
        check("Linux ARM64 资源路径",
                linuxArm64.resourcePath().equals("natives/linux-arm64/netherway"));
    }

    private static void testPlatformEmulationFallback() {
        check("Windows ARM64 兜底到 amd64",
                "windows-amd64".equals(String.valueOf(
                        Platform.detect("Windows 11", "arm64").emulationFallback())));
        check("Apple Silicon 兜底到 amd64（Rosetta）",
                "macos-amd64".equals(String.valueOf(
                        Platform.detect("Mac OS X", "aarch64").emulationFallback())));
        check("Linux ARM64 没有可假定的转译层",
                Platform.detect("Linux", "aarch64").emulationFallback() == null);
        check("amd64 平台不反向兜底",
                Platform.detect("Windows 10", "amd64").emulationFallback() == null);
    }

    // ---------- BinaryStore ----------

    /** 用内存 map 假扮 mod jar 的资源目录。 */
    private static ClassLoader fakeJar(final java.util.Map<String, byte[]> resources) {
        return new ClassLoader(null) {
            @Override
            public java.io.InputStream getResourceAsStream(String name) {
                byte[] data = resources.get(name);
                return data == null ? null : new java.io.ByteArrayInputStream(data);
            }
        };
    }

    private static void testBinaryStoreExtractsAndCleans() throws Exception {
        Path dir = Files.createTempDirectory("netherway-bin");
        // 旧版本残留：本平台旧摘要、别的平台、崩溃留下的陈旧半成品
        Path oldSelf = dir.resolve("netherway-windows-amd64-000000000000.exe");
        Path oldOther = dir.resolve("netherway-macos-arm64-0123456789ab");
        Path oldPart = dir.resolve("netherway-4711.part");
        Files.write(oldSelf, new byte[] {1});
        Files.write(oldOther, new byte[] {2});
        Files.write(oldPart, new byte[] {3});
        Files.setLastModifiedTime(oldPart, java.nio.file.attribute.FileTime.fromMillis(
                System.currentTimeMillis() - 2L * 60L * 60L * 1000L));
        // 不得触碰的邻居：日志、凭证目录、非摘要命名的文件、新鲜半成品
        Path log = dir.resolve("tunnel.log");
        Path cred = dir.resolve("credentials").resolve("keep.bin");
        Path manual = dir.resolve("netherway-windows-amd64.exe");
        Path freshPart = dir.resolve("netherway-8080.part");
        Files.write(log, new byte[] {4});
        Files.createDirectories(cred.getParent());
        Files.write(cred, new byte[] {5});
        Files.write(manual, new byte[] {6});
        Files.write(freshPart, new byte[] {7});

        byte[] agent = "netherway-agent-payload".getBytes("UTF-8");
        java.util.Map<String, byte[]> jar = new java.util.HashMap<String, byte[]>();
        jar.put("natives/windows-amd64/netherway.exe", agent);

        BinaryStore store = new BinaryStore(
                dir, Platform.detect("Windows 10", "amd64"), fakeJar(jar));
        Path exe = store.ensureExtracted();

        check("释放文件名带平台与摘要", exe.getFileName().toString()
                .matches("netherway-windows-amd64-[0-9a-f]{12}\\.exe"));
        check("释放内容逐字节一致",
                java.util.Arrays.equals(Files.readAllBytes(exe), agent));
        check("本平台旧摘要被清掉", !Files.exists(oldSelf));
        check("其它平台残留一并清掉", !Files.exists(oldOther));
        check("陈旧半成品被清掉", !Files.exists(oldPart));
        check("日志不受影响", Files.exists(log));
        check("凭证目录不受影响", Files.exists(cred));
        check("非摘要命名的文件不受影响", Files.exists(manual));
        check("新鲜半成品保留（可能有并发提取）", Files.exists(freshPart));

        check("重复调用命中同一路径", store.ensureExtracted().equals(exe) && Files.exists(exe));
    }

    private static void testBinaryStoreEmulationFallback() throws Exception {
        byte[] agent = "amd64-only-payload".getBytes("UTF-8");
        java.util.Map<String, byte[]> jar = new java.util.HashMap<String, byte[]>();
        jar.put("natives/windows-amd64/netherway.exe", agent);

        Path exe = new BinaryStore(Files.createTempDirectory("netherway-bin-arm"),
                Platform.detect("Windows 11", "aarch64"), fakeJar(jar)).ensureExtracted();
        check("Windows ARM64 落到 amd64 转译二进制",
                exe.getFileName().toString().startsWith("netherway-windows-amd64-"));

        boolean threw = false;
        try {
            new BinaryStore(Files.createTempDirectory("netherway-bin-larm"),
                    Platform.detect("Linux", "aarch64"), fakeJar(jar)).ensureExtracted();
        } catch (java.io.IOException e) {
            threw = e.getMessage().contains("linux-arm64");
        }
        check("Linux ARM64 无兜底应报错并点名平台", threw);
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

        boolean strictThrew = false;
        try {
            Json.parseObject("{\"a\":[1]}");
        } catch (IllegalArgumentException e) {
            strictThrew = true;
        }
        check("嵌套数组应报错", strictThrew);
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

        AgentEvent failed = AgentEvent.parse("{\"event\":\"failed\","
                + "\"failureStage\":\"probe\",\"failureCode\":\"ready_probe_timeout\","
                + "\"reason\":\"打洞超时\"}");
        check("识别 failed", failed != null && failed.type() == AgentEvent.Type.FAILED);
        check("解析失败阶段", "probe".equals(failed.failureStage()));
        check("解析失败码", "ready_probe_timeout".equals(failed.failureCode()));
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

        // 老 agent 只有自由文本 reason；新增字段缺失时仍应正常解析
        AgentEvent legacy = AgentEvent.parse("{\"event\":\"failed\",\"reason\":\"旧版失败\"}");
        check("兼容旧 agent 失败事件", legacy != null
                && legacy.failureStage() == null && legacy.failureCode() == null);

        AgentEvent local = AgentEvent.failed("start", "agent_start_failed", "无法启动");
        check("构造结构化失败事件", "start".equals(local.failureStage())
                && "agent_start_failed".equals(local.failureCode())
                && "无法启动".equals(local.reason()));
        AgentEvent legacyLocal = AgentEvent.failed("旧式本地失败");
        check("兼容旧式失败构造", legacyLocal.failureStage() == null
                && legacyLocal.failureCode() == null);
    }

    // ---------- Credentials ----------

    private static void testCredentialsRoundTrip() throws Exception {
        Credentials orig = Credentials.frpXtcp("203.0.113.10", 7000, "tok3n",
                "stun.miwifi.com:3478", "survival", "s3cr3t", 15000);
        Credentials back = Credentials.decode(orig.encode());

        check("往返 backend", Credentials.BACKEND_FRP_XTCP.equals(back.backendId()));
        check("往返 server 参数", "203.0.113.10".equals(back.param("server")));
        check("往返 serverPort 参数", "7000".equals(back.param("serverPort")));
        check("往返 token 参数", "tok3n".equals(back.param("token")));
        check("往返 stun 参数", "stun.miwifi.com:3478".equals(back.param("stun")));
        check("往返 room", back.room().equals("survival"));
        check("往返 secret 参数", "s3cr3t".equals(back.param("secret")));
        check("往返 timeout", back.punchTimeoutMs() == 15000);
        check("参数保持下发顺序",
                new ArrayList<String>(back.params().keySet()).get(0).equals("server"));

        // 中文和特殊字符要能安全通过（writeUTF 是 modified UTF-8）
        Credentials cn = Credentials.frpXtcp("a.example.com", 1, "令牌", "s:1",
                "青金石小镇", "密钥#1", 0);
        Credentials cnBack = Credentials.decode(cn.encode());
        check("往返中文房间名", cnBack.room().equals("青金石小镇"));
        check("往返中文密钥", "密钥#1".equals(cnBack.param("secret")));

        Credentials withOrigin = orig.withOrigin("Play.Example.COM", 25566);
        Credentials originBack = Credentials.decode(withOrigin.encode());
        check("往返凭证来源主机", originBack.hasOrigin()
                && "play.example.com".equals(originBack.originHost()));
        check("往返凭证来源端口", originBack.originPort() == 25566);
    }

    private static void testCredentialsGenericBackend() throws Exception {
        // 核心承诺：新增隧道方案时 core 无需任何改动。
        // 用一个 core 从未听说过的 backend 走一遍编解码验证这一点。
        java.util.Map<String, String> p = new java.util.LinkedHashMap<String, String>();
        p.put(Credentials.PARAM_ROOM, "survival");
        p.put("endpoint", "relay.example.com:443");
        p.put("auth", "k3y");
        Credentials back = Credentials.decode(new Credentials("hysteria2", p, 8000).encode());
        check("未知 backend 原样往返", back.backendId().equals("hysteria2"));
        check("未知参数原样往返", "relay.example.com:443".equals(back.param("endpoint")));
        check("未知 backend 也有房间名", back.room().equals("survival"));
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
        out.writeUTF("survival");
        out.writeUTF("sec");
        out.writeInt(3000);

        Credentials v1 = Credentials.decode(buf.toByteArray());
        check("v1 识别为 frp-xtcp", Credentials.BACKEND_FRP_XTCP.equals(v1.backendId()));
        check("v1 字段映射到参数", "1.2.3.4".equals(v1.param("server"))
                && "sec".equals(v1.param("secret")));
        check("v1 房间名", v1.room().equals("survival"));
        check("v1 超时", v1.punchTimeoutMs() == 3000);
    }

    private static void testCredentialsForwardCompat() throws Exception {
        // 未来版本在 v4 尾部追加字段时，当前客户端读已知前缀、忽略其余。
        byte[] v4 = Credentials.frpXtcp("h", 1, "t", "s:1", "survival", "k", 0)
                .withOrigin("play.example.com", 25565).encode();
        byte[] v5 = new byte[v4.length + 5];
        System.arraycopy(v4, 0, v5, 0, v4.length);
        v5[0] = 5;
        Credentials fut = Credentials.decode(v5);
        check("未来版本读已知前缀", fut.room().equals("survival"));
        check("未来版本参数完整", "t".equals(fut.param("token")));
        check("未来版本保留已知 origin", fut.hasOrigin()
                && "play.example.com".equals(fut.originHost()));

        // v3 的尾部是已废弃的 policy 表。玩家缓存里可能仍有这种凭证，
        // 必须完整跳过，而不能把 policy 错当成 v4 origin。
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream out = new java.io.DataOutputStream(buf);
        out.writeByte(3);
        out.writeUTF(Credentials.BACKEND_FRP_XTCP);
        out.writeInt(1234);
        out.writeShort(1);
        out.writeUTF(Credentials.PARAM_ROOM);
        out.writeUTF("legacy-room");
        out.writeShort(1);
        out.writeUTF("oldPolicy");
        out.writeUTF("ignored");
        out.flush();
        Credentials v3 = Credentials.decode(buf.toByteArray());
        check("v3 policy 尾部仍能解码", "legacy-room".equals(v3.room()));
        check("v3 policy 不会冒充 origin", !v3.hasOrigin());

        // 当前服务端也会 encode v4（origin 留空）。v4 故意保留零长度 policy
        // 表头，使上一代 decoder 能读完已知前缀并忽略 origin 后缀。
        check("上一代 decoder 可接受 v4 凭证", "survival".equals(
                decodeWithLegacyV3Layout(Credentials.frpXtcp(
                        "h", 1, "t", "s:1", "survival", "k", 0).encode())));
    }

    private static String decodeWithLegacyV3Layout(byte[] data) throws Exception {
        java.io.DataInputStream in = new java.io.DataInputStream(
                new java.io.ByteArrayInputStream(data));
        int version = in.readUnsignedByte();
        in.readUTF();
        in.readInt();
        int count = in.readUnsignedShort();
        String room = null;
        for (int i = 0; i < count; i++) {
            String key = in.readUTF();
            String value = in.readUTF();
            if (Credentials.PARAM_ROOM.equals(key)) {
                room = value;
            }
        }
        if (version >= 3 && in.available() >= 2) {
            int policies = in.readUnsignedShort();
            for (int i = 0; i < policies; i++) {
                in.readUTF();
                in.readUTF();
            }
        }
        return room;
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
        Credentials a = Credentials.frpXtcp("h1", 1, "t1", "s:1", "survival", "k1", 0);
        Credentials b = Credentials.frpXtcp("h2", 2, "t2", "s:2", "survival", "k2", 9);
        Credentials c = Credentials.frpXtcp("h1", 1, "t1", "s:1", "other", "k1", 0);
        check("同 backend 同房间去重键一致", a.dedupKey().equals(b.dedupKey()));
        check("不同房间去重键不同", !a.dedupKey().equals(c.dedupKey()));
        Credentials a1 = a.withOrigin("one.example.com", 25565);
        Credentials a2 = a.withOrigin("two.example.com", 25565);
        check("同 backend/room 的不同服务入口不再冲突",
                !a1.dedupKey().equals(a2.dedupKey()));
        check("来源主机大小写归一",
                a1.dedupKey().equals(a.withOrigin("ONE.EXAMPLE.COM", 25565).dedupKey()));
    }

    // ---------- AgentProcess 命令行 ----------

    private static void testBuildCommand() {
        Credentials cred = Credentials.frpXtcp("1.2.3.4", 7000, "tok",
                "stun.miwifi.com:3478", "survival", "sec", 0);
        List<String> cmd = AgentProcess.buildCommand(
                Paths.get("/tmp/netherway"), cred, Timings.defaults(),
                Paths.get("/tmp/tunnel.log"));

        check("首个参数是可执行文件", cmd.get(0).endsWith("netherway"));
        check("子命令是 tunnel", "tunnel".equals(cmd.get(1)));
        int at = cmd.indexOf("-backend");
        check("指定 backend", at >= 0 && Credentials.BACKEND_FRP_XTCP.equals(cmd.get(at + 1)));
        check("server 经 -O 传递", cmd.contains("-O") && cmd.contains("server=1.2.3.4"));
        check("room 经 -O 传递", cmd.contains("room=survival"));
        check("secret 经 -O 传递", cmd.contains("secret=sec"));
        check("默认超时 15s", cmd.contains("15.000"));
        check("固定开启 agent 详细日志", cmd.contains("-v"));
        int lf = cmd.indexOf("-log-file");
        check("传递日志文件路径", lf >= 0 && cmd.get(lf + 1).endsWith("tunnel.log"));

        // 服务端下发的超时应当覆盖客户端默认值
        Credentials override = Credentials.frpXtcp("1.2.3.4", 7000, "tok",
                "stun:1", "survival", "sec", 3000);
        List<String> cmd2 = AgentProcess.buildCommand(
                Paths.get("/tmp/netherway"), override, Timings.defaults(), null);
        check("服务端超时优先", cmd2.contains("3.000") && !cmd2.contains("15.000"));
        check("未指定日志文件则不传 -log-file", !cmd2.contains("-log-file"));
    }

    private static void testDescribeCommandMasksValues() {
        Credentials cred = Credentials.frpXtcp("203.0.113.7", 7000, "SUPER_TOKEN",
                "stun:1", "survival", "SUPER_SECRET", 0);
        String desc = AgentProcess.describeCommand(AgentProcess.buildCommand(
                Paths.get("/tmp/netherway"), cred, Timings.defaults(),
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
                UpgradeReport.upgraded("survival", 31, 1792).encode());
        check("成功回执往返", ok.isUpgraded());
        check("成功回执房间", "survival".equals(ok.room()));
        check("成功回执延迟", ok.rttMs() == 31);
        check("成功回执耗时", ok.elapsedMs() == 1792);
        check("成功回执无原因", ok.reason().isEmpty());

        UpgradeReport bad = UpgradeReport.decode(
                UpgradeReport.gaveUp("survival", "打洞超时").encode());
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
        byte[] v1 = UpgradeReport.upgraded("survival", 31, 1792).encode();
        byte[] extended = new byte[v1.length + 4];
        System.arraycopy(v1, 0, extended, 0, v1.length);
        extended[0] = 9; // 假装是版本 9，尾部多 4 字节新字段
        UpgradeReport r = UpgradeReport.decode(extended);
        check("高版本回执读已知前缀", r.isUpgraded() && "survival".equals(r.room()));

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

        List<String> cmd = ServeCommand.build(Paths.get("/srv/netherway"), params, 25570);
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

    private static void testCredentialsRendezvousAddress() throws Exception {
        Credentials viaRz = Credentials.frpXtcpViaRendezvous(
                "TOKEN", "stun.example.com:3478", "survival", "SECRET", 15000);
        check("会合点凭证不含 server", !viaRz.params().containsKey("server"));
        check("会合点凭证不含 serverPort", !viaRz.params().containsKey("serverPort"));
        check("会合点凭证自报缺地址", viaRz.needsRendezvousAddress());

        Credentials filled = viaRz.rendezvousAt("mc.example.com", 25565);
        check("补齐后 server 生效", "mc.example.com".equals(filled.params().get("server")));
        check("补齐后 serverPort 生效", "25565".equals(filled.params().get("serverPort")));
        check("补齐后不再缺地址", !filled.needsRendezvousAddress());
        check("补齐不影响其它参数", "SECRET".equals(filled.params().get("secret")));
        check("原对象不变（缺地址）", viaRz.needsRendezvousAddress());

        // 服务端明确指定了地址就以服务端为准——它可能有意指向别的入口
        Credentials explicit = Credentials.frpXtcp("frps.example.com", 7000, "T",
                "stun.example.com:3478", "survival", "S", 15000);
        check("经典凭证不缺地址", !explicit.needsRendezvousAddress());
        Credentials untouched = explicit.rendezvousAt("mc.example.com", 25565);
        check("已有地址不被覆盖",
                "frps.example.com".equals(untouched.params().get("server")));
        check("已有端口不被覆盖", "7000".equals(untouched.params().get("serverPort")));

        // 非法地址不该把凭证改坏
        check("空主机名不改动凭证", viaRz.rendezvousAt("", 25565).needsRendezvousAddress());
        check("越界端口不改动凭证",
                viaRz.rendezvousAt("mc.example.com", 70000).needsRendezvousAddress());
        check("零端口不改动凭证",
                viaRz.rendezvousAt("mc.example.com", 0).needsRendezvousAddress());

        // 别的 backend 的地址键名由它们自己的契约决定，这里不该乱猜
        java.util.Map<String, String> other = new java.util.LinkedHashMap<String, String>();
        other.put(Credentials.PARAM_ROOM, "survival");
        Credentials generic = new Credentials("some-other-backend", other, 15000);
        check("非 frp-xtcp 不报缺地址", !generic.needsRendezvousAddress());
        check("非 frp-xtcp 不被塞入 server",
                !generic.rendezvousAt("mc.example.com", 25565).params().containsKey("server"));

        // 编解码要能原样往返（少两个键不影响格式）
        Credentials back = Credentials.decode(viaRz.encode());
        check("会合点凭证编解码往返", back.needsRendezvousAddress()
                && "survival".equals(back.params().get(Credentials.PARAM_ROOM)));

        // withDefaultParams 的语义：只补空缺，不覆盖
        java.util.Map<String, String> d = new java.util.LinkedHashMap<String, String>();
        d.put("secret", "别的密钥");
        d.put("新键", "新值");
        Credentials merged = viaRz.withDefaultParams(d);
        check("withDefaultParams 不覆盖已有值",
                "SECRET".equals(merged.params().get("secret")));
        check("withDefaultParams 补上缺失键", "新值".equals(merged.params().get("新键")));
    }

    private static void testServeCommandRendezvous() {
        java.util.Map<String, String> params = new java.util.LinkedHashMap<String, String>();
        params.put("server", "frps.example.com");
        params.put("serverPort", "7000");
        params.put("token", "ROOM_TOKEN");
        params.put("room", "test");
        params.put("secret", "SUPER_SECRET");

        List<String> cmd = ServeCommand.build(Paths.get("/srv/netherway"), params, 25565,
                new ServeCommand.Options().rendezvousPort(41234).signingKey("SIGNING"));

        int rz = cmd.indexOf("-rendezvous");
        check("会合点端口经 -rendezvous 传递", rz >= 0 && "41234".equals(cmd.get(rz + 1)));
        // 会合点在本机回环上，agent 自己就知道地址，传公网 frps 的地址只会误导
        check("内嵌会合点模式不传 -server", !cmd.contains("-server"));
        check("内嵌会合点模式不传 -server-port", !cmd.contains("-server-port"));
        // token 仍要传：它与下发凭证同源，玩家拿着它登录内嵌会合点
        int token = cmd.indexOf("-token");
        check("内嵌会合点模式仍传 token", token >= 0 && "ROOM_TOKEN".equals(cmd.get(token + 1)));
        int key = cmd.indexOf("-signing-key");
        check("签发密钥经 -signing-key 传递", key >= 0 && "SIGNING".equals(cmd.get(key + 1)));

        String desc = ServeCommand.describe(cmd);
        check("serve 描述不含签发密钥值", !desc.contains("SIGNING"));
        check("serve 描述保留会合点端口", desc.contains("-rendezvous 41234"));

        // 不启用时一切照旧
        List<String> off = ServeCommand.build(Paths.get("/srv/netherway"), params, 25565,
                new ServeCommand.Options().signingKey("SIGNING"));
        check("未启用会合点时不传 -rendezvous", !off.contains("-rendezvous"));
        check("未启用会合点时不传 -signing-key", !off.contains("-signing-key"));
        check("未启用会合点时仍传 -server", off.contains("-server"));
    }

    private static void testTlsRecordDetection() {
        // frp 控制通道实测首字节：16 03 01 ...（TLS ClientHello）
        byte[] tls = {0x16, 0x03, 0x01, 0x00, 0x2f};
        check("TLS 握手被认出", Boolean.TRUE.equals(TlsRecord.looksLikeHandshake(tls, tls.length)));

        // 同一个端口上的其它流量都必须被排除，否则会被错误地转给会合点
        byte[] mcModern = {0x10, 0x00, 0x2f};
        check("MC 现代握手不被误判",
                Boolean.FALSE.equals(TlsRecord.looksLikeHandshake(mcModern, mcModern.length)));
        byte[] legacy = {(byte) 0xFE, 0x01};
        check("MC legacy ping 不被误判",
                Boolean.FALSE.equals(TlsRecord.looksLikeHandshake(legacy, legacy.length)));
        byte[] proxyV1 = {'P', 'R', 'O', 'X', 'Y'};
        check("PROXY v1 头不被误判",
                Boolean.FALSE.equals(TlsRecord.looksLikeHandshake(proxyV1, proxyV1.length)));
        byte[] proxyV2 = {0x0D, 0x0A, 0x0D, 0x0A};
        check("PROXY v2 头不被误判",
                Boolean.FALSE.equals(TlsRecord.looksLikeHandshake(proxyV2, proxyV2.length)));
        byte[] nway = {'N', 'W', 'A', 'Y'};
        check("预认证帧不被误判",
                Boolean.FALSE.equals(TlsRecord.looksLikeHandshake(nway, nway.length)));

        // 三态：字节不够时必须回 null，而不是猜
        check("零字节时不下定论", TlsRecord.looksLikeHandshake(new byte[0], 0) == null);
        byte[] one = {0x16};
        check("只有首字节时不下定论", TlsRecord.looksLikeHandshake(one, 1) == null);
        // 首字节就不对的话不必再等第二个字节
        byte[] oneBad = {0x17};
        check("首字节即可否定时立刻下定论",
                Boolean.FALSE.equals(TlsRecord.looksLikeHandshake(oneBad, 1)));
        // 记录类型对但主版本不对（比如 SSLv2 或畸形流量）
        byte[] wrongVersion = {0x16, 0x02};
        check("记录类型对但版本不对时否定",
                Boolean.FALSE.equals(TlsRecord.looksLikeHandshake(wrongVersion, 2)));

        // len 是有效长度，不是数组长度：缓冲区通常比已读字节大
        byte[] over = new byte[16];
        over[0] = 0x16;
        check("只看 len 以内的字节", TlsRecord.looksLikeHandshake(over, 1) == null);
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

    /**
     * 等待窗口与 agent 的 -timeout 必须同源（凭证下发值优先）。
     * 2026-08-10 实测过脱节的代价：服务端下发 1 小时，agent 拿到了
     * -timeout 3600，mod 却按本地 20 秒把它掐掉——HardNAT 常态要两轮
     * 打洞，第二轮根本来不及开始。
     */
    private static void testCredAwareWaitWindows() {
        Timings t = new Timings(15000, 250, 2000, 5000).normalized();

        // 凭证下发值优先；未下发（<=0）回退本地配置
        check("凭证超时优先", t.punchTimeoutMs(3600000) == 3600000L);
        check("未下发回退本地", t.punchTimeoutMs(0) == 15000L);
        check("负值同样回退本地", t.punchTimeoutMs(-1) == 15000L);
        check("凭证版等待窗口 = 凭证超时 + 余量",
                t.outcomeWaitMs(3600000) == 3600000L + t.startupGraceMs());
        check("未下发时两个版本一致", t.outcomeWaitMs(0) == t.outcomeWaitMs());

        // 与 buildCommand 组装的 -timeout 同源：凭证说 1 小时，
        // 命令行就该是 3600 秒，而等待窗口必须比它更长
        Credentials cred = Credentials.frpXtcp("1.2.3.4", 7000, "tok",
                "stun:1", "survival", "sec", 3600000);
        List<String> cmd = AgentProcess.buildCommand(
                Paths.get("/tmp/netherway"), cred, t, null);
        int at = cmd.indexOf("-timeout");
        check("命令行超时取凭证值", at >= 0 && "3600.000".equals(cmd.get(at + 1)));
        check("等待窗口长于 agent 预算",
                t.outcomeWaitMs(cred.punchTimeoutMs()) > 3600000L);

        // 未在打洞时，预热公布的让路界退回本地配置的窗口
        WarmupController idle = new WarmupController(null, null, t, null, 0, null);
        check("预热闲置时公布本地窗口", idle.punchWaitBoundMs() == t.outcomeWaitMs());
    }

    // ---------- UpgradeController ----------

    /** 记录调用的假 bridge，避免测试触碰真实游戏。竞态测试会匿名子类化它加闩锁。 */
    private static class FakeBridge implements ClientBridge {
        final List<String> logs = new ArrayList<String>();
        final List<String> connects = new ArrayList<String>();
        final List<byte[]> reports = new ArrayList<byte[]>();
        final CountDownLatch settled = new CountDownLatch(1);
        final CountDownLatch reportSent = new CountDownLatch(1);
        private final Path dir;
        /** 测试可改：模拟「玩家正连着哪台服务器」，null 表示推导不出来。 */
        ServerCandidates.Address currentServer =
                ServerCandidates.Address.of("mc.example.com", 25565);

        FakeBridge(Path dir) {
            this.dir = dir;
        }

        @Override
        public ServerCandidates.Address currentServerAddress() {
            return currentServer;
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
            reportSent.countDown();
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
        Path tmp = Files.createTempDirectory("netherway-test");
        FakeBridge bridge = new FakeBridge(tmp);
        UpgradeController c = new UpgradeController(bridge, Timings.defaults());

        Credentials cred = Credentials.frpXtcp("127.0.0.1", 7000, "t",
                "stun:1", "room-a", "k", 1000);
        check("首次凭证启动升级", c.onCredentials(cred));

        // jar 里没有 natives 资源，应当迅速放弃而不是卡住等超时
        check("在超时前就放弃", bridge.settled.await(10, TimeUnit.SECONDS));
        check("终态是 GAVE_UP", c.state() == UpgradeController.State.GAVE_UP);
        check("没有触发连接切换", bridge.connects.isEmpty());

        // 失败时应当把结果回执发给服务端，让服主在自己的日志里看到原因。
        // giveUp 先打「放弃直连」日志（唤醒上面的 settled）后发回执，
        // 必须等回执自己的闩锁，否则会赶在 worker 发出前就断言。
        byte[] payload;
        check("失败后回传了结果", bridge.reportSent.await(10, TimeUnit.SECONDS));
        synchronized (bridge.reports) {
            check("回执恰好一份", bridge.reports.size() == 1);
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
        Path tmp = Files.createTempDirectory("netherway-test2");
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

    /**
     * 回归：补不上会合点地址的凭证绝不能写缓存。缓存按房间同文件覆盖，
     * 残废版会把带地址的好凭证盖掉，下次启动预热直接瘫痪（2026-08-09 实测：
     * 切换后 currentServerData 被 loadWorld(null) 清空，重复下发的凭证
     * 推导不出地址，就这么把缓存污染了）。
     */
    private static void testAddresslessCredentialDoesNotClobberCache() throws Exception {
        Path tmp = Files.createTempDirectory("netherway-test-cachepoison");
        FakeBridge bridge = new FakeBridge(tmp);
        CredentialCache cache = new CredentialCache(tmp.resolve("credentials"));

        // 上一场留下的好凭证：带会合点地址
        Credentials viaRz = Credentials.frpXtcpViaRendezvous(
                "T", "stun:1", "room-rz", "S", 1000);
        cache.store(viaRz.rendezvousAt("mc.example.com", 25565));

        // 切换后的重复下发：此刻服务器地址推导不出来（模拟 ServerData 已被清）
        bridge.currentServer = null;
        UpgradeController c = new UpgradeController(bridge, Timings.defaults(), cache, null);
        c.onCredentials(viaRz);
        bridge.settled.await(10, TimeUnit.SECONDS);

        Credentials cached = cache.loadMostRecent();
        check("无地址凭证不覆盖缓存", cached != null && !cached.needsRendezvousAddress());
        check("缓存里仍是带地址的版本",
                cached != null && "mc.example.com".equals(cached.params().get("server")));
        c.shutdown();
    }

    /**
     * 竞态回归：PUNCHING 期间玩家真退出触发 shutdown() 复位后，还在跑的
     * 过期 worker 不得把状态覆写成 GAVE_UP——那会锁死本会话的直连升级，
     * 还会往服务端发一条假的失败回执。
     *
     * <p>用闩锁把 worker 卡在 {@code cacheDirectory()} 上，确定性地构造
     * 「shutdown 先于 worker 落败」的交织，不靠碰运气的 sleep。
     */
    private static void testStaleWorkerCannotOverrideReset() throws Exception {
        Path tmp = Files.createTempDirectory("netherway-test-race");
        final CountDownLatch workerEntered = new CountDownLatch(1);
        final CountDownLatch resetDone = new CountDownLatch(1);
        final CountDownLatch staleNoticed = new CountDownLatch(1);
        FakeBridge bridge = new FakeBridge(tmp) {
            @Override
            public Path cacheDirectory() {
                workerEntered.countDown();
                try {
                    // 卡住 worker，让测试线程有确定的窗口去 shutdown
                    resetDone.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return super.cacheDirectory();
            }

            @Override
            public void debug(String message) {
                super.debug(message);
                if (message.contains("忽略过期升级")) {
                    staleNoticed.countDown();
                }
            }
        };
        UpgradeController c = new UpgradeController(bridge, Timings.defaults());
        Credentials cred = Credentials.frpXtcp("127.0.0.1", 7000, "t",
                "stun:1", "room-race", "k", 1000);

        check("竞态：首次凭证启动升级", c.onCredentials(cred));
        check("竞态：worker 已进入升级流程", workerEntered.await(10, TimeUnit.SECONDS));

        // 玩家此刻真退出：复位到 IDLE，代际号递增
        c.shutdown();
        check("竞态：shutdown 后立即回到 IDLE",
                c.state() == UpgradeController.State.IDLE);

        // 放行 worker：它会因缺少 natives 而落败，但它已经过期
        resetDone.countDown();
        check("竞态：过期 worker 的放弃被识别并忽略",
                staleNoticed.await(10, TimeUnit.SECONDS));
        check("竞态：状态没有被过期 worker 覆写成 GAVE_UP",
                c.state() == UpgradeController.State.IDLE);
        synchronized (bridge.reports) {
            check("竞态：没有发出假的失败回执", bridge.reports.isEmpty());
        }

        // 复位后的会话必须还能正常升级——这正是本 bug 锁死的东西
        check("竞态：复位后同一凭证能重新启动升级", c.onCredentials(cred));
        check("竞态：新一轮升级正常落败", bridge.settled.await(10, TimeUnit.SECONDS));
        check("竞态：新一轮的终态是 GAVE_UP",
                c.state() == UpgradeController.State.GAVE_UP);
        check("竞态：新一轮的失败回执正常发出",
                bridge.reportSent.await(10, TimeUnit.SECONDS));
        c.shutdown();
    }

    // ---------- CredentialCache ----------

    private static Credentials sampleCred(String room, String secret) {
        return Credentials.frpXtcp("1.2.3.4", 7000, "tok", "stun:1", room, secret, 15000);
    }

    private static Credentials sampleCredAt(String room, String secret) {
        return sampleCred(room, secret).withOrigin("mc.example.com", 25565);
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
        Path dir = Files.createTempDirectory("netherway-cache").resolve("credentials");
        CredentialCache cache = new CredentialCache(dir);
        check("空缓存返回 null", cache.loadMostRecent() == null);

        cache.store(sampleCred("survival", "s1"));
        Credentials back = cache.loadMostRecent();
        check("缓存往返房间名", back != null && back.room().equals("survival"));
        check("缓存往返密钥", back != null && "s1".equals(back.param("secret")));

        // 同一房间重复缓存应覆盖同一个文件，而不是越攒越多
        cache.store(sampleCred("survival", "s2"));
        check("同房间覆盖后取到新值", "s2".equals(cache.loadMostRecent().param("secret")));
        check("同房间只留一个文件", listCredFiles(dir).size() == 1);
    }

    private static void testCredentialCacheKeepsMostRecent() throws Exception {
        Path dir = Files.createTempDirectory("netherway-cache2");
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
        Path dir = Files.createTempDirectory("netherway-cache3");
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

    private static void testCredentialCacheMigratesLegacyKey() throws Exception {
        Path dir = Files.createTempDirectory("netherway-cache-migrate");
        CredentialCache cache = new CredentialCache(dir);
        cache.store(sampleCred("shared-room", "legacy"));
        check("迁移前只有 legacy 缓存", listCredFiles(dir).size() == 1);

        cache.store(sampleCred("shared-room", "one")
                .withOrigin("one.example.com", 25565));
        List<Credentials> migrated = cache.loadAll();
        check("带入口凭证会移除同房间 legacy 缓存", migrated.size() == 1);
        check("迁移后保留入口信息", migrated.get(0).hasOrigin()
                && "one.example.com".equals(migrated.get(0).originHost()));

        cache.store(sampleCred("shared-room", "two")
                .withOrigin("two.example.com", 25566));
        check("同 backend/room 的第二台服务可并存", cache.loadAll().size() == 2);
    }

    private static void testCredentialCachePrunes() throws Exception {
        Path dir = Files.createTempDirectory("netherway-cache4");
        CredentialCache cache = new CredentialCache(dir);
        for (int i = 0; i < 36; i++) {
            cache.store(sampleCred("room-" + i, "k"));
            touch(dir, "room-" + i, 1000L * (i + 1));
        }
        check("超出上限的旧缓存被清理", listCredFiles(dir).size() <= 32);
        check("最新的房间仍在", cache.loadMostRecent().room().equals("room-35"));
    }

    // ---------- 预热与采认 ----------

    private static void testBuildCommandBindPort() {
        Credentials cred = sampleCred("survival", "sec");
        List<String> cmd = AgentProcess.buildCommand(
                Paths.get("/tmp/netherway"), cred, Timings.defaults(), null, 25595);
        int at = cmd.indexOf("-port");
        check("指定预热端口经 -port 传递", at >= 0 && "25595".equals(cmd.get(at + 1)));

        List<String> auto = AgentProcess.buildCommand(
                Paths.get("/tmp/netherway"), cred, Timings.defaults(), null);
        check("未指定端口则不传 -port", !auto.contains("-port"));
    }

    private static void testAdoptDirectConnection() throws Exception {
        Path tmp = Files.createTempDirectory("netherway-adopt");
        FakeBridge bridge = new FakeBridge(tmp);
        UpgradeController c = new UpgradeController(bridge, Timings.defaults());

        Credentials cred = sampleCredAt("room-adopt", "k");
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
        Path tmp = Files.createTempDirectory("netherway-warm");
        FakeBridge bridge = new FakeBridge(tmp);
        WarmupController warmup = new WarmupController(bridge,
                new CredentialCache(tmp.resolve("credentials")),
                Timings.defaults(), null, 0, null);
        Credentials cred = sampleCredAt("room-warm", "k");
        Credentials second = sampleCred("room-warm", "k")
                .withOrigin("second.example.com", 25565);
        AgentEvent ready = AgentEvent.parse(
                "{\"event\":\"ready\",\"port\":25595,\"rttMs\":31,\"elapsedMs\":1792}");

        check("未就绪时查询端口为 null", warmup.readyPort(cred.dedupKey()) == null);
        warmup.injectReadyForTest(cred, ready);
        warmup.injectReadyForTest(second, AgentEvent.parse(
                "{\"event\":\"ready\",\"port\":25596,\"rttMs\":45,\"elapsedMs\":2000}"));
        check("就绪后按去重键查到端口",
                Integer.valueOf(25595).equals(warmup.readyPort(cred.dedupKey())));
        check("其他房间查不到", warmup.readyPort("frp-xtcp:other") == null);
        check("按端口反查得到凭证", warmup.credentialsForPort(25595) == cred);
        check("多服务就绪状态同时保留",
                Integer.valueOf(25596).equals(warmup.readyPort(second.dedupKey()))
                        && warmup.credentialsForPort(25596) == second);
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

    private static void testWarmupListenerLifecycle() throws Exception {
        Path tmp = Files.createTempDirectory("netherway-warm-listener");
        final List<String> lifecycle = new ArrayList<String>();
        WarmupController.Listener listener = new WarmupController.Listener() {
            @Override
            public void onTunnelStarting(Credentials cred, int port) {
                lifecycle.add("starting:" + port);
            }

            @Override
            public void onTunnelReady(Credentials cred, AgentEvent event) {
                lifecycle.add("ready:" + event.port());
            }

            @Override
            public void onTunnelClosed(Credentials cred, int port) {
                lifecycle.add("closed:" + port);
            }
        };
        WarmupController warmup = new WarmupController(new FakeBridge(tmp),
                new CredentialCache(tmp.resolve("credentials")), Timings.defaults(),
                listener, 0, null);
        Credentials cred = sampleCredAt("room-listener", "key");
        warmup.injectReadyForTest(cred, AgentEvent.parse(
                "{\"event\":\"ready\",\"port\":25595}"));
        check("预热 READY 通知平台层", lifecycle.contains("ready:25595"));
        warmup.shutdown();
        check("预热关闭通知平台层撤销入口", lifecycle.contains("closed:25595"));
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
        Credentials base = sampleCred("survival", "sec");
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

        List<String> plain = ServeCommand.build(Paths.get("/srv/netherway"), params, 25570);
        check("未配置静态令牌则不传旗标", !plain.contains("-meta-token"));

        List<String> cmd = ServeCommand.build(Paths.get("/srv/netherway"), params, 25570,
                new ServeCommand.Options().metaToken("SERVE_STATIC"));
        int at = cmd.indexOf("-meta-token");
        check("静态令牌经 -meta-token 传递", at >= 0 && "SERVE_STATIC".equals(cmd.get(at + 1)));
        check("serve 描述抹掉静态令牌值",
                !ServeCommand.describe(cmd).contains("SERVE_STATIC"));
    }

    private static void testServeCommandProxyProtocol() {
        java.util.Map<String, String> params = new java.util.LinkedHashMap<String, String>();
        params.put("server", "frps.example.com");
        params.put("room", "test");

        List<String> plain = ServeCommand.build(Paths.get("/srv/netherway"), params, 25570);
        check("未配置 PROXY protocol 则不传旗标", !plain.contains("-proxy-protocol"));

        List<String> cmd = ServeCommand.build(Paths.get("/srv/netherway"), params, 25570,
                new ServeCommand.Options().proxyProtocol("v2"));
        int at = cmd.indexOf("-proxy-protocol");
        check("PROXY protocol 版本经 -proxy-protocol 传递",
                at >= 0 && "v2".equals(cmd.get(at + 1)));
        check("只给静态令牌时不带出 PROXY 旗标",
                !ServeCommand.build(Paths.get("/srv/netherway"), params, 25570,
                        new ServeCommand.Options().metaToken("T"))
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

    // ---------- 凭证预取（会话/候选推导/命令行） ----------

    private static void testServerCandidatesFromServerList() {
        List<ServerCandidates.Address> got = ServerCandidates.build(null,
                java.util.Arrays.asList(
                        "Play.Example.COM:25565",   // 大小写归一
                        "play.example.com",         // 补默认端口后与上一条相同，去重
                        "127.0.0.1:25595",          // 回环（本 mod 的直连条目）跳过
                        "localhost",                // 回环别名跳过
                        "[2001:db8::1]:25565",      // 带方括号的 IPv6 跳过
                        "2001:db8::7",              // 裸 IPv6 跳过
                        "  ",                       // 空白跳过
                        "bad.example.com:70000",    // 端口越界跳过
                        "relay.example.net:20001"));
        check("列表里筛出两个候选", got.size() == 2);
        check("主机名归一为小写", got.get(0).host.equals("play.example.com"));
        check("不写端口时补 25565", got.get(0).port == ServerCandidates.DEFAULT_PORT);
        check("保持 server.dat 顺序并保留显式端口",
                got.get(1).host.equals("relay.example.net") && got.get(1).port == 20001);
    }

    private static void testServerCandidatesConfigFirst() {
        List<ServerCandidates.Address> got = ServerCandidates.build(
                new String[] {"pack.example.com:25566"},
                java.util.Arrays.asList("play.example.com:25565"));
        check("cfg 预置的地址排第一", got.get(0).host.equals("pack.example.com")
                && got.get(0).port == 25566);
        check("server.dat 条目紧随其后", got.get(1).host.equals("play.example.com"));
        check("cfg 地址可以不写端口", ServerCandidates.build(
                new String[] {"pack.example.com"}, null).get(0).port == 25565);
    }

    private static void testServerCandidatesCap() {
        List<String> many = new ArrayList<String>();
        for (int i = 0; i < 20; i++) {
            many.add("host" + i + ".example.com:25565");
        }
        check("候选数量有上限", ServerCandidates.build(null, many).size() == 8);
        // 上限是总数：cfg 地址排在前面，挤掉的是 server.dat 里靠后的猜测
        List<ServerCandidates.Address> withCfg = ServerCandidates.build(
                new String[] {"cfg.example.com"}, many);
        check("cfg 地址排第一", withCfg.get(0).host.equals("cfg.example.com"));
        check("cfg 地址计入总上限", withCfg.size() == 8);
        // cfg 自己就超过上限时一条都不能丢：那是部署者的明确指定
        String[] manyCfg = new String[12];
        for (int i = 0; i < manyCfg.length; i++) {
            manyCfg[i] = "cfg" + i + ".example.com";
        }
        check("cfg 地址永不被上限截断",
                ServerCandidates.build(manyCfg, many).size() == 12);
    }

    private static void testPrefetchStoresAllServices() throws Exception {
        Path tmp = Files.createTempDirectory("netherway-prefetch-many");
        FakeBridge bridge = new FakeBridge(tmp);
        final List<ServerCandidates.Address> candidates = ServerCandidates.build(
                new String[] {"one.example.com", "two.example.com:25566", "silent.example.com"},
                null);
        Prefetcher prefetcher = new Prefetcher(bridge,
                SessionIdentity.of("Alice", "069a79f444e94726a5befca90e38aaf5"),
                candidates, Timings.defaults().withPrefetchTimeout(1000L),
                cn.ripplecraft.netherway.core.telemetry.QualitySummary.Source.CONFIG,
                cn.ripplecraft.netherway.core.telemetry.QualityObserver.NOOP,
                new Prefetcher.Fetcher() {
                    @Override
                    public Credentials fetch(ServerCandidates.Address addr, SessionIdentity id,
                                             int timeoutMs) throws java.io.IOException {
                        if (addr.host.startsWith("silent")) {
                            // EOFException 等 IOException 可以没有 message；日志不能再落成 null。
                            throw new java.io.IOException();
                        }
                        // 两台独立服务刻意使用同一 backend/room，只靠 origin 分隔。
                        return sampleCred("shared-room", addr.host);
                    }
                });
        CredentialCache cache = new CredentialCache(tmp.resolve("credentials"));

        check("多服务预取至少一份成功", prefetcher.refresh(cache));
        List<Credentials> all = cache.loadAll();
        check("预取保留所有成功服务", all.size() == 2);
        java.util.Set<String> origins = new java.util.HashSet<String>();
        java.util.Set<String> keys = new java.util.HashSet<String>();
        for (Credentials cred : all) {
            origins.add(cred.originHost() + ":" + cred.originPort());
            keys.add(cred.dedupKey());
        }
        check("每份预取凭证记住自己的 MC 入口",
                origins.contains("one.example.com:25565")
                        && origins.contains("two.example.com:25566"));
        check("同 backend/room 的两台服务拥有独立缓存键", keys.size() == 2);
        check("无 message 的预取异常仍记录异常类型", bridge.logs.contains(
                "DEBUG 向 silent.example.com:25565 预取凭证未成功: IOException"));
    }

    private static void testPreauthFrameRoundTrip() throws Exception {
        byte[] payload = PreauthProtocol.encodeIdentity("Alice",
                "069a79f4-44e9-4726-a5be-fca90e38aaf5");
        byte[] frame = PreauthProtocol.encodeRequest(PreauthProtocol.OP_REQUEST, payload);
        // 帧头布局是 mod 新旧版本之间的线上契约，逐字节钉住
        check("帧以 NWAY 开头", frame[0] == 'N' && frame[1] == 'W'
                && frame[2] == 'A' && frame[3] == 'Y');
        check("第 5 字节是协议版本", (frame[4] & 0xFF) == PreauthProtocol.VERSION);
        check("第 6 字节是操作码", (frame[5] & 0xFF) == PreauthProtocol.OP_REQUEST);
        check("payload 长度大端写在第 7-8 字节",
                (((frame[6] & 0xFF) << 8) | (frame[7] & 0xFF)) == payload.length);

        PreauthProtocol.Request req = PreauthProtocol.readRequest(frame, frame.length);
        check("解出的帧长度与原帧一致", req.frameLength() == frame.length);
        String[] id = PreauthProtocol.decodeIdentity(req.payload);
        check("身份往返保真", id[0].equals("Alice")
                && id[1].equals("069a79f4-44e9-4726-a5be-fca90e38aaf5"));
    }

    private static void testPreauthFrameSniff() {
        // MC 现代握手：首字节是包长 VarInt，第 2 字节是包 id 0x00
        byte[] mcHandshake = {0x10, 0x00, 0x2F, 0x09};
        check("MC 握手不被当成预认证帧", Boolean.FALSE.equals(
                PreauthProtocol.looksLikeFrame(mcHandshake, mcHandshake.length)));
        byte[] legacyPing = {(byte) 0xFE, 0x01};
        check("legacy ping 不被当成预认证帧", Boolean.FALSE.equals(
                PreauthProtocol.looksLikeFrame(legacyPing, legacyPing.length)));
        byte[] proxyV1 = {'P', 'R', 'O', 'X'};
        check("PROXY v1 不被当成预认证帧", Boolean.FALSE.equals(
                PreauthProtocol.looksLikeFrame(proxyV1, proxyV1.length)));
        byte[] proxyV2 = {0x0D, 0x0A, 0x0D, 0x0A};
        check("PROXY v2 不被当成预认证帧", Boolean.FALSE.equals(
                PreauthProtocol.looksLikeFrame(proxyV2, proxyV2.length)));
        // 首字节相同、第 2 字节才分叉的情况：必须等到第 2 字节才下定论
        byte[] justN = {'N'};
        check("只有 1 个字节时不下定论",
                PreauthProtocol.looksLikeFrame(justN, justN.length) == null);
        byte[] nButNotW = {'N', 0x00};
        check("第 2 字节不符即判定不是", Boolean.FALSE.equals(
                PreauthProtocol.looksLikeFrame(nButNotW, nButNotW.length)));
        check("魔数齐了即判定是", Boolean.TRUE.equals(
                PreauthProtocol.looksLikeFrame(PreauthProtocol.MAGIC,
                        PreauthProtocol.MAGIC.length)));
    }

    private static void testPreauthFrameIncremental() throws Exception {
        byte[] frame = PreauthProtocol.encodeRequest(PreauthProtocol.OP_REQUEST,
                PreauthProtocol.encodeIdentity("Bob", "069a79f444e94726a5befca90e38aaf5"));
        // 嗅探器是一个字节一个字节攒的：收全之前必须一直说「还不够」
        for (int n = 0; n < frame.length; n++) {
            check("收到 " + n + " 字节时还不成帧",
                    PreauthProtocol.readRequest(frame, n) == null);
        }
        check("收全即成帧", PreauthProtocol.readRequest(frame, frame.length) != null);
        // 粘包：后面跟着下一帧的字节不影响本帧解析
        byte[] withTail = new byte[frame.length + 5];
        System.arraycopy(frame, 0, withTail, 0, frame.length);
        PreauthProtocol.Request req = PreauthProtocol.readRequest(withTail, withTail.length);
        check("粘包时只吃掉本帧", req != null && req.frameLength() == frame.length);
    }

    private static void testPreauthFrameRejects() {
        boolean threw = false;
        try {
            byte[] notOurs = {'H', 'T', 'T', 'P', '/', '1', '.', '1'};
            PreauthProtocol.readRequest(notOurs, notOurs.length);
        } catch (java.io.IOException e) {
            threw = true;
        }
        check("非预认证帧直接报错", threw);

        // 声称的 payload 超限：必须在分配缓冲之前就拒绝
        byte[] huge = new byte[PreauthProtocol.REQUEST_HEADER_LEN];
        System.arraycopy(PreauthProtocol.MAGIC, 0, huge, 0, 4);
        huge[4] = (byte) PreauthProtocol.VERSION;
        huge[5] = (byte) PreauthProtocol.OP_REQUEST;
        huge[6] = (byte) 0xFF;
        huge[7] = (byte) 0xFF;
        boolean rejected = false;
        try {
            PreauthProtocol.readRequest(huge, huge.length);
        } catch (java.io.IOException e) {
            rejected = true;
        }
        check("payload 声称超限即拒绝", rejected);

        boolean tooBig = false;
        try {
            PreauthProtocol.encodeRequest(PreauthProtocol.OP_REQUEST,
                    new byte[PreauthProtocol.MAX_PAYLOAD + 1]);
        } catch (IllegalArgumentException e) {
            tooBig = true;
        }
        check("编码超限 payload 即拒绝", tooBig);
    }

    private static void testPreauthIdentityValidation() {
        check("正常身份通过", PreauthProtocol.validateIdentity("Alice",
                "069a79f4-44e9-4726-a5be-fca90e38aaf5").isEmpty());
        check("不带连字符的 uuid 也通过", PreauthProtocol.validateIdentity("Alice",
                "069a79f444e94726a5befca90e38aaf5").isEmpty());
        check("空用户名被拒", !PreauthProtocol.validateIdentity("",
                "069a79f444e94726a5befca90e38aaf5").isEmpty());
        // 换行会伪造日志行，必须从源头掐死
        check("含换行的用户名被拒", !PreauthProtocol.validateIdentity("Al\nice",
                "069a79f444e94726a5befca90e38aaf5").isEmpty());
        check("超长用户名被拒", !PreauthProtocol.validateIdentity(
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                "069a79f444e94726a5befca90e38aaf5").isEmpty());
        check("非 hex uuid 被拒",
                !PreauthProtocol.validateIdentity("Alice", "not-a-uuid").isEmpty());
        check("uuid 归一去连字符", PreauthProtocol.normalizeUuid(
                "069a79f4-44e9-4726-a5be-fca90e38aaf5")
                .equals("069a79f444e94726a5befca90e38aaf5"));
    }

    private static void testCredentialV2StillDecodes() throws Exception {
        // 手工拼一份 v2 凭证：老服务端仍会下发，玩家缓存目录里也躺着这种。
        // 本侧升到 v4 之后仍不能把它们当成损坏数据。
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream out = new java.io.DataOutputStream(buf);
        out.writeByte(2);
        out.writeUTF(Credentials.BACKEND_FRP_XTCP);
        out.writeInt(15_000);
        out.writeShort(2);
        out.writeUTF(Credentials.PARAM_ROOM);
        out.writeUTF("survival");
        out.writeUTF("secret");
        out.writeUTF("s3cret");
        out.flush();

        Credentials v2 = Credentials.decode(buf.toByteArray());
        check("v2 凭证仍能解码", v2.room().equals("survival"));
        check("v2 参数完整", v2.param("secret").equals("s3cret"));
    }

    private static void testWarmupRetryBackoff() {
        Timings t = Timings.defaults().withWarmupRetry(10_000L, 120_000L);
        check("首轮退避为初始值", t.warmupRetryDelayMs(0) == 10_000L);
        check("退避按倍数增长", t.warmupRetryDelayMs(1) == 20_000L
                && t.warmupRetryDelayMs(2) == 40_000L);
        check("退避封顶", t.warmupRetryDelayMs(4) == 120_000L
                && t.warmupRetryDelayMs(1000) == 120_000L);
        Timings zero = new Timings(0, 0, 0, 0).withWarmupRetry(0, 0)
                .withPrefetchTimeout(0).normalized();
        check("非正退避回填默认值", zero.warmupRetryDelayMs(0) == 10_000L
                && zero.warmupRetryDelayMs(1000) == 120_000L);
        check("非正预取超时回填默认值", zero.prefetchTimeoutMs() == 60_000L);
        check("配置的预取超时生效",
                Timings.defaults().withPrefetchTimeout(5_000L).prefetchTimeoutMs() == 5_000L);
    }

    // ---------- Telemetry ----------

    private static void testTelemetryFailureCodeContract() {
        check("遥测契约：Go start stage 对齐",
                cn.ripplecraft.netherway.core.telemetry.QualitySummary.FailureStage
                        .fromWire("start")
                        == cn.ripplecraft.netherway.core.telemetry.QualitySummary.FailureStage.START);
        check("遥测契约：Go backend stage 对齐",
                cn.ripplecraft.netherway.core.telemetry.QualitySummary.FailureStage
                        .fromWire("backend")
                        == cn.ripplecraft.netherway.core.telemetry.QualitySummary.FailureStage.BACKEND);
        check("遥测契约：Go probe stage 对齐",
                cn.ripplecraft.netherway.core.telemetry.QualitySummary.FailureStage
                        .fromWire("probe")
                        == cn.ripplecraft.netherway.core.telemetry.QualitySummary.FailureStage.PROBE);
        check("遥测契约：backend_unknown 对齐",
                cn.ripplecraft.netherway.core.telemetry.QualitySummary.FailureCode
                        .fromWire("backend_unknown")
                        == cn.ripplecraft.netherway.core.telemetry.QualitySummary.FailureCode.BACKEND_UNKNOWN);
        check("遥测契约：bind_port_failed 对齐",
                cn.ripplecraft.netherway.core.telemetry.QualitySummary.FailureCode
                        .fromWire("bind_port_failed")
                        == cn.ripplecraft.netherway.core.telemetry.QualitySummary.FailureCode.BIND_PORT_FAILED);
        check("遥测契约：backend_exited 对齐",
                cn.ripplecraft.netherway.core.telemetry.QualitySummary.FailureCode
                        .fromWire("backend_exited")
                        == cn.ripplecraft.netherway.core.telemetry.QualitySummary.FailureCode.BACKEND_EXITED);
        check("遥测契约：ready_probe_timeout 对齐",
                cn.ripplecraft.netherway.core.telemetry.QualitySummary.FailureCode
                        .fromWire("ready_probe_timeout")
                        == cn.ripplecraft.netherway.core.telemetry.QualitySummary.FailureCode.READY_PROBE_TIMEOUT);
        check("遥测契约：未知 stage 归一为 other",
                cn.ripplecraft.netherway.core.telemetry.QualitySummary.FailureStage
                        .fromWire("future_stage")
                        == cn.ripplecraft.netherway.core.telemetry.QualitySummary.FailureStage.OTHER);
        check("遥测契约：未知 code 归一为 other",
                cn.ripplecraft.netherway.core.telemetry.QualitySummary.FailureCode
                        .fromWire("future_code")
                        == cn.ripplecraft.netherway.core.telemetry.QualitySummary.FailureCode.OTHER);
    }

    private static void testTelemetryQualityWindow() {
        final long[] now = new long[1];
        cn.ripplecraft.netherway.core.telemetry.QualityWindow window =
                new cn.ripplecraft.netherway.core.telemetry.QualityWindow(
                        cn.ripplecraft.netherway.core.telemetry.QualitySummary.Path.WARMUP,
                        cn.ripplecraft.netherway.core.telemetry.QualitySummary.Source.COLD_AGENT,
                        new cn.ripplecraft.netherway.core.telemetry.QualityWindow.Clock() {
                            @Override
                            public long nanoTime() {
                                return now[0];
                            }
                        });
        cn.ripplecraft.netherway.core.telemetry.QualitySummary summary = null;
        for (int i = 0; i < 4; i++) {
            window.markAttempts(1);
            summary = window.failed(
                    cn.ripplecraft.netherway.core.telemetry.QualitySummary.Stage.ROUND_FINISHED,
                    cn.ripplecraft.netherway.core.telemetry.QualitySummary.FailureStage.PROBE,
                    cn.ripplecraft.netherway.core.telemetry.QualitySummary.FailureCode.READY_PROBE_TIMEOUT);
        }
        check("质量窗口：前四次失败不结算", summary == null);
        window.markAttempts(1);
        summary = window.failed(
                cn.ripplecraft.netherway.core.telemetry.QualitySummary.Stage.ROUND_FINISHED,
                cn.ripplecraft.netherway.core.telemetry.QualitySummary.FailureStage.PROBE,
                cn.ripplecraft.netherway.core.telemetry.QualitySummary.FailureCode.READY_PROBE_TIMEOUT);
        check("质量窗口：第五次真实尝试结算", summary != null);

        window.markAttempts(1);
        check("质量窗口：持续失败在六小时内被抑制", window.failed(
                cn.ripplecraft.netherway.core.telemetry.QualitySummary.Stage.ROUND_FINISHED,
                cn.ripplecraft.netherway.core.telemetry.QualitySummary.FailureStage.PROBE,
                cn.ripplecraft.netherway.core.telemetry.QualitySummary.FailureCode.READY_PROBE_TIMEOUT) == null);
        now[0] += TimeUnit.HOURS.toNanos(6L);
        window.markAttempts(1);
        check("质量窗口：持续失败满六小时再次结算", window.failed(
                cn.ripplecraft.netherway.core.telemetry.QualitySummary.Stage.ROUND_FINISHED,
                cn.ripplecraft.netherway.core.telemetry.QualitySummary.FailureStage.PROBE,
                cn.ripplecraft.netherway.core.telemetry.QualitySummary.FailureCode.READY_PROBE_TIMEOUT) != null);

        window.markAttempts(1);
        check("质量窗口：失败后的恢复成功立即结算", window.succeeded(
                cn.ripplecraft.netherway.core.telemetry.QualitySummary.Stage.TUNNEL_READY,
                1800L, 31L) != null);
        window.markAttempts(1);
        check("质量窗口：重复成功在六小时内被抑制", window.succeeded(
                cn.ripplecraft.netherway.core.telemetry.QualitySummary.Stage.TUNNEL_READY,
                1800L, 31L) == null);
        window.markAttempts(1);
        check("质量窗口：成功后的单次失败先留在窗口", window.failed(
                cn.ripplecraft.netherway.core.telemetry.QualitySummary.Stage.ROUND_FINISHED,
                cn.ripplecraft.netherway.core.telemetry.QualitySummary.FailureStage.BACKEND,
                cn.ripplecraft.netherway.core.telemetry.QualitySummary.FailureCode.BACKEND_EXITED) == null);
        window.markAttempts(1);
        check("质量窗口：有失败夹在中间时恢复成功不被抑制", window.succeeded(
                cn.ripplecraft.netherway.core.telemetry.QualitySummary.Stage.TUNNEL_READY,
                2200L, 40L) != null);

        final long[] age = new long[1];
        cn.ripplecraft.netherway.core.telemetry.QualityWindow aged =
                new cn.ripplecraft.netherway.core.telemetry.QualityWindow(
                        cn.ripplecraft.netherway.core.telemetry.QualitySummary.Path.PREFETCH,
                        cn.ripplecraft.netherway.core.telemetry.QualitySummary.Source.CONFIG,
                        new cn.ripplecraft.netherway.core.telemetry.QualityWindow.Clock() {
                            @Override
                            public long nanoTime() {
                                return age[0];
                            }
                        });
        aged.markAttempts(1);
        check("质量窗口：未满三十分钟不结算", aged.failed(
                cn.ripplecraft.netherway.core.telemetry.QualitySummary.Stage.ROUND_FINISHED,
                cn.ripplecraft.netherway.core.telemetry.QualitySummary.FailureStage.PREFETCH,
                cn.ripplecraft.netherway.core.telemetry.QualitySummary.FailureCode.PREFETCH_FAILED) == null);
        age[0] += TimeUnit.MINUTES.toNanos(30L);
        aged.markAttempts(1);
        check("质量窗口：满三十分钟即使不足五次也结算", aged.failed(
                cn.ripplecraft.netherway.core.telemetry.QualitySummary.Stage.ROUND_FINISHED,
                cn.ripplecraft.netherway.core.telemetry.QualitySummary.FailureStage.PREFETCH,
                cn.ripplecraft.netherway.core.telemetry.QualitySummary.FailureCode.PREFETCH_FAILED) != null);

        cn.ripplecraft.netherway.core.telemetry.QualityWindow dominant =
                new cn.ripplecraft.netherway.core.telemetry.QualityWindow(
                        cn.ripplecraft.netherway.core.telemetry.QualitySummary.Path.WARMUP,
                        cn.ripplecraft.netherway.core.telemetry.QualitySummary.Source.COLD_AGENT,
                        new cn.ripplecraft.netherway.core.telemetry.QualityWindow.Clock() {
                            @Override
                            public long nanoTime() {
                                return 0L;
                            }
                        });
        cn.ripplecraft.netherway.core.telemetry.QualitySummary dominantResult = null;
        for (int i = 0; i < 4; i++) {
            dominant.markAttempts(1);
            dominantResult = dominant.failed(
                    cn.ripplecraft.netherway.core.telemetry.QualitySummary.Stage.ROUND_FINISHED,
                    cn.ripplecraft.netherway.core.telemetry.QualitySummary.FailureStage.BACKEND,
                    cn.ripplecraft.netherway.core.telemetry.QualitySummary.FailureCode.BACKEND_EXITED);
        }
        dominant.markAttempts(1);
        dominantResult = dominant.failed(
                cn.ripplecraft.netherway.core.telemetry.QualitySummary.Stage.ROUND_FINISHED,
                cn.ripplecraft.netherway.core.telemetry.QualitySummary.FailureStage.PROBE,
                cn.ripplecraft.netherway.core.telemetry.QualitySummary.FailureCode.READY_PROBE_TIMEOUT);
        cn.ripplecraft.netherway.core.telemetry.TelemetryCollector dominantCollector =
                new cn.ripplecraft.netherway.core.telemetry.TelemetryCollector(
                        cn.ripplecraft.netherway.core.telemetry.TelemetryConfig.enabled(true),
                        new cn.ripplecraft.netherway.core.telemetry.TelemetryEnvironment(
                                "test", "1.7.10", "8", "linux", "amd64",
                                cn.ripplecraft.netherway.core.telemetry.TelemetryEnvironment.Role.CLIENT));
        dominantCollector.record(dominantResult);
        String dominantPayload = dominantCollector.previewPayload();
        check("质量窗口：按窗口主要失败码结算而非最后一次",
                dominantPayload.contains("\"failureCode\":\"backend_exited\"")
                        && !dominantPayload.contains("ready_probe_timeout"));
    }

    private static void testTelemetryCollector() throws Exception {
        cn.ripplecraft.netherway.core.telemetry.TelemetryEnvironment env =
                new cn.ripplecraft.netherway.core.telemetry.TelemetryEnvironment(
                        "0.1.0", "1.7.10", "8", "linux", "amd64",
                        cn.ripplecraft.netherway.core.telemetry.TelemetryEnvironment.Role.CLIENT);
        cn.ripplecraft.netherway.core.telemetry.QualitySummary failed =
                cn.ripplecraft.netherway.core.telemetry.QualitySummary.of(
                        cn.ripplecraft.netherway.core.telemetry.QualitySummary.Path.UPGRADE,
                        cn.ripplecraft.netherway.core.telemetry.QualitySummary.Stage.ROUND_FINISHED,
                        cn.ripplecraft.netherway.core.telemetry.QualitySummary.Outcome.FAILED)
                        .withSource(cn.ripplecraft.netherway.core.telemetry.QualitySummary.Source.COLD_AGENT)
                        .withFailure("probe", "ready_probe_timeout")
                        .withAttempts(3).withTimings(5200L, 31L);

        cn.ripplecraft.netherway.core.telemetry.TelemetryCollector enhanced =
                new cn.ripplecraft.netherway.core.telemetry.TelemetryCollector(
                        new cn.ripplecraft.netherway.core.telemetry.TelemetryConfig(true, true, 2), env);
        enhanced.record(failed);
        enhanced.record(failed);
        enhanced.record(failed);
        String full = enhanced.previewPayload();
        check("增强遥测队列有界", enhanced.pendingCount() == 2 && enhanced.droppedCount() == 1);
        check("默认 transport 明确标记为未配置", !enhanced.transportConfigured());
        check("payload 含临时 batch ID", full.contains("\"batchId\":\""));
        check("增强 payload 含稳定失败码", full.contains("\"failureCode\":\"ready_probe_timeout\""));
        check("增强 payload 使用桶而非精确耗时", full.contains("\"elapsed\":\"5_8s\"")
                && full.contains("\"rtt\":\"25_50ms\"") && !full.contains("5200"));

        cn.ripplecraft.netherway.core.telemetry.TelemetryCollector basic =
                new cn.ripplecraft.netherway.core.telemetry.TelemetryCollector(
                        new cn.ripplecraft.netherway.core.telemetry.TelemetryConfig(true, false, 4), env);
        basic.record(cn.ripplecraft.netherway.core.telemetry.QualitySummary.of(
                cn.ripplecraft.netherway.core.telemetry.QualitySummary.Path.UPGRADE,
                cn.ripplecraft.netherway.core.telemetry.QualitySummary.Stage.PUNCH_STARTED,
                cn.ripplecraft.netherway.core.telemetry.QualitySummary.Outcome.STARTED)
                .withFailure("probe", "ready_probe_timeout").withTimings(9876L, 44L));
        basic.record(failed);
        String small = basic.previewPayload();
        check("基础模式不收集中间漏斗事件", basic.pendingCount() == 1);
        check("基础 payload 只保留粗粒度结果", small.contains("\"outcome\":\"failed\"")
                && !small.contains("\"path\"") && !small.contains("\"stage\"")
                && !small.contains("failureStage") && !small.contains("failureCode")
                && !small.contains("\"source\"") && !small.contains("\"rtt\"")
                && !small.contains("9876"));

        cn.ripplecraft.netherway.core.telemetry.TelemetryEnvironment unsafe =
                new cn.ripplecraft.netherway.core.telemetry.TelemetryEnvironment(
                        "/Users/alice/mod", "1.7.10", "8", "AliceOS", "secret-arch",
                        cn.ripplecraft.netherway.core.telemetry.TelemetryEnvironment.Role.CLIENT);
        cn.ripplecraft.netherway.core.telemetry.TelemetryCollector sanitized =
                new cn.ripplecraft.netherway.core.telemetry.TelemetryCollector(
                        cn.ripplecraft.netherway.core.telemetry.TelemetryConfig.enabled(true), unsafe);
        String safe = sanitized.previewPayload();
        check("环境自由文本被 allow-list 拦截", !safe.contains("alice")
                && !safe.contains("secret-arch") && safe.contains("\"modVersion\":\"unknown\""));

        final byte[][] sent = new byte[1][];
        cn.ripplecraft.netherway.core.telemetry.TelemetryTransport accepting =
                new cn.ripplecraft.netherway.core.telemetry.TelemetryTransport() {
                    @Override
                    public boolean send(byte[] payload) {
                        sent[0] = payload;
                        return true;
                    }
                };
        cn.ripplecraft.netherway.core.telemetry.TelemetryCollector flushing =
                new cn.ripplecraft.netherway.core.telemetry.TelemetryCollector(
                        cn.ripplecraft.netherway.core.telemetry.TelemetryConfig.enabled(true),
                        env, accepting);
        flushing.record(failed);
        check("注入 transport 后标记为已配置", flushing.transportConfigured());
        check("transport 接受后才清队列", flushing.flush()
                && sent[0] != null && flushing.pendingCount() == 0);

        final byte[][] retried = new byte[2][];
        final int[] retryCalls = new int[1];
        cn.ripplecraft.netherway.core.telemetry.TelemetryTransport retryTransport =
                new cn.ripplecraft.netherway.core.telemetry.TelemetryTransport() {
                    @Override
                    public boolean send(byte[] payload) {
                        int call = retryCalls[0]++;
                        if (call < retried.length) retried[call] = payload.clone();
                        return call > 0;
                    }
                };
        cn.ripplecraft.netherway.core.telemetry.TelemetryCollector retrying =
                new cn.ripplecraft.netherway.core.telemetry.TelemetryCollector(
                        cn.ripplecraft.netherway.core.telemetry.TelemetryConfig.enabled(true),
                        env, retryTransport);
        retrying.record(failed);
        check("失败发送保留首批", !retrying.flush() && retrying.pendingCount() == 1);
        retrying.record(failed);
        check("重试只确认冻结首批", retrying.flush() && retrying.pendingCount() == 1);
        check("重试复用完全相同的 batch ID 与内容",
                java.util.Arrays.equals(retried[0], retried[1]));

        cn.ripplecraft.netherway.core.telemetry.TelemetryCollector disabled =
                new cn.ripplecraft.netherway.core.telemetry.TelemetryCollector(
                        cn.ripplecraft.netherway.core.telemetry.TelemetryConfig.disabled(), env);
        disabled.record(failed);
        check("关闭时不收集摘要", disabled.pendingCount() == 0);
    }

    private static void testTelemetryConcurrentFlush() throws Exception {
        cn.ripplecraft.netherway.core.telemetry.TelemetryEnvironment env =
                new cn.ripplecraft.netherway.core.telemetry.TelemetryEnvironment(
                        "test", "1.7.10", "8", "linux", "amd64",
                        cn.ripplecraft.netherway.core.telemetry.TelemetryEnvironment.Role.CLIENT);
        final CountDownLatch firstSendEntered = new CountDownLatch(1);
        final CountDownLatch releaseFirstSend = new CountDownLatch(1);
        final int[] sendCalls = new int[1];
        cn.ripplecraft.netherway.core.telemetry.TelemetryTransport blocking =
                new cn.ripplecraft.netherway.core.telemetry.TelemetryTransport() {
                    @Override
                    public boolean send(byte[] payload) throws java.io.IOException {
                        int call;
                        synchronized (sendCalls) {
                            call = ++sendCalls[0];
                        }
                        if (call == 1) {
                            firstSendEntered.countDown();
                            try {
                                releaseFirstSend.await();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new java.io.IOException("interrupted", e);
                            }
                        }
                        return true;
                    }
                };
        final cn.ripplecraft.netherway.core.telemetry.TelemetryCollector collector =
                new cn.ripplecraft.netherway.core.telemetry.TelemetryCollector(
                        cn.ripplecraft.netherway.core.telemetry.TelemetryConfig.enabled(true),
                        env, blocking);
        collector.record(cn.ripplecraft.netherway.core.telemetry.QualitySummary.of(
                cn.ripplecraft.netherway.core.telemetry.QualitySummary.Path.UPGRADE,
                cn.ripplecraft.netherway.core.telemetry.QualitySummary.Stage.STARTED,
                cn.ripplecraft.netherway.core.telemetry.QualitySummary.Outcome.STARTED));

        final boolean[] results = new boolean[2];
        final Throwable[] errors = new Throwable[2];
        Thread first = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    results[0] = collector.flush();
                } catch (Throwable t) {
                    errors[0] = t;
                }
            }
        }, "telemetry-flush-first");
        first.start();
        check("并发 flush：首批已进入 transport",
                firstSendEntered.await(2, TimeUnit.SECONDS));

        Thread second = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    results[1] = collector.flush();
                } catch (Throwable t) {
                    errors[1] = t;
                }
            }
        }, "telemetry-flush-second");
        second.start();
        second.join(2_000L);
        check("并发 flush：第二次不重复发送",
                !second.isAlive() && !results[1] && errors[1] == null);

        collector.record(cn.ripplecraft.netherway.core.telemetry.QualitySummary.of(
                cn.ripplecraft.netherway.core.telemetry.QualitySummary.Path.PREFETCH,
                cn.ripplecraft.netherway.core.telemetry.QualitySummary.Stage.ROUND_FINISHED,
                cn.ripplecraft.netherway.core.telemetry.QualitySummary.Outcome.SUCCESS));
        releaseFirstSend.countDown();
        first.join(2_000L);
        String remaining = collector.previewPayload();
        check("并发 flush：首批成功且无异常",
                !first.isAlive() && results[0] && errors[0] == null);
        check("并发 flush：不删除未发送的新摘要",
                collector.pendingCount() == 1
                        && remaining.contains("\"path\":\"prefetch\"")
                        && !remaining.contains("\"path\":\"upgrade\""));
        check("并发 flush：后续批次可正常发送",
                collector.flush() && collector.pendingCount() == 0 && sendCalls[0] == 2);
    }

    private static void testUpgradeTelemetryLanding() throws Exception {
        Path tmp = Files.createTempDirectory("netherway-telemetry-upgrade");
        FakeBridge bridge = new FakeBridge(tmp);
        cn.ripplecraft.netherway.core.telemetry.TelemetryEnvironment env =
                new cn.ripplecraft.netherway.core.telemetry.TelemetryEnvironment(
                        "test", "1.7.10", "8", "linux", "amd64",
                        cn.ripplecraft.netherway.core.telemetry.TelemetryEnvironment.Role.CLIENT);
        cn.ripplecraft.netherway.core.telemetry.TelemetryCollector telemetry =
                new cn.ripplecraft.netherway.core.telemetry.TelemetryCollector(
                        cn.ripplecraft.netherway.core.telemetry.TelemetryConfig.enabled(true), env);
        WarmupController warmup = new WarmupController(bridge,
                new CredentialCache(tmp.resolve("credentials")), Timings.defaults(),
                null, 0, null);
        Credentials cred = Credentials.frpXtcp("203.0.113.10", 7000, "secret-token",
                "stun.example:3478", "private-room", "secret-key", 1000)
                .withOrigin("mc.example.com", 25565);
        warmup.injectReadyForTest(cred, AgentEvent.parse(
                "{\"event\":\"ready\",\"port\":25595,\"rttMs\":31,\"elapsedMs\":1792,"
                + "\"nat\":\"easy\"}"));
        UpgradeController controller = new UpgradeController(bridge, Timings.defaults(),
                null, warmup, telemetry);

        check("遥测落地：收到凭证启动升级", controller.onCredentials(cred));
        long deadline = System.currentTimeMillis() + 5000L;
        while (System.currentTimeMillis() < deadline) {
            synchronized (bridge.connects) {
                if (bridge.connects.contains("127.0.0.1:25595")) break;
            }
            Thread.sleep(20L);
        }
        String before = telemetry.previewPayload();
        check("遥测落地：READY 与重定向开始已记录", before.contains("tunnel_ready")
                && before.contains("redirect_started"));
        check("遥测落地：READY 不冒充真正落地", !before.contains("redirect_landed"));
        controller.onRedirectLanded();
        controller.onRedirectLanded();
        String after = telemetry.previewPayload();
        check("遥测落地：平台确认后才记录 landed", countOf(after, "redirect_landed") == 1);
        check("遥测 payload 不含凭证与服务器标识", !after.contains("secret-token")
                && !after.contains("secret-key") && !after.contains("private-room")
                && !after.contains("203.0.113.10") && !after.contains("stun.example"));
        check("遥测摘要带归一化 backend（不透传 backendId 原文）",
                after.contains("\"backend\":\"frp_xtcp\"") && !after.contains("frp-xtcp"));
        check("遥测摘要带 agent 探得的 NAT 形态", after.contains("\"nat\":\"easy\""));
        controller.shutdown();
        warmup.shutdown();
    }

    private static void testUpgradeTelemetryStaleRedirect() throws Exception {
        Path tmp = Files.createTempDirectory("netherway-telemetry-stale-redirect");
        final List<Runnable> queued = java.util.Collections.synchronizedList(
                new ArrayList<Runnable>());
        FakeBridge bridge = new FakeBridge(tmp) {
            @Override
            public void runOnGameThread(Runnable task) {
                queued.add(task);
            }
        };
        cn.ripplecraft.netherway.core.telemetry.TelemetryEnvironment env =
                new cn.ripplecraft.netherway.core.telemetry.TelemetryEnvironment(
                        "test", "1.7.10", "8", "linux", "amd64",
                        cn.ripplecraft.netherway.core.telemetry.TelemetryEnvironment.Role.CLIENT);
        cn.ripplecraft.netherway.core.telemetry.TelemetryCollector telemetry =
                new cn.ripplecraft.netherway.core.telemetry.TelemetryCollector(
                        cn.ripplecraft.netherway.core.telemetry.TelemetryConfig.enabled(true), env);
        WarmupController warmup = new WarmupController(bridge,
                new CredentialCache(tmp.resolve("credentials")), Timings.defaults(),
                null, 0, null);
        Credentials cred = Credentials.frpXtcp("203.0.113.11", 7000, "token",
                "stun.example:3478", "room", "key", 1000)
                .withOrigin("mc.example.com", 25565);
        warmup.injectReadyForTest(cred, AgentEvent.parse(
                "{\"event\":\"ready\",\"port\":25596,\"rttMs\":40,\"elapsedMs\":2000}"));
        UpgradeController controller = new UpgradeController(bridge, Timings.defaults(),
                null, warmup, telemetry);

        check("过期重定向：收到凭证后开始升级", controller.onCredentials(cred));
        long deadline = System.currentTimeMillis() + 5_000L;
        while (queued.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        check("过期重定向：READY 任务已排队", !queued.isEmpty());
        controller.shutdown();
        List<Runnable> snapshot;
        synchronized (queued) {
            snapshot = new ArrayList<Runnable>(queued);
            queued.clear();
        }
        for (Runnable task : snapshot) task.run();
        String payload = telemetry.previewPayload();
        check("过期重定向：shutdown 后旧任务不再连接", bridge.connects.isEmpty());
        check("过期重定向：未伪造 redirect_started", !payload.contains("redirect_started"));
        check("过期重定向：排队窗口记为 interrupted", payload.contains("interrupted"));
        warmup.shutdown();
    }

    private static void testUpgradeTelemetryRedirectFailure() throws Exception {
        Path tmp = Files.createTempDirectory("netherway-telemetry-redirect-failure");
        FakeBridge bridge = new FakeBridge(tmp) {
            @Override
            public void connectTo(String host, int port) {
                throw new IllegalStateException("synthetic connect failure");
            }
        };
        cn.ripplecraft.netherway.core.telemetry.TelemetryEnvironment env =
                new cn.ripplecraft.netherway.core.telemetry.TelemetryEnvironment(
                        "test", "1.7.10", "8", "linux", "amd64",
                        cn.ripplecraft.netherway.core.telemetry.TelemetryEnvironment.Role.CLIENT);
        cn.ripplecraft.netherway.core.telemetry.TelemetryCollector telemetry =
                new cn.ripplecraft.netherway.core.telemetry.TelemetryCollector(
                        cn.ripplecraft.netherway.core.telemetry.TelemetryConfig.enabled(true), env);
        WarmupController warmup = new WarmupController(bridge,
                new CredentialCache(tmp.resolve("credentials")), Timings.defaults(),
                null, 0, null);
        Credentials cred = Credentials.frpXtcp("203.0.113.12", 7000, "token",
                "stun.example:3478", "room", "key", 1000)
                .withOrigin("mc.example.com", 25565);
        warmup.injectReadyForTest(cred, AgentEvent.parse(
                "{\"event\":\"ready\",\"port\":25597,\"rttMs\":40,\"elapsedMs\":2000}"));
        UpgradeController controller = new UpgradeController(bridge, Timings.defaults(),
                null, warmup, telemetry);

        check("重定向异常：收到凭证后开始升级", controller.onCredentials(cred));
        long deadline = System.currentTimeMillis() + 5_000L;
        while (controller.state() != UpgradeController.State.IDLE
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        String payload = telemetry.previewPayload();
        check("重定向异常：平台抛错后复位", controller.state() == UpgradeController.State.IDLE
                && !controller.redirectInProgress());
        check("重定向异常：只记录 redirect_failed 而非 interrupted",
                payload.contains("redirect_failed") && !payload.contains("interrupted"));
        warmup.shutdown();
    }

    private static int countOf(String text, String needle) {
        int count = 0;
        int at = 0;
        while ((at = text.indexOf(needle, at)) >= 0) {
            count++;
            at += needle.length();
        }
        return count;
    }

    private static void testTelemetryBackendNatDimensions() {
        check("backendId 归一化：frp-xtcp → frp_xtcp",
                cn.ripplecraft.netherway.core.telemetry.QualitySummary.Backend
                        .fromBackendId("frp-xtcp")
                        == cn.ripplecraft.netherway.core.telemetry.QualitySummary.Backend.FRP_XTCP);
        check("backendId 归一化：未识别归 other",
                cn.ripplecraft.netherway.core.telemetry.QualitySummary.Backend
                        .fromBackendId("gonc")
                        == cn.ripplecraft.netherway.core.telemetry.QualitySummary.Backend.OTHER);
        check("backendId 归一化：空归 unknown",
                cn.ripplecraft.netherway.core.telemetry.QualitySummary.Backend.fromBackendId(null)
                        == cn.ripplecraft.netherway.core.telemetry.QualitySummary.Backend.UNKNOWN
                && cn.ripplecraft.netherway.core.telemetry.QualitySummary.Backend.fromBackendId("")
                        == cn.ripplecraft.netherway.core.telemetry.QualitySummary.Backend.UNKNOWN);
        check("nat 线上值解析",
                cn.ripplecraft.netherway.core.telemetry.QualitySummary.Nat.fromWire("easy")
                        == cn.ripplecraft.netherway.core.telemetry.QualitySummary.Nat.EASY
                && cn.ripplecraft.netherway.core.telemetry.QualitySummary.Nat.fromWire("hard")
                        == cn.ripplecraft.netherway.core.telemetry.QualitySummary.Nat.HARD
                && cn.ripplecraft.netherway.core.telemetry.QualitySummary.Nat.fromWire("weird")
                        == cn.ripplecraft.netherway.core.telemetry.QualitySummary.Nat.UNKNOWN
                && cn.ripplecraft.netherway.core.telemetry.QualitySummary.Nat.fromWire(null)
                        == cn.ripplecraft.netherway.core.telemetry.QualitySummary.Nat.UNKNOWN);

        cn.ripplecraft.netherway.core.telemetry.TelemetryEnvironment env =
                new cn.ripplecraft.netherway.core.telemetry.TelemetryEnvironment(
                        "test", "1.7.10", "8", "linux", "amd64",
                        cn.ripplecraft.netherway.core.telemetry.TelemetryEnvironment.Role.CLIENT);
        cn.ripplecraft.netherway.core.telemetry.QualitySummary summary =
                cn.ripplecraft.netherway.core.telemetry.QualitySummary.of(
                        cn.ripplecraft.netherway.core.telemetry.QualitySummary.Path.UPGRADE,
                        cn.ripplecraft.netherway.core.telemetry.QualitySummary.Stage.TUNNEL_READY,
                        cn.ripplecraft.netherway.core.telemetry.QualitySummary.Outcome.SUCCESS)
                        .withBackend(cn.ripplecraft.netherway.core.telemetry
                                .QualitySummary.Backend.FRP_XTCP)
                        .withNat(cn.ripplecraft.netherway.core.telemetry
                                .QualitySummary.Nat.HARD);

        cn.ripplecraft.netherway.core.telemetry.TelemetryCollector enhanced =
                new cn.ripplecraft.netherway.core.telemetry.TelemetryCollector(
                        cn.ripplecraft.netherway.core.telemetry.TelemetryConfig.enabled(true), env);
        enhanced.record(summary);
        String full = enhanced.previewPayload();
        check("payload 声明 schema v2", full.contains("\"schemaVersion\":2"));
        check("增强 payload 带 backend/nat 维度",
                full.contains("\"backend\":\"frp_xtcp\"") && full.contains("\"nat\":\"hard\""));

        cn.ripplecraft.netherway.core.telemetry.TelemetryCollector basic =
                new cn.ripplecraft.netherway.core.telemetry.TelemetryCollector(
                        cn.ripplecraft.netherway.core.telemetry.TelemetryConfig.enabled(false), env);
        basic.record(summary);
        String small = basic.previewPayload();
        check("基础模式抹掉 backend/nat",
                !small.contains("backend") && !small.contains("\"nat\""));
    }

    private static void testAgentEventNatField() {
        AgentEvent with = AgentEvent.parse(
                "{\"event\":\"ready\",\"port\":63128,\"nat\":\"easy\"}");
        check("事件解析 nat 字段", with != null && "easy".equals(with.nat()));
        AgentEvent without = AgentEvent.parse("{\"event\":\"ready\",\"port\":63128}");
        check("旧版 agent 无 nat 字段为 null", without != null && without.nat() == null);
        AgentEvent failed = AgentEvent.parse(
                "{\"event\":\"failed\",\"failureStage\":\"probe\","
                + "\"failureCode\":\"ready_probe_timeout\",\"nat\":\"hard\"}");
        check("失败事件同样携带 nat", failed != null && "hard".equals(failed.nat()));
    }

    private static cn.ripplecraft.netherway.core.telemetry.TelemetryCollector serveCollector() {
        return new cn.ripplecraft.netherway.core.telemetry.TelemetryCollector(
                cn.ripplecraft.netherway.core.telemetry.TelemetryConfig.enabled(true),
                new cn.ripplecraft.netherway.core.telemetry.TelemetryEnvironment(
                        "test", "1.7.10", "21", "linux", "amd64",
                        cn.ripplecraft.netherway.core.telemetry
                                .TelemetryEnvironment.Role.DEDICATED_SERVER));
    }

    private static void testServeTelemetryLifecycle() {
        cn.ripplecraft.netherway.core.telemetry.QualitySummary.Backend frp =
                cn.ripplecraft.netherway.core.telemetry.QualitySummary.Backend.FRP_XTCP;

        // 完整生命周期：启动 → 注册成功 → 异常退出
        cn.ripplecraft.netherway.core.telemetry.TelemetryCollector full = serveCollector();
        cn.ripplecraft.netherway.core.telemetry.ServeTelemetry serve =
                new cn.ripplecraft.netherway.core.telemetry.ServeTelemetry(full, frp);
        serve.onStartAttempt();
        serve.onLogLine("2026/08/16 00:00:00 [I] [proxy_manager.go:100] "
                + "[abc] start proxy success: [room]");
        serve.onLogLine("start proxy success");  // 重复行不得再记一次
        serve.onExit(false);
        String payload = full.previewPayload();
        check("serve 路径与角色", payload.contains("\"path\":\"serve\"")
                && payload.contains("\"role\":\"dedicated_server\""));
        check("serve 注册成功记 tunnel_ready", payload.contains("\"stage\":\"tunnel_ready\""));
        check("serve 注册成功只记一次", countOf(payload, "tunnel_ready") == 1);
        check("serve 就绪后异常退出记 tunnel_lost", payload.contains("\"stage\":\"tunnel_lost\"")
                && payload.contains("\"failureCode\":\"backend_exited\""));
        check("serve 摘要带 backend", payload.contains("\"backend\":\"frp_xtcp\""));

        // 早退：进程活了但没等到注册成功
        cn.ripplecraft.netherway.core.telemetry.TelemetryCollector early = serveCollector();
        cn.ripplecraft.netherway.core.telemetry.ServeTelemetry earlyServe =
                new cn.ripplecraft.netherway.core.telemetry.ServeTelemetry(early, frp);
        earlyServe.onStartAttempt();
        earlyServe.onExit(false);
        check("serve 早退记 agent_early_exit",
                early.previewPayload().contains("\"failureCode\":\"agent_early_exit\""));

        // 主动停止不算事故
        cn.ripplecraft.netherway.core.telemetry.TelemetryCollector clean = serveCollector();
        cn.ripplecraft.netherway.core.telemetry.ServeTelemetry cleanServe =
                new cn.ripplecraft.netherway.core.telemetry.ServeTelemetry(clean, frp);
        cleanServe.onStartAttempt();
        cleanServe.onLogLine("start proxy success");
        cleanServe.onExit(true);
        String cleanPayload = clean.previewPayload();
        check("serve 主动停止不记 tunnel_lost", !cleanPayload.contains("tunnel_lost")
                && !cleanPayload.contains("agent_early_exit"));

        // 启动阶段失败
        cn.ripplecraft.netherway.core.telemetry.TelemetryCollector fail = serveCollector();
        cn.ripplecraft.netherway.core.telemetry.ServeTelemetry failServe =
                new cn.ripplecraft.netherway.core.telemetry.ServeTelemetry(fail, frp);
        failServe.onStartAttempt();
        failServe.onStartFailure(
                cn.ripplecraft.netherway.core.telemetry.QualitySummary.FailureStage.EXTRACT,
                cn.ripplecraft.netherway.core.telemetry.QualitySummary.FailureCode
                        .BINARY_EXTRACT_FAILED);
        failServe.onExit(false);  // 启动失败后进程本来就没起，不得再补一条早退
        String failPayload = fail.previewPayload();
        check("serve 启动失败记录失败码",
                failPayload.contains("\"failureCode\":\"binary_extract_failed\""));
        check("serve 启动失败后不再记早退",
                !failPayload.contains("agent_early_exit"));
    }

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
