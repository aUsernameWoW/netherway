package cn.ripplecraft.netherway.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 把打包在 jar 里的 agent 二进制释放到磁盘。
 *
 * <p>Java 调不了 Go 编译的库，agent 只能作为子进程运行，所以必须先落盘。
 * 各平台二进制以资源形式打包进 mod jar，运行时按当前系统挑一个。
 */
public final class BinaryStore {

    /**
     * 已释放二进制的文件名形态：netherway-&lt;os&gt;-&lt;arch&gt;-&lt;12位hex&gt;[.exe]。
     * hex 长度与 {@link #shortDigest} 钉在一起，改一处必须同步另一处。
     */
    private static final Pattern EXTRACTED_NAME =
            Pattern.compile("netherway-[a-z0-9]+-[a-z0-9]+-[0-9a-f]{12}(\\.exe)?");

    /** 半成品临时文件超过这个年龄就视为崩溃残留（正常提取以毫秒计）。 */
    private static final long STALE_PART_AGE_MS = 60L * 60L * 1000L;

    private final Path cacheDir;
    private final Platform platform;
    private final ClassLoader loader;

    public BinaryStore(Path cacheDir, Platform platform) {
        this(cacheDir, platform, BinaryStore.class.getClassLoader());
    }

    BinaryStore(Path cacheDir, Platform platform, ClassLoader loader) {
        this.cacheDir = cacheDir;
        this.platform = platform;
        this.loader = loader;
    }

    /**
     * 确保二进制已就位，返回可执行文件路径。
     *
     * <p>文件名里带内容摘要，因此 mod 升级换了 agent 后会自然落到新路径，
     * 不需要额外的版本比对，也不会用到上一版的残留文件；上一版留下的
     * 文件在这里顺手清掉（见 {@link #cleanupStale}）。
     *
     * <p>jar 只随附主力平台的二进制。本平台的资源缺失时会尝试
     * {@link Platform#emulationFallback()}（如 Windows ARM64 借系统的
     * x64 转译层跑 amd64 二进制），两者都没有才报错。
     */
    public Path ensureExtracted() throws IOException {
        Platform effective = platform;
        byte[] payload = tryReadResource(platform.resourcePath());
        if (payload == null) {
            Platform fallback = platform.emulationFallback();
            if (fallback != null) {
                payload = tryReadResource(fallback.resourcePath());
                if (payload != null) {
                    effective = fallback;
                }
            }
            if (payload == null) {
                throw new IOException(fallback == null
                        ? L10n.tr("binary.missing", platform.resourcePath(), platform)
                        : L10n.tr("binary.missingWithFallback",
                                platform.resourcePath(), fallback.resourcePath(), platform));
            }
        }
        String digest = shortDigest(payload);

        // 文件名跟着实际释放的二进制走（转译兜底时是替代平台的），与内容一致
        String name = effective.executableName();
        int dot = name.lastIndexOf('.');
        String target = dot < 0
                ? name + "-" + digest
                : name.substring(0, dot) + "-" + digest + name.substring(dot);

        Files.createDirectories(cacheDir);
        Path exe = cacheDir.resolve(target);

        if (Files.isRegularFile(exe) && Files.size(exe) == payload.length) {
            ensureExecutable(exe);
            cleanupStale(exe);
            return exe;
        }

        // 先写临时文件再原子改名：游戏可能被同时启动两份，
        // 直接写目标路径会让一个进程读到另一个进程写了一半的文件。
        Path tmp = Files.createTempFile(cacheDir, "netherway-", ".part");
        try {
            Files.write(tmp, payload);
            ensureExecutable(tmp);
            try {
                Files.move(tmp, exe, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                // 某些文件系统不支持原子改名，退回普通移动
                Files.move(tmp, exe, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }

        ensureExecutable(exe);
        cleanupStale(exe);
        return exe;
    }

    /**
     * 清掉旧版本残留的二进制与崩溃留下的半成品。
     *
     * <p>文件名带内容摘要，mod 升级后旧文件不会再被引用，只会越攒越多
     * （每个 20MB 上下）。同目录还住着凭证缓存、日志等邻居，因此只认
     * {@link #EXTRACTED_NAME} 形态的文件名与足够陈旧的 {@code .part}；
     * 新鲜的 {@code .part} 可能是并发启动的另一份游戏正在写，不碰。
     * Windows 上正在运行的旧 agent 删不掉，跳过即可，下次启动再收；
     * 清理失败一律不影响本次启动。
     */
    private void cleanupStale(Path current) {
        String keep = current.getFileName().toString();
        DirectoryStream<Path> dir = null;
        try {
            dir = Files.newDirectoryStream(cacheDir);
            for (Path p : dir) {
                String name = p.getFileName().toString();
                if (name.equals(keep) || !Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                if (EXTRACTED_NAME.matcher(name).matches() || isStalePart(p, name)) {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException inUse) {
                        // 大概率被正在运行的旧 agent 占着（Windows），留给下次
                    }
                }
            }
        } catch (IOException ignored) {
            // 清理不成不拦启动
        } finally {
            if (dir != null) {
                try {
                    dir.close();
                } catch (IOException ignored) {
                    // 关闭失败无关紧要
                }
            }
        }
    }

    private static boolean isStalePart(Path p, String name) {
        if (!name.startsWith("netherway-") || !name.endsWith(".part")) {
            return false;
        }
        try {
            long age = System.currentTimeMillis() - Files.getLastModifiedTime(p).toMillis();
            return age > STALE_PART_AGE_MS;
        } catch (IOException gone) {
            return false;
        }
    }

    /** 读 jar 内资源；不存在时返回 {@code null}（调用方决定兜底或报错）。 */
    private byte[] tryReadResource(String resource) throws IOException {
        InputStream in = loader.getResourceAsStream(resource);
        if (in == null) {
            return null;
        }
        try {
            // Java 8 没有 InputStream.readAllBytes()
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream(1 << 20);
            copy(in, buf);
            return buf.toByteArray();
        } finally {
            closeQuietly(in);
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) > 0) {
            out.write(chunk, 0, n);
        }
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // 关闭失败无关紧要
        }
    }

    /** 取 SHA-256 前 6 字节的十六进制，够区分不同构建又不会让文件名太长。 */
    private static String shortDigest(byte[] data) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(12);
            for (int i = 0; i < 6; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("运行环境缺少 SHA-256", e);
        }
    }

    private void ensureExecutable(Path file) {
        if (platform.isWindows()) {
            return;
        }
        try {
            Set<PosixFilePermission> perms = new HashSet<PosixFilePermission>(
                    Files.getPosixFilePermissions(file));
            perms.add(PosixFilePermission.OWNER_READ);
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(file, perms);
        } catch (IOException e) {
            // 权限设置失败会在启动子进程时暴露出来，这里不打断流程
        } catch (UnsupportedOperationException e) {
            // 非 POSIX 文件系统，忽略
        }
    }
}
