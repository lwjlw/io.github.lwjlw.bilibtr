# NOTICE — 第三方署名 / Third-Party Attributions

BiliBTR 参考的第三方项目及其许可。列出参考内容与独立实现声明。

---

## 1. Bilibili-thread-ripper

| 项 | 内容 |
| --- | --- |
| 地址 | https://github.com/MrTangLuyao/Bilibili-thread-ripper |
| 许可 | MIT |
| 参考内容 | 多 CDN 并发 Range 下载的**原理与默认参数** |
| 说明 | 本项目为 Java / Kotlin 独立实现，未复制其 JavaScript 代码 |

## 2. PiliPlus（`btr` 分支）

| 项 | 内容 |
| --- | --- |
| 地址 | https://github.com/nishuodedui1145-del/PiliPlus |
| 许可 | GPL-3.0 |
| 参考内容 | 本地代理架构设计、部分调度参数（`docs/btr/DESIGN.md`、`README-BTR.md`） |

PiliPlus 自身声明（原文）：

> 本仓库是 PiliPlus 的衍生作品，整体沿用上游的 GPL-3.0（见 LICENSE，未改动）。
> 并发下载的原理与默认参数参考 Bilibili-thread-ripper（网页版 / 桌面版，MIT）；
> 本移植为 Dart 独立实现，未复制其 JS 代码。
> 逐项改动说明与第三方署名见 NOTICE。

其默认参数源于 Bilibili-thread-ripper（MIT）。

## 3. 独立实现声明

- BiliBTR 不含 PiliPlus 的任何代码。两者语言不同（Dart / Flutter 与 Java / Kotlin）、
  目标宿主不同（第三方客户端与官方客户端），无代码复用。
- 本项目不是 PiliPlus 的衍生作品，未复制任何 GPL 代码。
- 本项目采用 GPL-3.0 属**自愿选择**，非许可传染所致。
- 对第三方的引用限于思想、架构与参数，并在此署名。
- 若署名方式有误，请提 Issue，我们将立即更正。

## 4. 本项目

| 项 | 内容 |
| --- | --- |
| 项目 | BiliBTR |
| 开发者 | **lwjlw** |
| 包名 | `com.lw5.bilibtr` |
| 许可 | GPL-3.0（见 [`LICENSE`](LICENSE)） |
| 实现 | 代码主要由 AI 编程助手编写；需求、设计取舍与真机验收由项目所有者完成 |
| 详见 | [`README.md`](README.md) / [`README.en.md`](README.en.md) 的「贡献声明 / Contribution Statement」 |

---

*最后更新：2026-10-06*
