package cn.ripplecraft.netherway.core;

import java.io.IOException;
import java.util.List;
import cn.ripplecraft.netherway.core.telemetry.QualityObserver;
import cn.ripplecraft.netherway.core.telemetry.QualitySummary;
import cn.ripplecraft.netherway.core.telemetry.QualityWindow;

/**
 * 凭证预取：向 Minecraft 服务器端口走一遍预下发（{@link PreauthClient}），
 * 把换回的凭证写进 {@link CredentialCache}，随后的预热打洞就有的用——
 * 首次启动、密钥轮换之后都不再需要先经中转进一次服。
 *
 * <p>候选地址来自 {@link ServerCandidates}：cfg 里预置的优先，其余是服务器
 * 列表里的条目。逐个试，第一个换到凭证的就收工；没开预下发的服务器会把
 * 请求帧当坏包断开，对我们就是「这个候选不应答」。
 *
 * <p>失败永远安静：预取只是优化，玩家照常走缓存/中转路径。
 */
public final class Prefetcher {

    private final ClientBridge bridge;
    private final SessionIdentity session;
    private final List<ServerCandidates.Address> candidates;
    private final Timings timings;
    private final QualityObserver quality;
    /** 预热会无限调用 refresh；结果必须先在本地窗口内收口。 */
    private final QualityWindow qualityWindow;

    public Prefetcher(ClientBridge bridge, SessionIdentity session,
                      List<ServerCandidates.Address> candidates, Timings timings) {
        this(bridge, session, candidates, timings, QualitySummary.Source.UNKNOWN,
                QualityObserver.NOOP);
    }

    public Prefetcher(ClientBridge bridge, SessionIdentity session,
                      List<ServerCandidates.Address> candidates, Timings timings,
                      QualitySummary.Source source, QualityObserver quality) {
        this.bridge = bridge;
        this.session = session;
        this.candidates = candidates;
        this.timings = timings == null ? Timings.defaults() : timings.normalized();
        this.quality = quality == null ? QualityObserver.NOOP : quality;
        QualitySummary.Source normalizedSource = source == null
                ? QualitySummary.Source.UNKNOWN : source;
        this.qualityWindow = new QualityWindow(QualitySummary.Path.PREFETCH, normalizedSource);
    }

    /**
     * 同步跑一次预取；换到凭证并写进缓存则返回 true。
     * 只在预热的后台线程被调用，任何失败都只记日志、不抛出。
     */
    public boolean refresh(CredentialCache cache) {
        PreauthClient client = new PreauthClient();
        int perCandidate = (int) timings.prefetchTimeoutMs();
        int attempted = 0;
        boolean stored = false;
        try {
            for (ServerCandidates.Address addr : candidates) {
                attempted++;
                try {
                    Credentials cred = client.fetch(addr, session, perCandidate);
                    // 预取是全流程里唯一确切知道「这份凭证来自哪台服务器」的地方：
                    // 内嵌会合点模式下服务端不写地址，就在这里替它补上再落盘，
                    // 之后预热直接拿来用即可（预热跑在玩家还没连服务器的时候，
                    // 那时已经无从得知来源了）。
                    if (cred.needsRendezvousAddress()) {
                        bridge.debug("预取到的凭证未带会合点地址，按来源补为 " + addr);
                        cred = cred.rendezvousAt(addr.host, addr.port);
                    }
                    cache.store(cred);
                    stored = true;
                    bridge.info("已从 " + addr + " 预取到房间 " + cred.room() + " 的凭证");
                    return true;
                } catch (IOException e) {
                    // 绝大多数候选本就不提供预下发，这是预期路径，不该刷屏
                    bridge.debug("向 " + addr + " 预取凭证未成功: " + e.getMessage());
                } catch (RuntimeException e) {
                    bridge.warn("向 " + addr + " 预取凭证时出错（不影响正常游戏）", e);
                }
            }
            return false;
        } finally {
            // attempts 是确实发起过的候选探测数，不是 warmup 的退避轮次。
            qualityWindow.markAttempts(attempted);
            QualitySummary summary = stored
                    ? qualityWindow.succeeded(QualitySummary.Stage.ROUND_FINISHED, 0L, 0L)
                    : qualityWindow.failed(QualitySummary.Stage.ROUND_FINISHED,
                            QualitySummary.FailureStage.PREFETCH,
                            QualitySummary.FailureCode.PREFETCH_FAILED);
            observe(summary);
        }
    }

    private void observe(QualitySummary summary) {
        if (summary == null) {
            return;
        }
        try {
            quality.record(summary);
        } catch (RuntimeException ignored) {
            // 预取是优化，遥测更不能影响它。
        }
    }
}
