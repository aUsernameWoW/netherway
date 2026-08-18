package cn.ripplecraft.netherway.modern.client;

import cn.ripplecraft.netherway.core.Credentials;
import cn.ripplecraft.netherway.core.L10n;
import cn.ripplecraft.netherway.core.WarmupController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;

/**
 * 维护服务器列表（servers.dat）里的直连条目。
 *
 * <p>预热隧道的本地端口每次启动都可能不同，agent 一报出端口（STARTING，
 * 在打洞开始之前）就更新条目——玩家到主菜单时看到的地址必然是本次会话的。
 * 预热的重试循环每一轮都会再报一次，条目已是最新时这里静默跳过。
 * 打洞没成时条目 ping 不通，玩家一眼可辨，不需要额外的状态展示；
 * 打通后列表里显示的就是真实的 P2P 延迟。
 *
 * <p>每份已知服务凭证都有自己的条目。名称只展示房间与凭证来源入口，
 * 方便玩家区分；Netherway 不排名、不自动选服，仍由玩家点击哪一个条目。
 *
 * <p>按完整名字识别自己的条目：找到就更新端口，找不到就新增。不能只按
 * 前缀匹配，否则第二个服务会把第一个条目覆盖。玩家改名后 mod 不再维护
 * 那个条目（视作玩家自己的），下次启动会重新添加；
 * 彻底不要这个条目就关掉 client.prewarm。
 */
public final class DirectServerEntry implements WarmupController.Listener {

    private final cn.ripplecraft.netherway.core.ClientBridge bridge;
    private final String namePrefix;

    public DirectServerEntry(cn.ripplecraft.netherway.core.ClientBridge bridge, String namePrefix) {
        this.bridge = bridge;
        this.namePrefix = namePrefix;
    }

    /**
     * 回调在 agent 的 stdout 读取线程；ServerList 的读写要回主线程做
     * （tick 队列在游戏主循环起来后第一时间排空，早于玩家能点开多人界面）。
     */
    @Override
    public void onTunnelStarting(final Credentials cred, final int port) {
        bridge.runOnGameThread(new Runnable() {
            @Override
            public void run() {
                upsert(cred, port);
            }
        });
    }

    private void upsert(Credentials cred, int port) {
        String name = entryName(cred);
        String legacyName = namePrefix + " " + cred.room();
        String address = "127.0.0.1:" + port;
        ServerList list = new ServerList(Minecraft.getInstance());
        list.load();
        ServerData legacy = null;
        for (int i = 0; i < list.size(); i++) {
            ServerData entry = list.get(i);
            if (entry == null) {
                continue;
            }
            if (!name.equals(entry.name)) {
                if (legacy == null && legacyName.equals(entry.name)) {
                    legacy = entry;
                }
                continue;
            }
            if (name.equals(entry.name) && address.equals(entry.ip)) {
                bridge.debug(L10n.tr("direntry.upToDate", name, address));
                return;
            }
            entry.name = name;
            entry.ip = address;
            list.save();
            bridge.info(L10n.tr("direntry.updated", name, address));
            return;
        }
        // 旧版名称没有 origin。第一份同房间的新凭证到来时就地升级，
        // 后续同名房间会正常新增各自的条目，不留一条永久失效的旧入口。
        if (legacy != null && cred.hasOrigin()) {
            legacy.name = name;
            legacy.ip = address;
            list.save();
            bridge.info(L10n.tr("direntry.migrated", name, address));
            return;
        }
        // 1.18.2 的 add 是单参（hidden 布尔是 1.19.3+）；ServerData 用布尔构造
        list.add(new ServerData(name, address, false));
        list.save();
        bridge.info(L10n.tr("direntry.added", name, address));
    }

    private String entryName(Credentials cred) {
        StringBuilder name = new StringBuilder(namePrefix).append(' ').append(cred.room());
        if (cred.hasOrigin()) {
            name.append(" (").append(cred.originHost());
            if (cred.originPort() != 25565) {
                name.append(':').append(cred.originPort());
            }
            name.append(')');
        }
        return name.toString();
    }
}
