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

    private static void runtimeRoutesExistOnlyWhileReady() {
        WarmupEntryRouter router = new WarmupEntryRouter(true, null, null);
        Credentials cred = Credentials.frpXtcp("203.0.113.10", 7000, "token",
                "stun.example:3478", "room", "secret", 1000)
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
