# 验收清单：单人游戏内置服务器崩溃修复（上游 #28 / PR #29）

- [ ] **1.7.10 单人世界不再崩溃**
  - 操作：用 `fix/integrated-server-crash` 分支构建的 forge-1.7.10 jar（GTNH 或纯 Forge 10.13.4.1614 均可），客户端 cfg 保持 `server.enabled=true`，新建/进入单人世界。
  - 预期：正常进入世界；游戏日志里没有 `NoSuchMethodError func_71215_F`，也没有 netherway 嗅探器/serve agent 启动的日志。
  - 用户反馈：

- [ ] **1.12.2 单人世界不再崩溃**
  - 操作：同上，换成 forge-1.12.2 jar。
  - 预期：同上。
  - 用户反馈：

- [ ] **专用服务器行为不变**
  - 操作：在专用服务器上装新 jar 启动，客户端加入。
  - 预期：服务端日志照常出现 serve 就绪（`[serve-ready]`），客户端照常收到凭证并升级/预热。
  - 用户反馈：
