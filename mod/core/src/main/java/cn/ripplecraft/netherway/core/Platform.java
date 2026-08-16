package cn.ripplecraft.netherway.core;

import java.util.Locale;

/**
 * 识别当前系统，用于从 jar 里挑出正确的 agent 二进制。
 *
 * <p>只依赖 {@code os.name} / {@code os.arch} 两个标准系统属性，
 * 不碰任何 JDK 内部 API——这套代码要在 Java 8 到现代 JVM 上都能跑。
 */
public final class Platform {

    /** 与 build.sh 产出的文件名保持一致。 */
    public enum Os {
        WINDOWS("windows", ".exe"),
        MACOS("macos", ""),
        LINUX("linux", "");

        private final String token;
        private final String exeSuffix;

        Os(String token, String exeSuffix) {
            this.token = token;
            this.exeSuffix = exeSuffix;
        }
    }

    public enum Arch {
        AMD64("amd64"),
        ARM64("arm64");

        private final String token;

        Arch(String token) {
            this.token = token;
        }
    }

    private final Os os;
    private final Arch arch;

    Platform(Os os, Arch arch) {
        this.os = os;
        this.arch = arch;
    }

    public Os os() {
        return os;
    }

    public Arch arch() {
        return arch;
    }

    /**
     * 探测当前平台。
     *
     * @throws UnsupportedPlatformException 无法识别时抛出，调用方应当据此
     *         彻底放弃 P2P 升级（而不是继续用一个猜测的二进制）
     */
    public static Platform detect() {
        return detect(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    /** 供测试注入的重载。 */
    static Platform detect(String osName, String osArch) {
        String n = osName.toLowerCase(Locale.ROOT);
        String a = osArch.toLowerCase(Locale.ROOT);

        Os os;
        if (n.contains("win")) {
            os = Os.WINDOWS;
        } else if (n.contains("mac") || n.contains("darwin")) {
            os = Os.MACOS;
        } else if (n.contains("nux") || n.contains("nix")) {
            os = Os.LINUX;
        } else {
            throw new UnsupportedPlatformException(L10n.tr("platform.unknownOs", osName));
        }

        // JVM 对同一架构有多种叫法：x86_64/amd64 是一回事，aarch64/arm64 也是。
        Arch arch;
        if (a.equals("amd64") || a.equals("x86_64") || a.equals("x64")) {
            arch = Arch.AMD64;
        } else if (a.equals("aarch64") || a.equals("arm64")) {
            arch = Arch.ARM64;
        } else {
            throw new UnsupportedPlatformException(L10n.tr("platform.unknownArch", osArch));
        }

        // 没有为 macOS 构建 32 位/其他组合，这里的组合校验交给资源查找环节，
        // 找不到对应资源时会给出明确报错。
        return new Platform(os, arch);
    }

    /**
     * jar 里没有本平台的二进制时，可借系统转译层运行的替代平台；
     * 没有可默认假定的转译层则返回 {@code null}。
     *
     * <p>jar 只随附主力平台（见 mod/build-natives.sh）。ARM64 的 Windows
     * 自带 x64 转译、Apple Silicon 有 Rosetta 2，amd64 的二进制照样能跑；
     * 反方向（amd64 系统跑 arm64 二进制）与 Linux 都没有这种保证。
     */
    public Platform emulationFallback() {
        if (arch == Arch.ARM64 && (os == Os.WINDOWS || os == Os.MACOS)) {
            return new Platform(os, Arch.AMD64);
        }
        return null;
    }

    /** jar 内资源路径，例如 {@code natives/windows-amd64/netherway.exe}。 */
    public String resourcePath() {
        return "natives/" + os.token + "-" + arch.token + "/netherway" + os.exeSuffix;
    }

    /** 释放到磁盘后的文件名。 */
    public String executableName() {
        return "netherway-" + os.token + "-" + arch.token + os.exeSuffix;
    }

    public boolean isWindows() {
        return os == Os.WINDOWS;
    }

    @Override
    public String toString() {
        return os.token + "-" + arch.token;
    }

    /** 当前系统没有可用的 agent 二进制。 */
    public static final class UnsupportedPlatformException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public UnsupportedPlatformException(String message) {
            super(message);
        }
    }
}
