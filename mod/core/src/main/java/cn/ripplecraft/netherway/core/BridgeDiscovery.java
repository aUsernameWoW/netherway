package cn.ripplecraft.netherway.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 组装 authbridge 的候选地址：客户端配置优先，其余从服务器列表
 * （server.dat 的条目地址）按约定推导。
 *
 * <p>约定：服务器地址 {@code host[:port]} 对应的候选是
 * {@code https://host/netherway}（走 443，与 MC 端口无关）。服主把
 * authbridge 经 TLS 反代挂在主域名的这个路径下，玩家就零配置——
 * 列表里有服务器地址即可。真正的识别在探测阶段：prefetch 逐个
 * GET /info，只认自报 netherway-authbridge 的应答（Go 侧 pickBridge）。
 *
 * <p>这里只做纯字符串推导，不发任何网络请求，方便在 SelfTest 里钉住规则。
 */
public final class BridgeDiscovery {

    /** 发现约定的路径段，与部署文档一致。 */
    public static final String WELL_KNOWN_PATH = "/netherway";

    /**
     * 候选上限。每个候选都要被探测一次（最多几秒超时），server.dat 很长时
     * 不能把启动期的预取拖成分钟级；配置显式给出的地址永远保留。
     */
    static final int MAX_CANDIDATES = 8;

    private BridgeDiscovery() {
    }

    /**
     * @param configured      客户端配置的 authbridge 地址，可为空；非空时排第一
     * @param serverAddresses 服务器列表里的条目地址（原样的 host[:port] 字符串）
     * @return 探测顺序排列、去重后的候选，可能为空
     */
    public static List<String> candidates(String configured, List<String> serverAddresses) {
        Set<String> out = new LinkedHashSet<String>();
        String cfg = configured == null ? "" : configured.trim();
        if (!cfg.isEmpty()) {
            if (!cfg.contains("://")) {
                cfg = "https://" + cfg;
            }
            out.add(trimTrailingSlash(cfg));
        }
        if (serverAddresses != null) {
            for (String address : serverAddresses) {
                if (out.size() >= MAX_CANDIDATES) {
                    break;
                }
                String host = hostOf(address);
                if (host != null) {
                    out.add("https://" + host + WELL_KNOWN_PATH);
                }
            }
        }
        return new ArrayList<String>(out);
    }

    /**
     * 从服务器条目地址里取主机名；取不出（空串、IPv6 字面量、回环）返回 null。
     *
     * <p>回环要跳过：那多半是本 mod 自己维护的直连条目或玩家的本地调试服，
     * 往回环发 https 探测毫无意义。IPv6 字面量跳过：给裸 v6 地址签发证书
     * 极罕见，探测注定失败，不值得占一个候选名额。
     */
    static String hostOf(String address) {
        String s = address == null ? "" : address.trim();
        if (s.isEmpty() || s.startsWith("[")) {
            return null;
        }
        int colon = s.indexOf(':');
        if (colon >= 0) {
            if (s.indexOf(':', colon + 1) >= 0) {
                return null; // 不带方括号的裸 IPv6
            }
            s = s.substring(0, colon);
        }
        if (s.isEmpty()) {
            return null;
        }
        String host = s.toLowerCase(Locale.ROOT);
        if ("localhost".equals(host) || host.startsWith("127.") || "0.0.0.0".equals(host)) {
            return null;
        }
        return host;
    }

    private static String trimTrailingSlash(String url) {
        String s = url;
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}
