package cn.ripplecraft.netherway.forge;

import cn.ripplecraft.netherway.core.AgentEvent;
import cn.ripplecraft.netherway.core.Credentials;
import cpw.mods.fml.relauncher.FMLInjectionData;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import net.minecraftforge.client.event.GuiOpenEvent;

/** 不启动 Minecraft，直接用 Forge 1.7.10 的真实配置解析器做回归测试。 */
public final class ModConfigSelfTest {

    private ModConfigSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("netherway-config-test-");
        try {
            setMinecraftHome(root.toFile());
            extraServerPropertyDoesNotCrash(root);
            invalidScalarValuesUseDefaults(root);
            replacementCanBeDisabled(root);
            cfgCommentsFollowLanguage(root);
            commentOnlyChangesDoNotRewriteCfg(root);
            freshConfigDefaultsToGonc(root);
            goncBackendEmbedsRendezvousBroker(root);
            goncBackendKeepsExplicitBrokers(root);
            goncOriginWithoutRendezvousIsPassedThroughAndWarned(root);
            runtimeRoutesExistOnlyWhileReady();
            eventSubscriberIsExternallyAccessible();
            System.out.println("ModConfigSelfTest passed");
        } finally {
            deleteRecursively(root);
        }
    }

    /** 复现三份 crash report：Forge 会把未登记字段追加到 propertyOrder。 */
    private static void extraServerPropertyDoesNotCrash(Path root) throws Exception {
        Path file = root.resolve("extra-property.cfg");
        Files.write(file, (
                "server {\n"
                + "    B:enabled=false\n"
                + "    S:misspelledLegacyOption=keep-me\n"
                + "}\n\n"
                + "client {\n"
                + "    B:enabled=false\n"
                + "}\n").getBytes(StandardCharsets.UTF_8));

        ModConfig config = new ModConfig(file.toFile());
        check(!config.serverEnabled(), "server.enabled 应按文件读取");
        check(!config.clientEnabled(), "client.enabled 应按文件读取，不能悄悄回退整份配置");
        String saved = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        check(saved.contains("misspelledLegacyOption"), "未知字段应保留，不能破坏用户配置");
    }

    /** 手写配置中的常见坏值应使用 Forge 默认值，而不是中止游戏启动。 */
    private static void invalidScalarValuesUseDefaults(Path root) throws Exception {
        Path file = root.resolve("invalid-values.cfg");
        Files.write(file, (
                "client {\n"
                + "    S:enabled=not-a-boolean\n"
                + "    I:punchTimeoutSeconds=not-a-number\n"
                + "}\n").getBytes(StandardCharsets.UTF_8));

        ModConfig config = new ModConfig(file.toFile());
        check(config.clientEnabled(), "非法布尔值应回退到默认 true");
        check(config.clientTimings().punchTimeoutMs() == 15_000L,
                "非法整数应回退到默认 15 秒");
        check(config.replaceServerEntries(), "入口运行期覆盖应默认开启");
    }

    private static void replacementCanBeDisabled(Path root) throws Exception {
        Path file = root.resolve("replacement-disabled.cfg");
        Files.write(file, (
                "client {\n"
                + "    B:replaceServerEntries=false\n"
                + "}\n").getBytes(StandardCharsets.UTF_8));

        ModConfig config = new ModConfig(file.toFile());
        check(!config.replaceServerEntries(), "应能关闭原条目的运行期覆盖");
    }

    /** cfg 注释经 L10n 按 general.language 生成（文件首次生成时写死）。 */
    private static void cfgCommentsFollowLanguage(Path root) throws Exception {
        Path en = root.resolve("comments-en.cfg");
        Files.write(en, "general {\n    S:language=en\n}\n".getBytes(StandardCharsets.UTF_8));
        new ModConfig(en.toFile());
        String enText = new String(Files.readAllBytes(en), StandardCharsets.UTF_8);
        check(enText.contains("Server side only"), "language=en 时 server 类目注释应为英文");
        check(enText.contains("# Parameters for the default embedded rendezvous"),
                "language=en 时 params 的默认注释行应为英文");
        check(!enText.contains("服务端专用"), "language=en 时不应出现中文类目注释");

        Path zh = root.resolve("comments-zh.cfg");
        Files.write(zh, "general {\n    S:language=zh\n}\n".getBytes(StandardCharsets.UTF_8));
        new ModConfig(zh.toFile());
        String zhText = new String(Files.readAllBytes(zh), StandardCharsets.UTF_8);
        check(zhText.contains("服务端专用"), "language=zh 时 server 类目注释应为中文");
    }

    /**
     * 注释文案与文件不一致（mod 更新改了措辞、服主手改、切换语言）绝不能
     * 触发回写：Forge 的 hasChanged 只看值与新建键，注释是裸赋值。
     * 这条破了，服主手改的 cfg 会在每次启动时被悄悄覆盖。
     */
    private static void commentOnlyChangesDoNotRewriteCfg(Path root) throws Exception {
        Path file = root.resolve("hand-edited.cfg");
        Files.write(file, "general {\n    S:language=zh\n}\n".getBytes(StandardCharsets.UTF_8));
        new ModConfig(file.toFile());   // 首次加载补齐全部键，注释按 zh 生成

        // 同时模拟手改注释与切换语言：重载后内存注释（en）与文件（zh+手改）全面不一致
        String tampered = new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
                .replace("服务端直连总开关", "服主手改的注释")
                .replace("S:language=zh", "S:language=en");
        check(tampered.contains("服主手改的注释"), "前置：手改注释应已写入文件");
        Files.write(file, tampered.getBytes(StandardCharsets.UTF_8));

        new ModConfig(file.toFile());
        String reloaded = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        check(reloaded.equals(tampered), "注释差异不得触发回写，手改内容必须原样保留");
    }

    /**
     * A freshly generated config (nothing but the language set) must come out
     * as the recommended mode without any editing: backend gonc-p2p, the
     * embedded rendezvous on, sessionKey=auto already resolved to a random
     * key, room=minecraft, and no trace of the removed frp-era keys.
     */
    private static void freshConfigDefaultsToGonc(Path root) throws Exception {
        Path file = root.resolve("fresh-defaults.cfg");
        Files.write(file, "general {\n    S:language=en\n}\n".getBytes(StandardCharsets.UTF_8));

        ModConfig config = new ModConfig(file.toFile());
        check(config.serverEnabled(), "新配置默认启用服务端直连");
        check(Credentials.BACKEND_GONC_P2P.equals(config.serverBackendId()),
                "新配置默认 backend 为 gonc-p2p");
        check(config.serverRendezvous(), "新配置默认开启内嵌会合点");
        check(config.serverRunAgent(), "新配置默认 runAgent=true");
        String sessionKey = config.serverParams().get("sessionKey");
        check(sessionKey != null && sessionKey.matches("[0-9a-f]{32}"),
                "新配置的 sessionKey=auto 应已生成随机密钥");
        check("minecraft".equals(config.serverParams().get("room")), "新配置默认房间为 minecraft");
        check(!config.serverParams().containsKey("token")
                        && !config.serverParams().containsKey("secret"),
                "新配置的 params 不得含 frp 时代的 token/secret");
        Credentials cred = config.serverCredentials();
        check(cred != null && Credentials.BACKEND_GONC_P2P.equals(cred.backendId()),
                "新配置应能组装 gonc-p2p 凭证");
        check(Credentials.BROKER_ORIGIN.equals(cred.param(Credentials.PARAM_BROKERS)),
                "新配置的凭证应带 brokers=origin 占位");
        check(cred.needsRendezvousAddress(), "新配置的凭证应自报缺地址（由客户端补）");

        String saved = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        check(saved.contains("S:backend=gonc-p2p"), "回写的 cfg 应记录 backend=gonc-p2p");
        check(saved.contains("sessionKey=auto") && saved.contains("room=minecraft"),
                "回写的 cfg 应保留 sessionKey=auto 与 room=minecraft 原文");
        check(!saved.contains("tokenSigningKey") && !saved.contains("serveAuthToken")
                        && !saved.contains("tokenTtlDays"),
                "回写的 cfg 不得生成已删除的鉴权键");
    }

    /**
     * backend=gonc-p2p with the default rendezvous=true: the embedded
     * rendezvous stays on (it is the loopback MQTT broker), the credentials
     * get the brokers=origin placeholder for the client to resolve, and
     * sessionKey=auto is generated once for serve and credentials alike.
     */
    private static void goncBackendEmbedsRendezvousBroker(Path root) throws Exception {
        Path file = root.resolve("gonc-backend.cfg");
        Files.write(file, (
                "server {\n"
                + "    S:backend=gonc-p2p\n"
                + "    B:rendezvous=true\n"
                + "    S:params <\n"
                + "        sessionKey=auto\n"
                + "        room=minecraft\n"
                + "     >\n"
                + "}\n").getBytes(StandardCharsets.UTF_8));

        ModConfig config = new ModConfig(file.toFile());
        check(Credentials.BACKEND_GONC_P2P.equals(config.serverBackendId()),
                "backend 应按文件读取为 gonc-p2p");
        check(config.serverRendezvous(), "gonc-p2p 下 rendezvous 应保持开启（内嵌 broker）");
        String sessionKey = config.serverParams().get("sessionKey");
        check(sessionKey != null && !sessionKey.isEmpty() && !"auto".equals(sessionKey),
                "sessionKey=auto 应生成随机密钥");
        check(!config.serverParams().containsKey(Credentials.PARAM_BROKERS),
                "serve 参数表不得被塞入 brokers（serve 自己解析 origin）");
        Credentials cred = config.serverCredentials();
        check(cred != null, "gonc-p2p 配置应能组装凭证");
        check(Credentials.BACKEND_GONC_P2P.equals(cred.backendId()),
                "凭证 backendId 应为 gonc-p2p");
        check(cred.param("server") == null && cred.param("serverPort") == null,
                "gonc-p2p 凭证不得带 server/serverPort");
        check(Credentials.BROKER_ORIGIN.equals(cred.param(Credentials.PARAM_BROKERS)),
                "会合点模式下 gonc 凭证应带 brokers=origin");
        check(cred.needsRendezvousAddress(), "brokers=origin 的凭证应自报缺地址");
        check(sessionKey.equals(cred.param("sessionKey")),
                "serve 与下发凭证的 sessionKey 必须同源");
        check(cred.params().size() == 3,
                "gonc-p2p 凭证只含 sessionKey/room/brokers，不附任何身份参数");
    }

    /** An operator-supplied broker list is the operator's choice: no origin injection. */
    private static void goncBackendKeepsExplicitBrokers(Path root) throws Exception {
        Path file = root.resolve("gonc-explicit-brokers.cfg");
        Files.write(file, (
                "server {\n"
                + "    S:backend=gonc-p2p\n"
                + "    S:params <\n"
                + "        sessionKey=auto\n"
                + "        room=minecraft\n"
                + "        brokers=tcp://broker.example.com:1883\n"
                + "     >\n"
                + "}\n").getBytes(StandardCharsets.UTF_8));

        ModConfig config = new ModConfig(file.toFile());
        check(config.serverRendezvous(), "默认 rendezvous=true 对 gonc-p2p 生效");
        Credentials cred = config.serverCredentials();
        check(cred != null, "显式 broker 的 gonc-p2p 配置应能组装凭证");
        check("tcp://broker.example.com:1883".equals(cred.param(Credentials.PARAM_BROKERS)),
                "显式 broker 列表必须原样下发，不注入 origin");
        check(!cred.needsRendezvousAddress(), "显式 broker 的凭证不缺地址");
    }

    /**
     * A hand-written "origin" without the embedded rendezvous is passed through
     * (so the operator sees exactly what they wrote) but must be warned about:
     * nothing resolves it, and the built-in serve refuses to start on it.
     */
    private static void goncOriginWithoutRendezvousIsPassedThroughAndWarned(Path root) throws Exception {
        Path file = root.resolve("gonc-origin-no-rendezvous.cfg");
        Files.write(file, (
                "server {\n"
                + "    S:backend=gonc-p2p\n"
                + "    B:rendezvous=false\n"
                + "    S:params <\n"
                + "        sessionKey=auto\n"
                + "        room=minecraft\n"
                + "        brokers=origin,tcp://broker.example.com:1883\n"
                + "     >\n"
                + "}\n").getBytes(StandardCharsets.UTF_8));

        ModConfig config = new ModConfig(file.toFile());
        check(!config.serverRendezvous(), "rendezvous=false 应按关闭读取");
        check(Credentials.containsOriginBroker(config.serverParams().get(Credentials.PARAM_BROKERS)),
                "serve 参数表里手写的 origin 应原样保留（serve 自己会拒绝并说明）");
        Credentials cred = config.serverCredentials();
        check(cred != null, "含 origin 的 gonc-p2p 配置仍应能组装凭证");
        check("origin,tcp://broker.example.com:1883".equals(cred.param(Credentials.PARAM_BROKERS)),
                "会合点关闭时 brokers 列表原样下发，不注入也不摘除");
        check(cred.needsRendezvousAddress(), "含 origin 的凭证自报缺地址");
    }

    private static void runtimeRoutesExistOnlyWhileReady() {
        WarmupEntryRouter router = new WarmupEntryRouter(true, null, null);
        Credentials cred = Credentials.goncP2p("secret", "room", "tcp://203.0.113.10:1883",
                null, null, 1000)
                .withOrigin("Play.Example.COM", 25565);
        AgentEvent ready = AgentEvent.parse(
                "{\"event\":\"ready\",\"port\":25595,\"rttMs\":31}");

        check(router.resolve("play.example.com") == null,
                "STARTING/未就绪时必须保留真实入口");
        router.onTunnelReady(cred, ready);
        WarmupEntryRouter.Route route = router.resolve("PLAY.EXAMPLE.COM:25565");
        check(route != null && route.port == 25595,
                "READY 后应按标准化真实入口解析到本地端口");
        router.onTunnelClosed(cred, 25596);
        check(router.resolve("play.example.com") != null,
                "迟到的旧端口关闭通知不能撤掉当前路由");
        router.onTunnelClosed(cred, 25595);
        check(router.resolve("play.example.com") == null,
                "隧道关闭后应立即恢复真实入口");

        WarmupEntryRouter disabled = new WarmupEntryRouter(false, null, null);
        disabled.onTunnelReady(cred, ready);
        check(disabled.resolve("play.example.com") == null,
                "关闭覆盖时不得发布运行期路由");
    }

    /** Forge 的 ASM 事件包装类在另一个包/类加载器中，订阅者类型本身必须 public。 */
    private static void eventSubscriberIsExternallyAccessible() throws Exception {
        check(Modifier.isPublic(RouteAwareGuiHandler.class.getModifiers()),
                "GuiOpenEvent 订阅者类型必须 public，Forge ASM 包装类才能访问");
        check(Modifier.isPublic(RouteAwareGuiHandler.class
                        .getDeclaredMethod("onGuiOpen", GuiOpenEvent.class)
                        .getModifiers()),
                "GuiOpenEvent 订阅方法必须 public");
    }

    private static void setMinecraftHome(File root) throws Exception {
        Field field = FMLInjectionData.class.getDeclaredField("minecraftHome");
        field.setAccessible(true);
        field.set(null, root);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void deleteRecursively(Path root) throws Exception {
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception e) {
                    throw new IllegalStateException("无法清理测试目录: " + path, e);
                }
            });
        }
    }
}
