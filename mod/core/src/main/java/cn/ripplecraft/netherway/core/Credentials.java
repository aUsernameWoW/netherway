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
 * <p>v2 起凭证是「backend 标识 + 参数表」：core 不解释参数含义，只负责
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

    /** 格式版本。v1 是 frp 专用的固定字段布局，v2 起为通用参数表。 */
    private static final byte FORMAT_VERSION = 2;

    /** frp xtcp 打洞 backend 的标识，与 Go 侧 internal/backend/frpxtcp 一致。 */
    public static final String BACKEND_FRP_XTCP = "frp-xtcp";

    /** 所有 backend 必填的参数：房间名，用于向玩家展示与重复凭证去重。 */
    public static final String PARAM_ROOM = "room";

    /**
     * 可选的每玩家身份参数，服务端启用令牌签发后按玩家附加
     * （{@link #withExtraParams}）。键名与 Go 侧 frpxtcp 的
     * {@code ParamUser}/{@code ParamUserToken} 一致；老 agent 按契约忽略。
     */
    public static final String PARAM_USER = "user";
    public static final String PARAM_USER_TOKEN = "userToken";

    private final String backendId;
    /** 保序（下发顺序），使 encode 与命令行输出确定、可测。 */
    private final Map<String, String> params;
    private final int punchTimeoutMs;

    public Credentials(String backendId, Map<String, String> params, int punchTimeoutMs) {
        this.backendId = require(backendId, "backendId");
        if (params == null) {
            throw new IllegalArgumentException("params 不能为 null");
        }
        Map<String, String> copy = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> e : params.entrySet()) {
            String key = e.getKey();
            if (key == null || key.isEmpty()) {
                throw new IllegalArgumentException("参数键不能为空");
            }
            if (key.indexOf('=') >= 0) {
                // 参数经 -O key=value 传给 agent，键含等号会让 agent 拆错位置
                throw new IllegalArgumentException("参数键不能含等号: " + key);
            }
            if (e.getValue() == null) {
                throw new IllegalArgumentException("参数 " + key + " 的值不能为 null");
            }
            copy.put(key, e.getValue());
        }
        if (copy.size() > 0xFFFF) {
            throw new IllegalArgumentException("参数过多: " + copy.size());
        }
        this.params = Collections.unmodifiableMap(copy);
        require(this.params.get(PARAM_ROOM), PARAM_ROOM);
        this.punchTimeoutMs = punchTimeoutMs;
    }

    /**
     * 构造 frp xtcp 打洞的凭证。
     *
     * <p>键名与 Go 侧 frpxtcp 的参数契约一致，集中在这个工厂里，
     * 避免服务端下发与 agent 解析各写一份、日后改岔。
     */
    public static Credentials frpXtcp(String serverAddr, int serverPort, String token,
                                      String stunServer, String roomName, String secretKey,
                                      int punchTimeoutMs) {
        Map<String, String> p = new LinkedHashMap<String, String>();
        p.put("server", require(serverAddr, "serverAddr"));
        p.put("serverPort", Integer.toString(serverPort));
        p.put("token", require(token, "token"));
        p.put("stun", require(stunServer, "stunServer"));
        p.put(PARAM_ROOM, require(roomName, "roomName"));
        p.put("secret", require(secretKey, "secretKey"));
        return new Credentials(BACKEND_FRP_XTCP, p, punchTimeoutMs);
    }

    /** 从工厂产物提取键集，保证与上面的键名字面量永远一致、不会改岔。 */
    private static final java.util.Set<String> FRP_XTCP_PARAM_KEYS =
            frpXtcp("_", 1, "_", "_", "_", "_", 0).params().keySet();

    /**
     * frp-xtcp 契约的全部参数键。
     *
     * <p>服务端直接构造参数表（不经上面的工厂）时可据此校验拼写：agent
     * 按契约忽略未知键，键名写错不会报错，只会静默落回构建期默认值——
     * 比如把 secret 写成 key，表现就是「密钥为空」而看不出为什么。
     */
    public static java.util.Set<String> frpXtcpParamKeys() {
        return FRP_XTCP_PARAM_KEYS;
    }

    private static String require(String v, String name) {
        if (v == null || v.isEmpty()) {
            throw new IllegalArgumentException("凭证字段 " + name + " 不能为空");
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
     * v1（frp 专用布局）会被翻译成等价的 frp-xtcp 参数表，老服务端不受影响。
     *
     * @throws IOException 数据损坏或字段缺失
     */
    public static Credentials decode(byte[] data) throws IOException {
        if (data == null || data.length == 0) {
            throw new IOException("凭证数据为空");
        }
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        int version = in.readByte() & 0xFF;
        if (version == 1) {
            return decodeV1(in);
        }
        if (version < FORMAT_VERSION) {
            throw new IOException("凭证格式版本过旧: " + version);
        }
        String backendId = in.readUTF();
        int punchTimeoutMs = in.readInt();
        int count = in.readUnsignedShort();
        Map<String, String> params = new LinkedHashMap<String, String>();
        for (int i = 0; i < count; i++) {
            String key = in.readUTF();
            params.put(key, in.readUTF());
        }
        // 版本更高时后面可能还有字段，直接不读，保持向后兼容
        try {
            return new Credentials(backendId, params, punchTimeoutMs);
        } catch (IllegalArgumentException e) {
            // 数据完整但内容非法（如缺 room），统一按损坏凭证处理
            throw new IOException("凭证内容非法: " + e.getMessage());
        }
    }

    /** v1 是 frp 专用的固定字段布局，老服务端仍会下发。 */
    private static Credentials decodeV1(DataInputStream in) throws IOException {
        String serverAddr = in.readUTF();
        int serverPort = in.readInt();
        String token = in.readUTF();
        String stunServer = in.readUTF();
        String roomName = in.readUTF();
        String secretKey = in.readUTF();
        int punchTimeoutMs = in.readInt();
        try {
            return frpXtcp(serverAddr, serverPort, token, stunServer,
                    roomName, secretKey, punchTimeoutMs);
        } catch (IllegalArgumentException e) {
            throw new IOException("凭证内容非法: " + e.getMessage());
        }
    }

    /**
     * 返回附加了额外参数的新凭证（原对象不变），同名键被覆盖。
     * 服务端按玩家追加 {@link #PARAM_USER}/{@link #PARAM_USER_TOKEN} 用——
     * 配置里的公共参数只解析一次，身份参数每次登录都不同。
     */
    public Credentials withExtraParams(Map<String, String> extra) {
        Map<String, String> merged = new LinkedHashMap<String, String>(params);
        merged.putAll(extra);
        return new Credentials(backendId, merged, punchTimeoutMs);
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
     * 重复凭证识别键。切换连接后玩家会重新登录，服务端会再下发一次凭证，
     * {@link UpgradeController} 靠这个键识别「同一个目标」以避免升级死循环。
     */
    public String dedupKey() {
        return backendId + ":" + room();
    }

    /** 刻意不输出任何参数值（token 与密钥都在其中），只列键名便于排查。 */
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
