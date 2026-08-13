package cn.ripplecraft.netherway.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import cn.ripplecraft.netherway.core.telemetry.QualityObserver;
import cn.ripplecraft.netherway.core.telemetry.QualitySummary;
import cn.ripplecraft.netherway.core.telemetry.QualityWindow;

/**
 * 凭证预取：向 Minecraft 服务器端口走一遍预下发（{@link PreauthClient}），
 * 把换回的凭证写进 {@link CredentialCache}，随后的预热打洞就有的用——
 * 首次启动、密钥轮换之后都不再需要先经中转进一次服。
 *
 * <p>候选地址来自 {@link ServerCandidates}：cfg 里预置的优先，其余是服务器
 * 列表里的条目。所有候选并行试一次，每个成功结果都写入缓存；
 * 没开预下发的服务器会把请求帧当坏包断开，对我们就是「这个候选不应答」。
 *
 * <p>失败永远安静：预取只是优化，玩家照常走缓存/中转路径。
 */
public final class Prefetcher {

    /** 给自检注入的最小网络边界；生产实现就是 {@link PreauthClient}。 */
    interface Fetcher {
        Credentials fetch(ServerCandidates.Address addr, SessionIdentity session,
                          int timeoutMs) throws IOException;
    }

    private static final class FetchResult {
        final Credentials credentials;
        final Throwable error;

        FetchResult(Credentials credentials, Throwable error) {
            this.credentials = credentials;
            this.error = error;
        }
    }

    private final ClientBridge bridge;
    private final SessionIdentity session;
    private final List<ServerCandidates.Address> candidates;
    private final Timings timings;
    private final QualityObserver quality;
    private final Fetcher fetcher;
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
        this(bridge, session, candidates, timings, source, quality, new Fetcher() {
            private final PreauthClient client = new PreauthClient();

            @Override
            public Credentials fetch(ServerCandidates.Address addr, SessionIdentity id,
                                     int timeoutMs) throws IOException {
                return client.fetch(addr, id, timeoutMs);
            }
        });
    }

    Prefetcher(ClientBridge bridge, SessionIdentity session,
               List<ServerCandidates.Address> candidates, Timings timings,
               QualitySummary.Source source, QualityObserver quality, Fetcher fetcher) {
        this.bridge = bridge;
        this.session = session;
        this.candidates = candidates;
        this.timings = timings == null ? Timings.defaults() : timings.normalized();
        this.quality = quality == null ? QualityObserver.NOOP : quality;
        this.fetcher = fetcher;
        QualitySummary.Source normalizedSource = source == null
                ? QualitySummary.Source.UNKNOWN : source;
        this.qualityWindow = new QualityWindow(QualitySummary.Path.PREFETCH, normalizedSource);
    }

    /**
     * 同步跑一次预取；至少换到一份凭证并写进缓存则返回 true。
     * 只在预热的后台线程被调用，任何失败都只记日志、不抛出。
     */
    public boolean refresh(CredentialCache cache) {
        int perCandidate = (int) Math.min(Integer.MAX_VALUE, timings.prefetchTimeoutMs());
        int attempted = candidates.size();
        boolean stored = false;
        ExecutorService pool = null;
        try {
            if (candidates.isEmpty()) {
                return false;
            }
            // 不能逐个吃 timeout：8 个无应答候选会把 60s 上限放大成 8 分钟。
            // 预认证只是短 TCP 交换，不打洞，因此在候选上并行不会干扰 NAT。
            final int parallelism = Math.min(8, candidates.size());
            pool = Executors.newFixedThreadPool(parallelism, new ThreadFactory() {
                private int sequence;

                @Override
                public synchronized Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "netherway-prefetch-" + (++sequence));
                    t.setDaemon(true);
                    return t;
                }
            });
            List<Callable<FetchResult>> tasks = new ArrayList<Callable<FetchResult>>();
            for (final ServerCandidates.Address addr : candidates) {
                tasks.add(new Callable<FetchResult>() {
                    @Override
                    public FetchResult call() {
                        try {
                            return new FetchResult(
                                    fetcher.fetch(addr, session, perCandidate), null);
                        } catch (IOException e) {
                            return new FetchResult(null, e);
                        } catch (RuntimeException e) {
                            return new FetchResult(null, e);
                        }
                    }
                });
            }
            long waves = (candidates.size() + parallelism - 1L) / parallelism;
            List<Future<FetchResult>> futures = pool.invokeAll(tasks,
                    perCandidate * waves + 1000L, TimeUnit.MILLISECONDS);
            Set<String> storedKeys = new HashSet<String>();
            // Future 与 tasks 同序：cfg/server.dat 的优先级仍然决定同一目标
            // 出现多个别名时采用哪份，线程调度不会改变结果。
            for (int i = 0; i < futures.size(); i++) {
                ServerCandidates.Address addr = candidates.get(i);
                try {
                    FetchResult result = futures.get(i).get();
                    if (result.credentials == null) {
                        Throwable error = result.error;
                        String message = error == null ? "未返回凭证" : error.getMessage();
                        if (error instanceof RuntimeException) {
                            bridge.warn("向 " + addr + " 预取凭证时出错（不影响正常游戏）",
                                    error);
                        } else {
                            bridge.debug("向 " + addr + " 预取凭证未成功: " + message);
                        }
                        continue;
                    }
                    Credentials cred = result.credentials.withOrigin(addr.host, addr.port);
                    // 预取是全流程里唯一确切知道「这份凭证来自哪台服务器」的地方：
                    // 内嵌会合点模式下服务端不写地址，就在这里替它补上再落盘，
                    // 之后预热直接拿来用即可（预热跑在玩家还没连服务器的时候，
                    // 那时已经无从得知来源了）。
                    if (cred.needsRendezvousAddress()) {
                        bridge.debug("预取到的凭证未带会合点地址，按来源补为 " + addr);
                        cred = cred.rendezvousAt(addr.host, addr.port);
                    }
                    if (!storedKeys.add(cred.dedupKey())) {
                        bridge.debug("候选 " + addr + " 与前面的入口指向同一份凭证，跳过重复写入");
                        continue;
                    }
                    cache.store(cred);
                    stored = true;
                    bridge.info("已从 " + addr + " 预取到房间 " + cred.room() + " 的凭证");
                } catch (CancellationException e) {
                    bridge.debug("向 " + addr + " 预取凭证超时");
                } catch (ExecutionException e) {
                    bridge.warn("向 " + addr + " 预取凭证时出错（不影响正常游戏）",
                            e.getCause() == null ? e : e.getCause());
                } catch (IOException e) {
                    bridge.warn("写入 " + addr + " 的凭证缓存失败（不影响正常游戏）", e);
                }
            }
            return stored;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return stored;
        } finally {
            if (pool != null) {
                pool.shutdownNow();
            }
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
