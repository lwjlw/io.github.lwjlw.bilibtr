# BiliBTR

**为哔哩哔哩官方安卓客户端提供多 CDN 并发加速的 LSPosed 模块。**
**An LSPosed module that accelerates video playback in the official Bilibili Android client via multi-CDN concurrent range downloading.**

![Platform](https://img.shields.io/badge/Platform-Android%2012%2B-green.svg)
![Framework](https://img.shields.io/badge/Framework-LSPosed%20(API%20102)-blue.svg)
![Language](https://img.shields.io/badge/Language-Java%20%2F%20Kotlin-orange.svg)
![License](https://img.shields.io/badge/License-GPLv3-blue.svg)

[**English**](README.md) · [**中文**](README.zh-CN.md)

---

## 简介

BiliBTR 将 [Bilibili-thread-ripper](https://github.com/MrTangLuyao/Bilibili-thread-ripper)
的「多 CDN 并发 Range 下载」策略移植到安卓端，作用于**哔哩哔哩客户端**。

它**不能解锁任何内容**：只是将播放器发起的视频字节请求接管到本机代理，
由代理去**挑选更快的 CDN 节点、并发拉取、提前预读**，再把数据交给播放器，**只优化用户本来就有权访问的字节**。

> 本项目基于 **LSPosed API 102**。

## 功能特性

### 网络加速

| 特性 | 说明 |
| --- | --- |
| **多 CDN 节点优选** | 测速候选节点，使用实测最快 |
| **多连接并发** | 一个 Range 请求切成多段并行拉取；**并发数按码率自动调整**（低码率自动降到 1 条） |
| **边收边发** | 拿到上游响应头就立刻回给播放器，显著加快起播 |
| **预读缓存** | 后台向前预读，播放器后续请求直接命中内存 |
| **连接复用** | 到 CDN 的连接池复用，降低每个请求的往返开销 |
| **失败降级** | 连续失败自动暂停并发、走单连接，适时自动恢复 |

### 图形界面

- **测速面板**：实时显示吞吐、卡顿次数、并发数、首字节时间、各节点实际用量
- **节点管理**：一键对**全部候选节点测速**并列出结果，可**点击切换**或保持自动
- **缓冲调节**：**缓冲大小 / 缓冲时长**手动调节
- **播放页悬浮球**：**3x / 4x 倍速**，无操作自动隐藏

![alt text](screenshot-1.jpg)


## 环境要求

| 项 | 要求 |
| --- | --- |
| 系统 | Android 12 及以上（`minSdk 31`） |
| 框架 | **LSPosed**（API 102） |
| 宿主 | 哔哩哔哩客户端（tv.danmaku.bili)（开发验证版本 **9.8.0**） |

> 模块**不会**申请悬浮窗权限。

## 安装

1. 下载并安装 `app-release.apk`（或自行构建，见下）；
2. 打开 **LSPosed 管理器** → 模块 → 启用 **BiliBTR**；
3. **作用域勾选「哔哩哔哩」**（`tv.danmaku.bili`）；
4. 打开 **BiliBTR** App 进行配置。

## 使用

### 基本

1. 打开 BiliBTR，确认“已连接 B站 进程”（需要哔哩哔哩正在运行）；
2. 播放任意视频；
3. 回到 BiliBTR 查看**测速面板**，确认有数据（吞吐、节点、卡顿等）。

### 节点管理

- **自动选择**（默认开启）：模块持续使用**真实下载速度**给每个节点打分，自动切换最快节点；
- **手动切换**：点「对全部节点测速」，等待结束后，**点任意节点**即锁定该节点；
  再点「取消锁定」或打开「自动选最快」可恢复；
- 界面上「实际走」是模块真正使用的地址，「B站给的」是客户端交给播放器的地址。

### 缓冲设置

- **缓冲大小**：对应播放器的 `max-buffer-size`。实测哔哩哔哩**默认已经是 150 MB**；
- **缓冲时长**：对应播放器的高/低水位 ——官方默认不设。
  线路抖动时，建议调到 **30~60 秒**。0 = 不干预。

### 悬浮球倍速

播放视频时：

1. **点一下画面** → 右侧出现 `3x` `4x` `跟` 三个按钮；
2. **按住可拖动**到任意位置，**无操作自动隐藏**；
3. 点 `3x` / `4x` → 强制该倍速；点 `跟` → 恢复哔哩哔哩自己的倍速；
4. 不需要时可在 App 里**关闭悬浮球**。

> 注意：在强制更改倍速后，哔哩哔哩界面上的倍速**仍显示原值**。

## 工作原理

```
哔哩哔哩客户端
      │  ① 播放地址被改写成 http://127.0.0.1:18888/media?u=<原始地址>
      ▼
  本机代理（运行在哔哩哔哩进程内）
      ├─ 候选节点：上游给的地址 ∪ 备用地址 ∪ 同家族换主机名
      ├─ 节点优选：真实流量 EWMA 打分 + 锚点优先 + 防抖
      ├─ 并发拉取：按码率自动决定开几条连接
      ├─ 预读缓存：提前把后面的数据拉进内存
      └─ 连接复用：到 CDN 的连接池
      │  ② 数据原样回给播放器（标准 206 + Range）
      ▼
哔哩哔哩播放器（IJK / ffmpeg）
```

模块 App 与哔哩哔哩进程之间通过**回环 TCP（`127.0.0.1:18889`）**通信：
App 下发设置、读取测速数据；注入侧上报状态。

> 为什么不用 Android 的 ContentProvider：Android 11+ 的**软件包可见性**会让哔哩哔哩进程
> 看不到本模块的 provider（`Unknown authority`），而回环 TCP 没有这个限制。

## 从源码构建

需要 JDK 17+、Android SDK（platform 37）。

```bash
git clone <this-repo>
cd io.github.lwjlw.bilibtr
./gradlew :app:assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

安装：

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

## 常见问题

**Q：测速面板显示"未连接"？**
A：说明哔哩哔哩没有在运行。先播放一个视频，再回到 BiliBTR 即可。

**Q：支持其它版本的哔哩哔哩吗？**
A：开发与验证基于 **9.8.0**。其它版本可能失效，可提 Issue 反馈。

**Q：会更耗流量吗？**
A：预读与并发会略微增加流量（提前下载 + 少量探测），总体上与直连相当。

## 贡献声明

- 实现代码主要由 DeepSeek Harness 编写；需求、设计取舍与真机验收由项目所有者完成。
- 关键结论均来自**真机实测**。
- 请将 AI 生成的代码视为**需要审查的第三方代码**，建议先阅读 `proxy/` 下的调度与并发逻辑。
- 欢迎提出问题或改进意见。

## 致谢与出处

| 项目 | 许可 | 说明 |
| --- | --- | --- |
| [Bilibili-thread-ripper](https://github.com/MrTangLuyao/Bilibili-thread-ripper) | MIT | 被移植的源项目；并发 Range 下载的原理与默认参数来自它 |
| [PiliPlus（`btr` 分支）](https://github.com/nishuodedui1145-del/PiliPlus) | GPL-3.0 | 本项目参考了它的本地代理架构与部分调度参数 |

BiliBTR 是面向官方客户端的独立实现（Java / Kotlin），不含上述项目的任何代码，亦非其衍生作品。
第三方署名详见 [`NOTICE.md`](NOTICE.md)。

## 免责声明

- 本项目**仅供学习与技术研究**， 所用 API 皆从官方网站收集，不提供、不修改、不解锁任何付费或会员内容；

## 许可证

[GPL-3.0](LICENSE) © 2026 [lwjlw](https://github.com/lwjlw)

```
This program is free software: you can redistribute it and/or modify it under
the terms of the GNU General Public License as published by the Free Software
Foundation, either version 3 of the License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY
WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
PARTICULAR PURPOSE.  See the GNU General Public License for more details.
```
