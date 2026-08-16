# 贡献指南 / Contributing

嗨，欢迎来玩。项目还在早期，很多东西没定型——PR、issue、想法、吐槽都
欢迎。用 AI 写的代码也欢迎，自己读过、测过就行。

Hi, welcome! This project is still young and plenty of things are not set
in stone — PRs, issues, ideas, and complaints are all welcome. AI-assisted
code is welcome too, as long as you've read and tested it yourself.

贡献按本项目现行许可证（[AGPL-3.0](LICENSE)）对外授权。
Contributions are licensed under the project's current license
([AGPL-3.0](LICENSE)).

## 会让 PR 更顺利的几件事 / A few things that help

- 动手前扫一眼 [CLAUDE.md](CLAUDE.md)——架构、Go↔Java 契约和已知的坑
  都在里面，能省不少弯路。
  Skim [CLAUDE.md](CLAUDE.md) before diving in — the architecture, the
  Go↔Java contracts, and the known pitfalls are all there, and they'll save
  you some detours.
- 本地跑一下 `go build ./... && go vet ./... && go test ./...` 和 Java 侧
  的 `SelfTest`（命令见 CLAUDE.md）。
  Run `go build ./... && go vet ./... && go test ./...` and the Java-side
  `SelfTest` locally (commands in CLAUDE.md).
- 示例地址用 `203.0.113.x` 和 `example.com`，真实服务器地址别写进代码、
  PR 描述或 commit message。
  Use `203.0.113.x` and `example.com` for example addresses; keep real
  server addresses out of code, PR descriptions, and commit messages.
- 想引入第三方依赖，或有大方向上的改动？先开个 issue 聊聊。
  Want to add a third-party dependency, or change something big? Open an
  issue first and let's talk.
