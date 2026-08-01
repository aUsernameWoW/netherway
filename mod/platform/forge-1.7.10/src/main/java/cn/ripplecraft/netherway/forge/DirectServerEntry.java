package cn.ripplecraft.netherway.forge;

import cn.ripplecraft.netherway.core.Credentials;
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
 * <p>这是玩家视角的唯一入口：预取 + 预热 + 无限重试都收敛到这个条目上，
 * 名字前缀由整合包配置成服务器名后，玩家看到的就是「那个服务器」本身，
 * 与中转在界面上再无关联（中转条目仍留在列表里作为手动退路）。
 *
 * <p>按名字前缀识别自己的条目：找到就更新，找不到就新增。玩家改名后
 * mod 不再维护那个条目（视作玩家自己的），下次启动会按前缀重新添加；
 * 彻底不要这个条目就关掉 client.prewarm。
 */
final class DirectServerEntry implements WarmupController.Listener {

    private final ForgeClientBridge bridge;
    private final String namePrefix;

    DirectServerEntry(ForgeClientBridge bridge, String namePrefix) {
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
                upsert(cred.room(), port);
            }
        });
    }

    private void upsert(String room, int port) {
        String name = namePrefix + " " + room;
        String address = "127.0.0.1:" + port;
        ServerList list = new ServerList(Minecraft.getMinecraft());
        for (int i = 0; i < list.countServers(); i++) {
            ServerData entry = list.getServerData(i);
            if (entry == null || entry.serverName == null
                    || !entry.serverName.startsWith(namePrefix)) {
                continue;
            }
            if (name.equals(entry.serverName) && address.equals(entry.serverIP)) {
                bridge.debug("直连条目已是最新: " + name + " → " + address);
                return;
            }
            entry.serverName = name;
            entry.serverIP = address;
            list.saveServerList();
            bridge.info("已更新服务器列表的直连条目: " + name + " → " + address);
            return;
        }
        list.addServerData(new ServerData(name, address));
        list.saveServerList();
        bridge.info("已在服务器列表添加直连条目: " + name + " → " + address);
    }
}
