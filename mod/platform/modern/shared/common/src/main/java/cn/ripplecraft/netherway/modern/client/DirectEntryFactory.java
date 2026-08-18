package cn.ripplecraft.netherway.modern.client;

import cn.ripplecraft.netherway.core.ClientBridge;
import cn.ripplecraft.netherway.core.WarmupController;

/**
 * 关闭入口覆盖（{@code client.replaceServerEntries=false}）时，维护独立直连
 * 条目的监听器由各版本自带——{@code ServerData} 构造器与 {@code ServerList.add}
 * 在 1.16.5/1.18.2/1.20.1 之间签名不同。开启覆盖时不会被调用。
 */
public interface DirectEntryFactory {

    WarmupController.Listener create(ClientBridge bridge, String namePrefix);
}
