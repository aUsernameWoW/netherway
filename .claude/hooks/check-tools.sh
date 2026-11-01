#!/bin/sh
# SessionStart 钩子：检查 LSP 插件背后的语言服务器是否在 PATH 上。
# 插件（gopls-lsp / jdtls-lsp）只是包装，真正干活的二进制要贡献者自己装；
# 缺了不会报错，只会让 agent 静默失去跳转/引用/诊断能力。
# stdout 会进入 agent 的上下文，所以缺什么就直说，并给出安装命令。

missing=""

if ! command -v gopls >/dev/null 2>&1; then
    missing="$missing
- gopls（Go，gopls-lsp 插件）：go install golang.org/x/tools/gopls@latest，并确保 \$(go env GOPATH)/bin 在 PATH 上"
fi

if ! command -v jdtls >/dev/null 2>&1; then
    missing="$missing
- jdtls（Java，jdtls-lsp 插件，jdtls 1.63 起需 JDK 25+）：macOS 用 brew install jdtls；其它平台从 https://download.eclipse.org/jdtls/ 解压后把 bin/jdtls 放进 PATH"
fi

if [ -n "$missing" ]; then
    echo "netherway 要求的语言服务器缺失，LSP 插件此时不可用：$missing
请在会话开始时把上面这段转告用户，装好后重启会话。"
fi
exit 0
