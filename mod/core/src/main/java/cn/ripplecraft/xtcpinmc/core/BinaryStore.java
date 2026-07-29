package cn.ripplecraft.xtcpinmc.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Set;

/**
 * 把打包在 jar 里的 agent 二进制释放到磁盘。
 *
 * <p>Java 调不了 Go 编译的库，agent 只能作为子进程运行，所以必须先落盘。
 * 各平台二进制以资源形式打包进 mod jar，运行时按当前系统挑一个。
 */
public final class BinaryStore {

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
     * 不需要额外的版本比对，也不会用到上一版的残留文件。
     */
    public Path ensureExtracted() throws IOException {
        String resource = platform.resourcePath();
        byte[] payload = readResource(resource);
        String digest = shortDigest(payload);

        String name = platform.executableName();
        int dot = name.lastIndexOf('.');
        String target = dot < 0
                ? name + "-" + digest
                : name.substring(0, dot) + "-" + digest + name.substring(dot);

        Files.createDirectories(cacheDir);
        Path exe = cacheDir.resolve(target);

        if (Files.isRegularFile(exe) && Files.size(exe) == payload.length) {
            ensureExecutable(exe);
            return exe;
        }

        // 先写临时文件再原子改名：游戏可能被同时启动两份，
        // 直接写目标路径会让一个进程读到另一个进程写了一半的文件。
        Path tmp = Files.createTempFile(cacheDir, "xtcpinmc-", ".part");
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
        return exe;
    }

    private byte[] readResource(String resource) throws IOException {
        InputStream in = loader.getResourceAsStream(resource);
        if (in == null) {
            throw new IOException("jar 内缺少当前平台的 agent: " + resource
                    + "（平台 " + platform + "）");
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
