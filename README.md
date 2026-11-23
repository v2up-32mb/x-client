# X Client

[简体中文](README.md) | [English](README_en.md) | [繁體中文](README_zh-TW.md) | [Русский](README_ru.md)

---

[![Release](https://img.shields.io/github/v/release/v2up-32mb/x-client?color=blue&logo=github)](https://github.com/v2up-32mb/x-client/releases)
[![API](https://img.shields.io/badge/API-24%2B-brightgreen.svg?logo=android)](https://android-arsenal.com/api?level=24)
[![Go Version](https://img.shields.io/badge/Go-1.25%2B-00ADD8.svg?logo=go)](https://golang.org)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

X Client 是一个专为 Android 打造的高性能多协议 VPN 客户端。每个配置节点（Profile）均可独立指定代理协议，并共享同一套成熟的 VPN 隧道框架、优选中转节点体系、路由分流绕过与全局设置。

- **多协议扩展架构**：基于 [gcm-client](https://github.com/v2up-32mb/gcm-client) 演进重构，支持 GCM 与 X-Tunnel 双协议。
- **Go 原生核心引擎**：网络核心基于 Go 语言编写（位于 `golib/`，module `xclient`），通过 `gomobile bind` 编译生成原生动态库 `xclient.aar`。
- **高效 TUN 转发**：VPN 隧道基于高性能 [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) 实现，将 TUN 流量以近原生性能重定向至核心库本地 SOCKS5 代理。

---

## 功能特性

- **多协议支持**：GCM / X-Tunnel 双协议共存，每个 Profile 独立配置、按需切换。
- **全界面多语言（i18n）**：
  - 支持 **English**、**简体中文**、**繁體中文**、**Русский** 四种语言；
  - 自动匹配系统语言，未适配语言默认以英文作为通用兜底；
  - 全局设置首项提供「应用语言」选择，切换即时生效，无需重启应用。
- **全球优选 IP 系统**：
  - **多渠道来源**：内置 Cloudflare 官方 IPv4/IPv6 CIDR 地址段智能抽样、第三方列表订阅及自定义手动/TXT 批量录入；
  - **真目标验证**：以真实目标节点验证握手与 WebSocket 连通性，摒弃单一 HTTPS 伪握手；
  - **轻量内存测速账本**：支持单节点 10 秒 50MiB 极速测速与自动流量控制；
  - **全局接管机制**：支持「跟随全局」与「独立配置」模式无缝衔接，配置变更自动适配。
- **ECH + DoH 前沿防封锁**：
  - DoH 多服务器 Fallback → UDP DNS（8.8.8.8:53）→ 标准 TLS 1.3 三级健壮回退体系；
  - 彻底阻断基于 SNI 的域名探测与阻断。
- **高性能连接池与通道调度**：
  - WebSocket 连接多路复用与动态池扩容机制；
  - 中转节点（Relay）延迟加权评分与负载均衡；
  - 支持连接质量实时监控与自适应网络重连。
- **精细化路由与分流**：
  - 本地与局域网（LAN）私有地址直连绕过；
  - 内置 GeoIP:CN 与 GeoSite:CN 大陆直连分流；
  - 支持用户自定义 CIDR / 域名绕过规则及强制走代理规则。
- **分应用代理（Per-App Proxy）**：
  - 自由勾选需要经过 VPN 的应用程序，支持关键字搜索、全选与反选。
- **代理延迟测试**：
  - 内置网络探测机制，支持自定义测速目标 URL（默认 gstatic 204）与探测间隔。
- **现代化 Material 3 设计**：
  - 提供「赛博极光」、「钛金极简」、「瑞士暗盾」三套精致主题调色板；
  - 五态动态连接悬浮按钮（Connection FAB），支持长按展开状态卡与单行定速信息展示；
  - Profile 卡片次级弹出菜单（切换/编辑/分享/复制/删除）与丰富空态引导。
- **运行日志诊断**：
  - 内存环形日志缓冲，时间戳与 Android 系统时区自动对齐；
  - 调试 / 信息 / 警告 / 错误 4 档过滤，支持一键导出与剪贴板复制。

---

## 协议能力对比

| 核心特性 | GCM 协议 | X-Tunnel 协议 |
|---|---|---|
| **协议包头** | 2 字节（`[STREAM_ID:1][TYPE:1]`） | 8 字节（`connID` + `msgType`） |
| **多路复用** | 256 流/WS 连接，连接池统一扩容 | 每个通道拥有独立 `connID` 空间 |
| **通道调度** | 中转节点评分 + 加权负载均衡 | 通道竞争选路 + Hot Pair 热通道预绑定 |
| **UDP 转发** | 暂不支持 | 完整支持 SOCKS5 UDP associate 转发 |
| **HTTP 代理** | 流内隧道传输 | 支持独立本地 HTTP 代理监听器 |
| **路由绕过** | ✅ 支持本地/GeoIP/GeoSite/自定义 | ✅ 支持（SOCKS5 与 HTTP 直连分流） |
| **ECH / DoH** | ✅ 共享 Go 核心防探测模块 | ✅ 共享 Go 核心防探测模块 |

---

## 下载与安装

请前往 [GitHub Releases](https://github.com/v2up-32mb/x-client/releases) 下载最新签名的 Release APK 安装包：

| APK 文件名 | 适用架构与设备 |
|---|---|
| `x-client-arm64-v8a-release.apk` | 64 位 ARM 架构（绝大多数现代 Android 手机与平板，**推荐**） |
| `x-client-armeabi-v7a-release.apk` | 32 位 ARM 架构（较早期的 Android 老设备） |
| `x-client-x86_64-release.apk` | 64 位 x86 架构（电脑 Android 模拟器、ChromeOS） |
| `x-client-x86-release.apk` | 32 位 x86 架构（32 位 PC 模拟器） |
| `x-client-universal-release.apk` | 全架构整合版（体积较大，自动兼容任意支持设备） |

---

## 配置格式与 URI 规范

X Client 支持通过扫码或剪贴板一键导入标准 URI：

### GCM 协议 URI
```
gcm://<workerHost>?ip=<优选中转IP:端口>&fip=<出口代理IP>&user_id=<用户ID>&dns=<DoH服务器>&domain=<ECH查询域名>&disable_ech=1#<配置名称>
```

### X-Tunnel 协议 URI
```
xtunnel://<serverAddr>?token=<Token>&relay_nodes=<中转节点>&connections=<连接数>&ech=0|1&domain=<ECH域名>&dns=<DoH服务器>&insecure=1&hotpair=<通道对数1-8>&ips=<策略>#<配置名称>
```

- **兼容说明**：`ech://` 前缀链接保持向后兼容支持；
- **Hot Pair**：`hotpair=1`（或 `true`）启用 1 对暖备用通道；`hotpair=2..8` 启用多对热通道；
- **IP 策略**：支持 `IPv4,IPv6`（默认）、`IPv6,IPv4`、`IPv4`、`IPv6` 四种出站解析优选规则。

---

## 项目架构

```
x-client/
├── app/                  # Android 应用源码（Java 17，Material 3，VpnService）
│   ├── src/main/java/com/x/client/app/   # UI、服务、管理器与持久化层
│   │   ├── LanguageManager.java          # 多语言即时应用调度
│   │   ├── ThemeManager.java             # 调色板与暗黑模式管理
│   │   ├── TProxyService.java            # VPN 前台服务（:vpn 独立进程）
│   │   └── ...
│   ├── src/main/res/                     # 资源目录
│   │   ├── values/                       # 默认回退资源（English 英文）
│   │   ├── values-zh-rCN/                # 简体中文资源
│   │   ├── values-zh-rTW/                # 繁體中文资源
│   │   ├── values-ru/                    # 俄语资源（Русский）
│   │   └── ...
│   └── libs/xclient.aar  # gomobile 编译产物（CI 自动构建注入）
├── golib/                # Go 核心网络库（module xclient，Go 1.25.5）
│   ├── gcm/              # GCM 协议后端（连接池、多路复用与 Relay 优选）
│   ├── xtunnel/          # X-Tunnel 协议后端（通道竞争、Hot Pair 与背压）
│   ├── ipopt/            # 优选 IP 算法、探测与网络扫描核心
│   └── shared/           # 共享基础模块（DNS、ECH、Logger、路由、SOCKS5）
└── .github/workflows/    # CI 自动化流水线（AAR 预热、构建、签名与发版）
```

### 核心流量流程

```
[ 应用流量 (App Traffic) ]
            │
            ▼
[ Android VpnService TUN 设备 ]
            │
            ▼
[ hev-socks5-tunnel (C / JNI) ]
            │ (TUN 流量转换转为 SOCKS5)
            ▼
[ 本地 SOCKS5 代理 (Go / golib 监听 127.0.0.1:1080) ]
       │                                     │
       ├─ [ 路由命中绕过 (Bypass) ]          └─ [ 代理转发 (Proxy) ]
       │        │                                    │
       ▼        ▼                                    ▼
  [ 直连目标地址 (Direct) ]                [ Go 协议后端 (GCM / X-Tunnel) ]
                                                     │
                                                     ▼
                                      [ ECH / TLS 1.3 加密隧道 ]
                                                     │
                                                     ▼
                                            [ 出口服务端 / Worker ]
```

---

## 构建与开发

> **约束提示**：本项目 Android APK 编译与发布由 GitHub Actions 自动化流水线统一完成，本地仅需对 Go 核心库进行单元测试与逻辑验证。

### 本地 Go 核心测试

```bash
cd golib
# 运行全部 Go 核心库单元测试（含竞态检测）
CGO_ENABLED=1 go test -race -count=1 ./...
```

### CI 自动化工作流

- **AAR 预热 (`build-aar.yml`)**：监听 `main` 分支 `golib/**` 变更，自动触发 gomobile 交叉编译并将 `xclient.aar` 产物落入全局构建缓存。
- **Release 发布 (`release.yml`)**：推送 `v*` 语义化标签自动触发全架构 Release 构建、对齐签名并自动生成 GitHub Release（带 `-preview` / `-beta` / `-rc` 后缀自动标记预发布）。

---

## 许可证

本项目基于 [Apache License 2.0](LICENSE) 协议开源。
