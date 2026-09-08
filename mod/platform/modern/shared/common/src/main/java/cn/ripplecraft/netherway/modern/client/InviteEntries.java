package cn.ripplecraft.netherway.modern.client;

import cn.ripplecraft.netherway.core.Credentials;
import cn.ripplecraft.netherway.core.InviteCode;
import cn.ripplecraft.netherway.core.L10n;
import cn.ripplecraft.netherway.core.WarmupController;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;

/**
 * Invite codes sitting in the player's server list, exposed to the warm-up
 * manager as a {@link WarmupController.CredentialSource}. Mirrors the
 * forge-1.7.10 class of the same name; the mojmap {@link ServerList} API
 * ({@code load/size/get}, {@code ServerData.ip}) is identical across
 * 1.16.5/1.18.2/1.20.1.
 *
 * <p>The list entry is the source of truth: {@link #rescan()} re-reads
 * {@code servers.dat}, decodes every {@link InviteCode} entry and publishes
 * the result; the warm-up loop merges it with the cache on its next round.
 * {@link #rescan()} runs on the game thread; the warm-up thread only reads
 * the published snapshot.
 */
final class InviteEntries implements WarmupController.CredentialSource {

    private final ModernClientBridge bridge;
    private volatile List<Credentials> current = new ArrayList<Credentials>();
    private final Map<String, String> known = new HashMap<String, String>();
    private List<String> lastAddresses = new ArrayList<String>();

    InviteEntries(ModernClientBridge bridge) {
        this.bridge = bridge;
    }

    @Override
    public List<Credentials> current() {
        return current;
    }

    /** Re-reads the server list; safe to call every second while the list screen is open. */
    void rescan() {
        List<String> addresses = new ArrayList<String>();
        try {
            ServerList list = new ServerList(Minecraft.getInstance());
            list.load();
            for (int i = 0; i < list.size(); i++) {
                ServerData entry = list.get(i);
                if (entry != null && entry.ip != null && InviteCode.isInviteCode(entry.ip)) {
                    addresses.add(entry.ip.trim());
                }
            }
        } catch (RuntimeException e) {
            bridge.warn(L10n.tr("fclient.serverListReadFailed"), e);
            return;
        }
        if (addresses.equals(lastAddresses)) {
            return;
        }
        lastAddresses = addresses;
        List<Credentials> decoded = InviteCode.collect(addresses, bridge);
        Map<String, String> next = new HashMap<String, String>();
        for (Credentials cred : decoded) {
            next.put(cred.dedupKey(), cred.room());
            if (!known.containsKey(cred.dedupKey())) {
                bridge.info(L10n.tr("invite.found", cred.room(), cred.originHost()));
            }
        }
        for (Map.Entry<String, String> gone : known.entrySet()) {
            if (!next.containsKey(gone.getKey())) {
                bridge.info(L10n.tr("invite.gone", gone.getValue()));
            }
        }
        known.clear();
        known.putAll(next);
        current = decoded;
    }
}
