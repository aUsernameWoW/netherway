package cn.ripplecraft.netherway.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 服务端在玩家登录后下发的直连凭证。
 *
 * <p>这是整套设计的安全基础：凭证不随客户端分发，而是玩家通过正版验证、
 * 白名单等既有校验连上服务器之后才拿得到。能拿到密钥的必然是有权进服的人，
 * 于是不需要另建一套鉴权。
 *
 * <p>凭证是「backend 标识 + 参数表」：core 不解释参数含义，只负责
 * 原样搬运给 agent（经命令行 {@code -O key=value}）。参数键名是各 backend
 * 自己的契约，由服务端与 Go 侧对应实现约定——这样将来新增隧道方案时，
 * core 与平台适配层都无需任何改动，只要服务端下发新的 backend 标识即可。
 *
 * <p>编解码刻意用最朴素的 {@link DataOutputStream} 手写字节，
 * <b>不使用任何 mod 加载器的序列化机制</b>。Forge 1.13 之后网络 API 整个重写过，
 * 绑上去意味着每换一个版本就要重写一遍编解码；而裸字节在所有加载器、
 * 所有版本上都一样，平台适配层只需负责把 byte[] 送出去。
 */
public final class Credentials {

    /**
     * 格式版本。v2 起为通用参数表（v1 是已删除的 frp 专用固定布局，
     * 现在直接拒绝）；v3 曾追加「客户端策略」段（现只保留兼容解码）；
     * v4 在其后记录客户端观测到的 Minecraft 入口，使相同 backend/room
     * 的多台服务器可并存。
     */
    private static final byte FORMAT_VERSION = 4;

    /**
     * 还认得的最低通用布局版本。v2/v3 的凭证可能仍躺在玩家的缓存目录里——
     * 不能因为本侧升到 v4 就把它们当成损坏数据。低于它的（v1）是另一种
     * 布局，读不出来，按损坏数据拒绝而不是误解码。
     */
    private static final int MIN_FORMAT_VERSION = 2;

    /** gonc P2P backend 的标识，与 Go 侧 internal/backend/goncp2p 一致。 */
    public static final String BACKEND_GONC_P2P = "gonc-p2p";

    /** 所有 backend 必填的参数：房间名，用于向玩家展示与重复凭证去重。 */
    public static final String PARAM_ROOM = "room";

    /**
     * gonc-p2p parameter: comma-separated MQTT broker URLs (gonc syntax).
     * Mirrors Go {@code goncp2p.ParamBrokers}.
     */
    public static final String PARAM_BROKERS = "brokers";

    /**
     * Placeholder entry inside {@link #PARAM_BROKERS} meaning "the Minecraft
     * entry this credential came from". Mirrors Go {@code goncp2p.BrokerOrigin}
     * byte for byte (pinned by tests on both sides).
     *
     * <p>Under the embedded rendezvous the signaling broker lives on the
     * server's loopback behind the Minecraft port: the client resolves the
     * token to {@code tcp://<host>:<port>} of the entry it connected to
     * ({@link #rendezvousAt}), while the server-side serve resolves the same
     * token to its own loopback broker. An unresolved token reaching the
     * agent is an error, never silently dropped.
     */
    public static final String BROKER_ORIGIN = "origin";

    private final String backendId;
    /** 保序（下发顺序），使 encode 与命令行输出确定、可测。 */
    private final Map<String, String> params;
    private final int punchTimeoutMs;
    /**
     * 这份凭证来自的 Minecraft 入口。它是纯客户端元数据：不传给
     * backend，只用于在多服务器之间分隔缓存、预热隧道与直连条目。
     * 服务端下发时留空，客户端在预取或实际连接上下文中补齐。
     */
    private final String originHost;
    private final int originPort;

    public Credentials(String backendId, Map<String, String> params, int punchTimeoutMs) {
        this(backendId, params, punchTimeoutMs, "", 0);
    }

    private Credentials(String backendId, Map<String, String> params, int punchTimeoutMs,
                        String originHost, int originPort) {
        this.backendId = require(backendId, "backendId");
        if (params == null) {
            throw new IllegalArgumentException(L10n.tr("cred.paramsNull"));
        }
        Map<String, String> copy = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> e : params.entrySet()) {
            String key = e.getKey();
            if (key == null || key.isEmpty()) {
                throw new IllegalArgumentException(L10n.tr("cred.emptyParamKey"));
            }
            if (key.indexOf('=') >= 0) {
                // 参数经 -O key=value 传给 agent，键含等号会让 agent 拆错位置
                throw new IllegalArgumentException(L10n.tr("cred.paramKeyEquals", key));
            }
            if (e.getValue() == null) {
                throw new IllegalArgumentException(L10n.tr("cred.paramValueNull", key));
            }
            copy.put(key, e.getValue());
        }
        if (copy.size() > 0xFFFF) {
            throw new IllegalArgumentException(L10n.tr("cred.tooManyParams", copy.size()));
        }
        this.params = Collections.unmodifiableMap(copy);
        require(this.params.get(PARAM_ROOM), PARAM_ROOM);
        this.punchTimeoutMs = punchTimeoutMs;
        String host = originHost == null ? "" : originHost.trim();
        if (host.isEmpty() || originPort <= 0 || originPort > 65535) {
            this.originHost = "";
            this.originPort = 0;
        } else {
            this.originHost = host.toLowerCase(java.util.Locale.ROOT);
            this.originPort = originPort;
        }
    }

    /**
     * 构造 gonc P2P 打洞的凭证。
     *
     * <p>信令走 MQTT broker，凭证因此<b>不含任何服务器地址</b>——broker 就是
     * 会合点。sessionKey 一身三职：派生 MQTT topic、加密信令、派生 TLS/DTLS
     * 双向认证证书。键名与 Go 侧 internal/backend/goncp2p 的参数契约一致，
     * 集中在这个工厂里，避免服务端下发与 agent 解析各写一份、日后改岔。
     *
     * <p>Under the embedded rendezvous the broker list carries the
     * {@link #BROKER_ORIGIN} placeholder and {@link #rendezvousAt} resolves it
     * on the client; a list of explicit URLs is left alone.
     *
     * @param brokers     可选（null/空 = agent 内置默认），逗号分隔的 broker URL，
     *                    可含 {@link #BROKER_ORIGIN} 占位
     * @param stunServers 可选，逗号分隔的 STUN 地址（gonc 语法）
     * @param network     可选，钉死打洞网络（any/tcp4/udp4/…）
     */
    public static Credentials goncP2p(String sessionKey, String roomName,
                                      String brokers, String stunServers, String network,
                                      int punchTimeoutMs) {
        Map<String, String> p = new LinkedHashMap<String, String>();
        p.put("sessionKey", require(sessionKey, "sessionKey"));
        p.put(PARAM_ROOM, require(roomName, "roomName"));
        if (brokers != null && !brokers.isEmpty()) {
            p.put(PARAM_BROKERS, brokers);
        }
        if (stunServers != null && !stunServers.isEmpty()) {
            p.put("stunServers", stunServers);
        }
        if (network != null && !network.isEmpty()) {
            p.put("network", network);
        }
        return new Credentials(BACKEND_GONC_P2P, p, punchTimeoutMs);
    }

    /** 从全参工厂产物提取键集，保证与上面的键名字面量永远一致、不会改岔。 */
    private static final java.util.Set<String> GONC_P2P_PARAM_KEYS =
            goncP2p("_", "_", "_", "_", "_", 0).params().keySet();

    /**
     * gonc-p2p 契约的全部参数键。
     *
     * <p>服务端直接构造参数表（不经上面的工厂）时可据此校验拼写：agent
     * 按契约忽略未知键，键名写错不会报错，只会静默落回默认值——
     * 比如把 sessionKey 写成 key，表现就是「密钥为空」而看不出为什么。
     */
    public static java.util.Set<String> goncP2pParamKeys() {
        return GONC_P2P_PARAM_KEYS;
    }

    private static String require(String v, String name) {
        if (v == null || v.isEmpty()) {
            throw new IllegalArgumentException(L10n.tr("cred.emptyField", name));
        }
        return v;
    }

    public byte[] encode() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buf);
        try {
            out.writeByte(FORMAT_VERSION);
            out.writeUTF(backendId);
            out.writeInt(punchTimeoutMs);
            out.writeShort(params.size());
            for (Map.Entry<String, String> e : params.entrySet()) {
                out.writeUTF(e.getKey());
                out.writeUTF(e.getValue());
            }
            // v3 曾在这里放过 policy 表。v4 保留一个空表头，
            // 让 v2/v3 客户端读到 v4 服务端凭证时能安全忽略后缀。
            out.writeShort(0);
            out.writeUTF(originHost);
            out.writeInt(originPort);
            out.flush();
        } catch (IOException e) {
            // ByteArrayOutputStream 不会真的抛 IO 异常
            throw new IllegalStateException("编码凭证失败", e);
        }
        return buf.toByteArray();
    }

    /**
     * 解码。
     *
     * <p>版本号高于当前实现时仍会读取已知前缀并忽略尾部追加的字段——服务端
     * 更新后老客户端仍能工作，只是用不上新特性，这比直接拒绝连接体面得多。
     * v1（已删除的 frp 专用布局）与通用布局字段完全不同，读出来只会是
     * 乱码，所以按「版本过旧」干脆拒绝——调用方（缓存、频道接收）都把
     * 损坏凭证当作「本次没有凭证」处理。
     * v3 曾在参数表后追加过「客户端策略」段，现已被丢弃——读到时跳过即可。
     *
     * @throws IOException 数据损坏、版本过旧或字段缺失
     */
    public static Credentials decode(byte[] data) throws IOException {
        if (data == null || data.length == 0) {
            throw new IOException(L10n.tr("cred.emptyData"));
        }
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        int version = in.readByte() & 0xFF;
        if (version < MIN_FORMAT_VERSION) {
            throw new IOException(L10n.tr("cred.versionTooOld", version));
        }
        String backendId = in.readUTF();
        int punchTimeoutMs = in.readInt();
        int count = in.readUnsignedShort();
        Map<String, String> params = new LinkedHashMap<String, String>();
        for (int i = 0; i < count; i++) {
            String key = in.readUTF();
            params.put(key, in.readUTF());
        }
        // v3 的参数表后曾有一段「客户端策略」（policy）。v4 仍保留
        // 这个表头（固定为空），再追加客户端观测到的 MC 入口。
        String originHost = "";
        int originPort = 0;
        if (version >= 3 && in.available() >= 2) {
            int policyCount = in.readUnsignedShort();
            for (int i = 0; i < policyCount; i++) {
                in.readUTF(); // key，丢弃
                in.readUTF(); // value，丢弃
            }
        }
        if (version >= 4 && in.available() >= 2) {
            originHost = in.readUTF();
            if (in.available() >= 4) {
                originPort = in.readInt();
            }
        }
        // 版本更高时后面可能还有字段，直接不读，保持向后兼容
        try {
            return new Credentials(backendId, params, punchTimeoutMs, originHost, originPort);
        } catch (IllegalArgumentException e) {
            // 数据完整但内容非法（如缺 room），统一按损坏凭证处理
            throw new IOException(L10n.tr("cred.invalid", e.getMessage()));
        }
    }

    /**
     * 附上这份凭证的 Minecraft 入口（原对象不变）。
     *
     * <p>这不是 backend 参数，也不代表 Netherway 在管理服务器；它只是
     * 最小的客户端命名空间。两台服务器可以合理地使用完全相同的
     * backend/room，没有来源地址就无法在本地同时保留两份凭证。
     */
    public Credentials withOrigin(String host, int port) {
        return new Credentials(backendId, params, punchTimeoutMs, host, port);
    }

    public boolean hasOrigin() {
        return !originHost.isEmpty() && originPort > 0;
    }

    public String originHost() {
        return originHost;
    }

    public int originPort() {
        return originPort;
    }

    /**
     * Fills in "where the rendezvous is" from the Minecraft entry the client
     * actually connected to. Returns a new credential; the original is
     * untouched. Invalid host/port leaves the credential as it is.
     *
     * <p>{@link #BACKEND_GONC_P2P}: every {@link #BROKER_ORIGIN} entry inside
     * {@link #PARAM_BROKERS} is replaced with {@code tcp://host:port} (IPv6
     * literals bracketed). This is a substitution, not a default: the server
     * said "the broker is at the entry you came in through", and only the
     * client knows that entry. The server-side serve resolves the same token
     * to its embedded loopback broker. No-op when the list carries no
     * placeholder (the operator named explicit brokers).
     *
     * <p>Other backends: their address keys are their own contract, nothing
     * is guessed here.
     */
    public Credentials rendezvousAt(String host, int port) {
        if (host == null || host.isEmpty() || port <= 0 || port > 65535) {
            return this;
        }
        if (BACKEND_GONC_P2P.equals(backendId) && hasOriginBroker()) {
            String url = brokerUrl(host, port);
            StringBuilder sb = new StringBuilder();
            for (String entry : params.get(PARAM_BROKERS).split(",")) {
                String e = entry.trim();
                if (e.isEmpty()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(BROKER_ORIGIN.equals(e) ? url : e);
            }
            Map<String, String> copy = new LinkedHashMap<String, String>(params);
            copy.put(PARAM_BROKERS, sb.toString());
            return new Credentials(backendId, copy, punchTimeoutMs, originHost, originPort);
        }
        return this;
    }

    /**
     * Whether the credential still lacks its rendezvous address, in which
     * case the caller must complete it with {@link #rendezvousAt}.
     *
     * <p>gonc-p2p: {@link #PARAM_BROKERS} still carries a
     * {@link #BROKER_ORIGIN} placeholder (the agent rejects an unresolved
     * one). Other backends never need an address here.
     */
    public boolean needsRendezvousAddress() {
        return BACKEND_GONC_P2P.equals(backendId) && hasOriginBroker();
    }

    /** True if the gonc broker list contains the {@link #BROKER_ORIGIN} placeholder. */
    private boolean hasOriginBroker() {
        return containsOriginBroker(params.get(PARAM_BROKERS));
    }

    /**
     * True if a {@link #PARAM_BROKERS} value (comma-separated, entries trimmed)
     * contains the {@link #BROKER_ORIGIN} placeholder as a whole entry. Shared
     * with the server-side config so it can warn about a placeholder that
     * nothing will resolve (embedded rendezvous off); mirrors Go
     * {@code goncp2p.HasOriginBroker}.
     */
    public static boolean containsOriginBroker(String brokers) {
        if (brokers == null || brokers.isEmpty()) {
            return false;
        }
        for (String entry : brokers.split(",")) {
            if (BROKER_ORIGIN.equals(entry.trim())) {
                return true;
            }
        }
        return false;
    }

    /** gonc broker URL for a Minecraft entry; IPv6 literals need brackets before the port. */
    private static String brokerUrl(String host, int port) {
        String h = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
        return "tcp://" + h + ":" + port;
    }

    /** backend 标识，决定 agent 用哪种隧道方案。 */
    public String backendId() {
        return backendId;
    }

    /** 房间名。所有 backend 必有，向玩家展示、写日志、去重都用它。 */
    public String room() {
        return params.get(PARAM_ROOM);
    }

    /** 读取 backend 参数；不存在返回 null。core 不解释参数含义。 */
    public String param(String key) {
        return params.get(key);
    }

    /** 只读参数表，保持下发顺序。 */
    public Map<String, String> params() {
        return params;
    }

    /** 服务端建议的建链超时；<=0 表示由客户端配置决定。 */
    public int punchTimeoutMs() {
        return punchTimeoutMs;
    }

    /**
     * 重复凭证识别键。新凭证用「backend + MC 入口 + room」，因此不同
     * 服务器即使使用相同房间名也不会互相覆盖。切换连接后服务端重发凭证时，
     * {@link UpgradeController} 靠这个键避免重复升级死循环。
     */
    public String dedupKey() {
        if (hasOrigin()) {
            return backendId + ":" + originHost + ":" + originPort + ":" + room();
        }
        // 兼容旧缓存。新的预取/登录路径都会先补 origin，
        // 这个退路只用于让升级后的首次启动仍能尝试老凭证。
        return legacyDedupKey();
    }

    /** 旧版只按 backend/room 命名；仅用于缓存迁移。 */
    String legacyDedupKey() {
        return backendId + ":" + room();
    }

    /**
     * 两份凭证是否指向同一建联目标且参数等价。预热重建隧道与预取的
     * 「凭证未变化」日志降噪都以此为准。
     */
    public boolean sameConnectionAs(Credentials other) {
        return other != null && backendId.equals(other.backendId)
                && dedupKey().equals(other.dedupKey())
                && params.equals(other.params);
    }

    /** 刻意不输出任何参数值（密钥在其中），只列键名便于排查。 */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Credentials{backend=").append(backendId)
                .append(" room=").append(room()).append(" params=[");
        boolean first = true;
        for (String key : params.keySet()) {
            if (!first) {
                sb.append(", ");
            }
            sb.append(key);
            first = false;
        }
        return sb.append("] punchTimeoutMs=").append(punchTimeoutMs).append("}").toString();
    }
}
