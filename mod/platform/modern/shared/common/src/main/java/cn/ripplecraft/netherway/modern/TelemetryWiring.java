package cn.ripplecraft.netherway.modern;

import cn.ripplecraft.netherway.core.Platform;
import cn.ripplecraft.netherway.core.telemetry.HttpTelemetryTransport;
import cn.ripplecraft.netherway.core.telemetry.TelemetryCollector;
import cn.ripplecraft.netherway.core.telemetry.TelemetryConfig;
import cn.ripplecraft.netherway.core.telemetry.TelemetryEnvironment;

/**
 * 客户端与专用服务器共用的遥测装配：同一端点、同一 cfg 开关（telemetry.*）、
 * 同一环境归一化，只差 {@link TelemetryEnvironment.Role}。
 *
 * <p>与 1.7.10 版的差异：mod 版本与 MC 版本不再来自 RFG 生成的 Tags 类和
 * 硬编码字符串，而是各版本入口在装配时传入（modern 无 injectTags 机制，
 * 版本号由 loader 元数据提供）。
 *
 * <p>刻意不引用任何 client-only 类：专用服务器的类路径上也要能加载。
 */
public final class TelemetryWiring {

    static final String ENDPOINT = "https://telemetry.ripplecraft.cn/v1/batches";

    private final String modVersion;
    private final String mcVersion;

    public TelemetryWiring(String modVersion, String mcVersion) {
        this.modVersion = modVersion;
        this.mcVersion = mcVersion;
    }

    public TelemetryCollector collector(ModConfig config, TelemetryEnvironment.Role role) {
        return new TelemetryCollector(
                new TelemetryConfig(config.telemetryEnabled(), config.telemetryEnhanced(),
                        TelemetryConfig.DEFAULT_MAX_PENDING),
                environment(role),
                new HttpTelemetryTransport(ENDPOINT, 2_000, 2_000));
    }

    /** 只输出低基数的标准化环境值；绝不把原始系统属性塞进 payload。 */
    public TelemetryEnvironment environment(TelemetryEnvironment.Role role) {
        String os = "other";
        String arch = "other";
        try {
            String platform = Platform.detect().toString();
            int dash = platform.indexOf('-');
            if (dash > 0) {
                os = platform.substring(0, dash);
                arch = platform.substring(dash + 1);
            }
        } catch (Platform.UnsupportedPlatformException ignored) {
            // Unsupported platforms are deliberately grouped as other/other.
        }
        return new TelemetryEnvironment(modVersion, mcVersion, javaMajor(), os, arch, role);
    }

    private static String javaMajor() {
        String spec = System.getProperty("java.specification.version", "");
        if (spec.startsWith("1.")) {
            spec = spec.substring(2);
        }
        int dot = spec.indexOf('.');
        return dot < 0 ? spec : spec.substring(0, dot);
    }
}
