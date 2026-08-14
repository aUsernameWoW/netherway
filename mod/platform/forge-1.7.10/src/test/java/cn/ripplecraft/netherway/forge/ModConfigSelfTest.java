package cn.ripplecraft.netherway.forge;

import cpw.mods.fml.relauncher.FMLInjectionData;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

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
