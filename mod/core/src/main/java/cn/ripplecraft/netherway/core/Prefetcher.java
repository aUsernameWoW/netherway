package cn.ripplecraft.netherway.core;

import java.io.IOException;
import java.util.List;

/**
 * 凭证预取：拿游戏会话向 Minecraft 服务器端口走一遍预认证
 * （{@link PreauthClient}），把换回的凭证写进 {@link CredentialCache}，
 * 随后的预热打洞就有的用——首次启动、密钥轮换之后都不再需要先经中转
 * 进一次服。
 *
 * <p>候选地址来自 {@link ServerCandidates}：cfg 里预置的优先，其余是服务器
 * 列表里的条目。逐个试，第一个换到凭证的就收工；没开预认证的服务器会把
 * 预认证帧当坏包断开，对我们就是「这个候选不应答」。
 *
 * <p>全程在本进程内完成，不再起子进程——交换是一次 TCP 对话，
 * accessToken 因此也不必经环境变量交给外部程序，始终留在 JVM 里。
 *
 * <p>失败永远安静：预取只是优化，玩家照常走缓存/中转路径。
 */
public final class Prefetcher {

    private final ClientBridge bridge;
    private final SessionIdentity session;
    private final List<ServerCandidates.Address> candidates;
    private final Timings timings;

    public Prefetcher(ClientBridge bridge, SessionIdentity session,
                      List<ServerCandidates.Address> candidates, Timings timings) {
        this.bridge = bridge;
        this.session = session;
        this.candidates = candidates;
        this.timings = timings == null ? Timings.defaults() : timings.normalized();
    }

    /**
     * 同步跑一次预取；换到凭证并写进缓存则返回 true。
     * 只在预热的后台线程被调用，任何失败都只记日志、不抛出。
     */
    public boolean refresh(CredentialCache cache) {
        // 本机登录用的皮肤站就是 accessToken 唯一有效的地方，
        // 钉死它，不让被问到的服务器替我们指定（见 PreauthClient）
        PreauthClient client = new PreauthClient(bridge, AuthlibInjector.detect());
        int perCandidate = (int) timings.prefetchTimeoutMs();
        for (ServerCandidates.Address addr : candidates) {
            try {
                Credentials cred = client.fetch(addr, session, perCandidate);
                cache.store(cred);
                bridge.info("已从 " + addr + " 预取到房间 " + cred.room() + " 的凭证");
                return true;
            } catch (IOException e) {
                // 绝大多数候选本就不提供预认证，这是预期路径，不该刷屏
                bridge.debug("向 " + addr + " 预取凭证未成功: " + e.getMessage());
            } catch (RuntimeException e) {
                bridge.warn("向 " + addr + " 预取凭证时出错（不影响正常游戏）", e);
            }
        }
        return false;
    }
}
