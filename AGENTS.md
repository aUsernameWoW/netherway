# AGENTS.md

本仓库的架构、契约、常用命令与已知陷阱全部写在 [CLAUDE.md](CLAUDE.md)，
那是唯一的权威来源，动手前先完整读一遍；这里不重复，免得两份文档漂移。

## 必装的代码智能工具

仓库约 120 个 Java 文件、20 多个 Go 文件，改动常跨 Go↔Java 契约，
找引用、看诊断不能只靠文本搜索：

| 工具 | 用途 | 安装 |
|---|---|---|
| `gopls` | Go agent | `go install golang.org/x/tools/gopls@latest` |
| `jdtls` | Java core 与平台适配层（jdtls 1.63 起需 JDK 25+，旧版本需 17+） | macOS `brew install jdtls`；其它平台从 <https://download.eclipse.org/jdtls/> 解压，把 `bin/jdtls` 放进 PATH |

各 agent 的接线已随仓库提交：

- **Claude Code**：`.claude/settings.json` 启用 `gopls-lsp` 与 `jdtls-lsp`
  插件（官方 marketplace），信任本目录时会提示安装；SessionStart 钩子
  在缺少上面的二进制时提醒。
- **Codex**：`.codex/config.toml` 把 `gopls mcp` 挂为 MCP 服务器。
  Codex 暂无 Java 的等价物，Java 侧照 CLAUDE.md 用 Java 8 `javac` 编译
  验证。
