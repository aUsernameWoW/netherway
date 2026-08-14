# 发布 Netherway

正式发布采用「版本 PR → tag → 完整 CI → build attestation → Draft Release →
人工 Publish」流程。日常 `main` / PR 构建只产生临时 Actions artifact；只有
`.github/workflows/release.yml` 会创建 GitHub Release。

## 仓库的一次性设置

首次发布前完成：

1. 在 **Settings → Releases** 开启 **Release immutability**。它只保护启用后
   发布的 Release。
2. 保持 `main` 的 required checks 与 `.github/branch-protection.json` 同步。
3. 建议增加一个匹配 `v*` 的 tag ruleset，只允许维护者创建版本 tag，并限制
   更新和删除。

## 准备版本

1. 完成计划内功能与修复。
2. 用单独 PR 更新 `mod/platform/forge-1.7.10/gradle.properties` 中的
   `modVersion`，并同步文档或变更说明。
3. 等 PR 合入 `main` 且全部 required checks 通过。
4. 确认工作区位于刚通过 CI 的 `main` 提交，然后创建 annotated tag：

   ```bash
   git tag -a v0.1.0 -m "Netherway v0.1.0"
   git push origin v0.1.0
   ```

tag 必须是 `vMAJOR.MINOR.PATCH`，也支持 `v0.2.0-rc.1` 形式的预发布版本；
去掉开头 `v` 后必须与 `modVersion` 完全一致。

## 检查并发布 Draft

推送 tag 后，`release` workflow 会：

1. 复用 `build.yml` 的 Go、Java 和 Forge 全部门禁；
2. 下载这次构建产生的非 `-dev` 原始 JAR；
3. 给同一个 JAR 生成 GitHub build provenance attestation；
4. 使用自动生成的 Release Notes 创建 Draft Release；
5. 只上传 `netherway-forge-1.7.10-<version>.jar`。

打开仓库的 **Releases** 页面，检查 tag、说明、预发布状态和 JAR 文件名。确认
无误后手工点击 **Publish release**。启用 immutability 后，这一步会冻结 tag 与
资产；发布后如需修复，创建新的 patch 版本，不覆盖旧资产或移动旧 tag。

GitHub 会另外显示自动生成的 `Source code (zip)` 和 `Source code (tar.gz)`；
它们不是本项目 workflow 打出的发布包。

## 发布后验证

下载发布 JAR，然后验证 Immutable Release 与构建来源：

```bash
gh release verify v0.1.0 -R aUsernameWoW/netherway
gh release download v0.1.0 -R aUsernameWoW/netherway --pattern '*.jar'
gh release verify-asset v0.1.0 netherway-forge-1.7.10-0.1.0.jar \
  -R aUsernameWoW/netherway
gh attestation verify netherway-forge-1.7.10-0.1.0.jar \
  --repo aUsernameWoW/netherway
```
