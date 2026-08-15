package cn.ripplecraft.netherway.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 组装预取凭证时要问的 Minecraft 服务器地址：客户端 cfg 里预置的优先，
 * 其余来自服务器列表（server.dat）里的条目。
 *
 * <p>问的就是服务器本身的端口——预认证帧与 MC 流量在同一个端口上靠首字节
 * 分叉（见 {@link PreauthProtocol}），所以不需要推导任何别的地址，
 * 也不需要服主额外暴露什么。没开预认证的服务器会把这个帧当成坏包直接断开，
 * 对我们就是「这个候选不应答」，跳过即可。
 *
 * <p>这里只做纯字符串处理，不发任何网络请求，方便在 SelfTest 里钉住规则。
 */
public final class ServerCandidates {

    /** Minecraft 默认端口，条目没写端口时用它。 */
    public static final int DEFAULT_PORT = 25565;

    /**
     * 候选上限。每个候选都要试着连一次（各带超时），server.dat 很长时
     * 不能把启动期的预取拖成分钟级。
     *
     * <p>上限只截断 server.dat 推来的猜测：cfg 里显式给出的地址是部署者的
     * 明确指定，一条都不丢，哪怕它们自己就超过了这个数。
     */
    static final int MAX_CANDIDATES = 8;

    private ServerCandidates() {
    }

    /** 一个候选：主机与端口。 */
    public static final class Address {
        public final String host;
        public final int port;

        Address(String host, int port) {
            this.host = host;
            this.port = port;
        }

        /**
         * 构造一个地址；主机为空或端口越界时返回 null。
         *
         * <p>给平台适配层用：它知道玩家正连着哪台服务器，但拿到的值来自
         * 游戏内部结构，不保证合法，所以这里代为把关而不是让每个实现自己判。
         */
        public static Address of(String host, int port) {
            if (host == null) {
                return null;
            }
            String h = host.trim();
            if (h.isEmpty() || port <= 0 || port > 65535) {
                return null;
            }
            return new Address(h, port);
        }

        @Override
        public String toString() {
            return host + ":" + port;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Address)) {
                return false;
            }
            Address a = (Address) o;
            return port == a.port && host.equals(a.host);
        }

        @Override
        public int hashCode() {
            return host.hashCode() * 31 + port;
        }
    }

    /**
     * @param configured      cfg 里预置的地址（{@code host} 或 {@code host:port}），可为 null
     * @param serverListAddresses 服务器列表里的条目地址，原样的 host[:port] 字符串
     * @return 按尝试顺序排列、去重后的候选，可能为空
     */
    public static List<Address> build(String[] configured, List<String> serverListAddresses) {
        Set<Address> out = new LinkedHashSet<Address>();
        if (configured != null) {
            for (String s : configured) {
                Address a = parse(s);
                if (a != null) {
                    out.add(a);
                }
            }
        }
        if (serverListAddresses != null) {
            for (String s : serverListAddresses) {
                if (out.size() >= MAX_CANDIDATES) {
                    break;
                }
                Address a = parse(s);
                if (a != null) {
                    out.add(a);
                }
            }
        }
        return new ArrayList<Address>(out);
    }

    /**
     * 解析一条 {@code host} 或 {@code host:port}；解析不出返回 null。
     *
     * <p>回环要跳过：那多半是本 mod 自己维护的直连条目（指向预热隧道的本地
     * 端口）或玩家的本地调试服。向直连条目预取是循环依赖——那条隧道正是
     * 预取要拿的凭证建起来的。IPv6 字面量同样跳过：带方括号的写法解析起来
     * 啰嗦，而它在 MC 服务器列表里极罕见，不值得占一个候选名额。
     */
    public static Address parse(String address) {
        String s = address == null ? "" : address.trim();
        if (s.isEmpty() || s.startsWith("[")) {
            return null;
        }
        int port = DEFAULT_PORT;
        int colon = s.indexOf(':');
        if (colon >= 0) {
            if (s.indexOf(':', colon + 1) >= 0) {
                return null; // 不带方括号的裸 IPv6
            }
            int parsed = parsePort(s.substring(colon + 1));
            if (parsed < 0) {
                return null;
            }
            port = parsed;
            s = s.substring(0, colon);
        }
        if (s.isEmpty()) {
            return null;
        }
        String host = s.toLowerCase(Locale.ROOT);
        if ("localhost".equals(host) || host.startsWith("127.") || "0.0.0.0".equals(host)
                || "::1".equals(host)) {
            return null;
        }
        return new Address(host, port);
    }

    private static int parsePort(String s) {
        if (s.isEmpty() || s.length() > 5) {
            return -1;
        }
        int v = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            v = v * 10 + (c - '0');
        }
        return (v > 0 && v <= 65535) ? v : -1;
    }
}
