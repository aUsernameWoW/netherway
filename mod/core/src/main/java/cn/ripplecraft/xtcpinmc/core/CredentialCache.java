package cn.ripplecraft.xtcpinmc.core;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 凭证的本地缓存：把服务端下发的凭证落盘，供下次启动时预热直连。
 *
 * <p>玩家首次进服仍要等服务端下发；从第二次启动起，游戏加载期间就能用
 * 缓存凭证提前打洞（{@link WarmupController}）。凭证轮换（如 secret 随
 * 服务端重启更换）后缓存自然失效：预热打不通 → 玩家走一次中转 → 登录后
 * 拿到新凭证覆盖缓存——整个闭环不需要玩家或服主做任何事。
 *
 * <p><b>刻意不加密。</b>解密密钥必须与密文放在同一台机器上，加密对玩家
 * 本人只是混淆。凭证本来就会完整出现在玩家的内存与 agent 命令行里，
 * 落盘并未增加新的暴露面；真正的止损手段是服务端轮换密钥。文件权限
 * 收紧到仅属主可读写，防的是同机其他用户，不是玩家本人。
 */
public final class CredentialCache {

    /** 连过几个服就有几份缓存；只留最近几份，目录不会无限膨胀。 */
    private static final int MAX_ENTRIES = 4;
    private static final String SUFFIX = ".cred";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final Path dir;

    public CredentialCache(Path dir) {
        this.dir = dir;
    }

    /**
     * 写入（或覆盖）一份凭证。同一房间（{@link Credentials#dedupKey()}）
     * 始终落在同一个文件上，文件修改时间即「最近使用」的排序依据。
     */
    public void store(Credentials cred) throws IOException {
        Files.createDirectories(dir);
        Path target = dir.resolve(fileNameOf(cred.dedupKey()));
        // 先写临时文件再原子改名：与 BinaryStore 同一纪律，
        // 游戏被同时启动两份时不会读到写了一半的文件。
        Path tmp = Files.createTempFile(dir, "cred-", ".part");
        try {
            Files.write(tmp, cred.encode());
            restrictToOwner(tmp);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                // 某些文件系统不支持原子改名，退回普通移动
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        prune(target);
    }

    /**
     * 取最近一次使用的凭证；没有可用缓存返回 null。
     *
     * <p>损坏的缓存文件直接删掉并尝试下一份——缓存丢了最多退化成
     * 「首次进服」的体验，绝不能因为一个坏文件卡住预热流程。
     */
    public Credentials loadMostRecent() throws IOException {
        for (Path f : listNewestFirst()) {
            try {
                return Credentials.decode(Files.readAllBytes(f));
            } catch (IOException corrupt) {
                deleteQuietly(f);
            }
        }
        return null;
    }

    /** 按修改时间从新到旧列出缓存文件。 */
    private List<Path> listNewestFirst() throws IOException {
        List<Path> files = new ArrayList<Path>();
        if (!Files.isDirectory(dir)) {
            return files;
        }
        DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*" + SUFFIX);
        try {
            for (Path p : ds) {
                files.add(p);
            }
        } finally {
            ds.close();
        }
        Collections.sort(files, new Comparator<Path>() {
            @Override
            public int compare(Path a, Path b) {
                long diff = mtime(b) - mtime(a);
                return diff < 0 ? -1 : (diff > 0 ? 1 : 0);
            }
        });
        return files;
    }

    private static long mtime(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    /**
     * 清掉最旧的多余缓存。刚写入的文件（keep）永不清理——修改时间的
     * 分辨率在某些文件系统上只有秒级，撞车时不能把新鲜数据当旧的删了。
     */
    private void prune(Path keep) {
        try {
            List<Path> files = listNewestFirst();
            for (int i = MAX_ENTRIES; i < files.size(); i++) {
                if (!files.get(i).equals(keep)) {
                    deleteQuietly(files.get(i));
                }
            }
        } catch (IOException ignored) {
            // 清理失败不影响本次写入
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // 删不掉就留着，下次再试
        }
    }

    /** 文件名取去重键的摘要：房间名可能含中文或对路径非法的字符，不直接入名。 */
    private static String fileNameOf(String dedupKey) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(dedupKey.getBytes(UTF8));
            StringBuilder sb = new StringBuilder(16 + SUFFIX.length());
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.append(SUFFIX).toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("运行环境缺少 SHA-256", e);
        }
    }

    /** 凭证含 token 与密钥，权限收紧到仅属主可读写；非 POSIX 文件系统（Windows）跳过。 */
    private static void restrictToOwner(Path file) {
        try {
            Set<PosixFilePermission> perms = new HashSet<PosixFilePermission>();
            perms.add(PosixFilePermission.OWNER_READ);
            perms.add(PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(file, perms);
        } catch (UnsupportedOperationException ignored) {
            // Windows 等非 POSIX 文件系统，交给系统默认 ACL
        } catch (IOException ignored) {
            // 权限收紧失败不阻断缓存写入
        }
    }
}
