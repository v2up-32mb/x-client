# X Client

[繁體中文](README_zh-TW.md) | [English](README_en.md) | [简体中文](README.md) | [Русский](README_ru.md)

---

[![Release](https://img.shields.io/github/v/release/v2up-32mb/x-client?color=blue&logo=github)](https://github.com/v2up-32mb/x-client/releases)
[![API](https://img.shields.io/badge/API-24%2B-brightgreen.svg?logo=android)](https://android-arsenal.com/api?level=24)
[![Go Version](https://img.shields.io/badge/Go-1.27-00ADD8.svg?logo=go)](https://golang.org)
[![License](https://img.shields.io/badge/License-Apache%202.0%20%2F%20MIT-blue.svg)](LICENSE)

X Client 是一個專為 Android 打造的高效能多協定 VPN 用戶端。每個設定節點（Profile）均可獨立指定代理協定，並共享同一套成熟的 VPN 通道架構、優選中轉節點體系、路由分流繞過與全域設定。

- **多協定擴充架構**：基於 [gcm-client](https://github.com/v2up-32mb/gcm-client) 演進重構，支援 GCM、X-Tunnel 與 SOCKS5 三種協定（GCM 與 X-Tunnel 用於加密 WebSocket 通道；SOCKS5 用於直連既有上游 SOCKS5 伺服器，支援選填帳號密碼驗證、TCP 中繼轉送與本機分流繞過）。
- **Go 原生核心引擎**：網路核心採用 Go 語言編寫（位於 `golib/`，module `xclient`），透過 `gomobile bind` 編譯產出原生動態庫 `xclient.aar`。
- **高效 TUN 轉送**：VPN 通道基於高效能 [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) 實現，將 TUN 流量以近原生效能重新導向至核心庫本機 SOCKS5 代理。

---

## 功能特性

- **多協定支援**：支援 **GCM**、**X-Tunnel** 與 **SOCKS5** 三種協定共存，每個 Profile 獨立設定、隨選切換；其中 SOCKS5 可直連既有上游 SOCKS5 伺服器，支援選填帳號/密碼驗證、TCP 轉送與本機分流繞過。
- **完整多語言支援（i18n）**：
  - 支援 **English**、**简体中文**、**繁體中文**、**Русский** 四種語言；
  - 自動比對系統語言，未適配語言預設以英文作為通用備援；
  - 全域設定首項提供「應用程式語言」選擇，切換即時生效，無需重新啟動應用程式。
- **全球優選 IP 系統**：
  - **多元來源渠道**：內建 Cloudflare 官方 IPv4/IPv6 CIDR 位址段智慧抽樣、第三方清單訂閱及自訂手動/TXT 批次匯入；
  - **真目標驗證**：以真實目標節點驗證交握與 WebSocket 連通性，摒棄單純 HTTPS 虛假交握；
  - **輕量記憶體測速帳本**：支援單節點最多 10 秒 / 50MiB 測速、輪次流量預算控管與安全檢核；
  - **全域接管機制**：支援「跟隨全域」與「獨立設定」模式無縫切換，設定變更自動調適。
- **ECH + DoH 前沿防封鎖**：
  - DoH 多伺服器 Fallback → UDP DNS（8.8.8.8:53）→ 標準 TLS 1.3 三級穩健回退體系；
  - 徹底阻斷基於 SNI 的網域名稱探測與阻斷。
- **高效能連線集區與通道排程**：
  - WebSocket 連線多路複用與動態集區擴展機制；
  - 中轉節點（Relay）延遲加權評分與負載平衡；
  - 支援連線品質即時監控與適應性網路自動重新連線。
- **精細化路由與分流**：
  - 本地與區域網路（LAN）私有位址直連分流繞過；
  - 內建 GeoIP:CN 與 GeoSite:CN 大陸直連分流；
  - 支援使用者自訂 CIDR / 網域繞過規則與強制走代理規則。
- **分應用代理（Per-App Proxy）**：
  - 自由勾選需經過 VPN 的應用程式，支援關鍵字搜尋、全選與反選。
- **代理延遲測試**：
  - 內建網路探測機制，支援自訂測速目標 URL（預設 gstatic 204）與探測間隔。
- **現代化 Material 3 設計**：
  - 提供「賽博極光」、「鈦金極簡」、「瑞士暗盾」三套精緻主題調色盤，並支援淺色/深色模式獨立儲存調色盤偏好；
  - 五態動態連線懸浮按鈕（Connection FAB），支援長按展開狀態卡與單行定速資訊展示；
  - Profile 卡片次級彈出選單（切換/編輯/分享/複製/刪除）與豐富的空態指引；
  - 設定頁「關於」區塊動態顯示實際編譯的 Go 核心版本。
- **Android TV 與橫螢幕適配**：
  - 取消觸控螢幕與相機硬體強制依賴（`required="false"`），提供專屬 Android TV 啟動器橫幅（`LEANBACK_LAUNCHER`）；
  - 支援遙控器方向鍵（D-pad）/ OK 鍵一鍵啟停 VPN、長按開啟操作選單，並具備完整焦點導覽與微縮放高光回饋；
  - 橫螢幕自動切換多欄網格排版，並完整適配異形全螢幕與邊緣安全內距（Edge-to-Edge Safe Insets）。
- **應用程式內更新（In-App Update）**：
  - 自動檢查 GitHub Releases 新版本，並連動 VPN 路由下載符合裝置架構（ABI）的 APK 安裝檔；
  - 自動清理已下載的歷史更新 APK 檔案以節省儲存空間。
- **執行記錄診斷**：
  - 記憶體環形記錄緩衝區，時間戳記與 Android 系統時區自動對齊；
  - 除錯 / 資訊 / 警告 / 錯誤 4 級過濾，支援一鍵匯出與剪貼簿複製。

---

## 協定能力對比

| 核心特性 | GCM 協定 | X-Tunnel 協定 | SOCKS5 協定 |
|---|---|---|---|
| **封包標頭開銷** | 2 位元組（`[STREAM_ID:1][TYPE:1]`） | 8 位元組（`connID` + `msgType`） | 標準 SOCKS5（RFC 1928 / RFC 1929） |
| **多路複用** | 256 串流/WS 連線，連線集區統一擴展 | 每個通道擁有獨立 `connID` 空間 | 隨選直連上游 TCP 撥號（單連線 1:1 追蹤） |
| **通道排程** | 中轉節點評分 + 加權負載平衡 | 通道競爭選路 + Hot Pair 暖備用通道預綁定 | 直連上游節點；支援選填帳號/密碼驗證 |
| **UDP 轉送** | 暫不支援 | 完整支援 SOCKS5 UDP associate 轉送 | 第一階段暫不支援（回覆 `0x07`） |
| **HTTP 代理** | 串流內通道傳輸 | 支援獨立本機 HTTP 代理監聽器 | 無獨立 HTTP 監聽器 |
| **路由分流** | ✅ 支援本地/GeoIP/GeoSite/自訂規則 | ✅ 支援（SOCKS5 與 HTTP 直連分流） | ✅ 支援（本地/GeoIP/GeoSite/自訂/強制代理） |
| **ECH / DoH** | ✅ 共享 Go 核心防探測模組 | ✅ 共享 Go 核心防探測模組 | 不使用（直連上游伺服器） |

---

## 下載與安裝

請前往 [GitHub Releases](https://github.com/v2up-32mb/x-client/releases) 下載最新簽名的 Release APK 安裝檔：

| APK 檔名 | 適用架構與裝置 |
|---|---|
| `x-client-arm64-v8a-release.apk` | 64 位元 ARM 架構（絕大多數現代 Android 手機與平板，**推薦**） |
| `x-client-armeabi-v7a-release.apk` | 32 位元 ARM 架構（較早期的 Android 舊裝置） |
| `x-client-x86_64-release.apk` | 64 位元 x86 架構（電腦 Android 模擬器、ChromeOS） |
| `x-client-x86-release.apk` | 32 位元 x86 架構（32 位元 PC 模擬器） |
| `x-client-universal-release.apk` | 全架構整合版（檔案較大，自動相容任何支援裝置） |

---

## 設定格式與 URI 規範

X Client 支援透過掃描行動條碼或剪貼簿一鍵匯入標準 URI：

### GCM 協定 URI
```
gcm://<workerHost>?ip=<優選中轉IP:連接埠>&fip=<出口代理IP>&user_id=<使用者ID>&dns=<DoH伺服器>&domain=<ECH查詢網域>&disable_ech=1#<設定名稱>
```

### X-Tunnel 協定 URI
```
xtunnel://<serverAddr>?token=<Token>&relay_nodes=<中轉節點>&connections=<連線數>&ech=0|1&domain=<ECH網域>&dns=<DoH伺服器>&insecure=1&hotpair=<通道對數1-8>&ips=<策略>#<設定名稱>
```

### SOCKS5 協定 URI
```
socks5://[username[:password]@]host:port#<設定名稱>
```

- **相容性說明**：GCM 維持支援 `ech://` 前置詞連結向下相容；匯入 SOCKS5 時同時相容 `socks://` 前置詞；
- **SOCKS5 解析**：支援 IPv6 `[host]:port` 格式，省略連接埠時預設為 `1080`；
- **Hot Pair**：`hotpair=1`（或 `true`）啟用 1 對暖備用通道；`hotpair=2..8` 啟用多對熱通道；
- **IP 策略**：支援 `IPv4,IPv6`（預設）、`IPv6,IPv4`、`IPv4`、`IPv6` 四種出站解析優先規則。

---

## 專案架構

```
x-client/
├── app/                  # Android 應用程式原始碼（Java 17，Material 3，VpnService）
│   ├── src/main/java/com/x/client/app/   # UI、服務、管理器與持久化層
│   │   ├── LanguageManager.java          # 多語言即時應用調度
│   │   ├── ThemeManager.java             # 亮/暗模式獨立調色盤與深色模式管理
│   │   ├── TProxyService.java            # VPN 前台服務（:vpn 獨立處理程序）
│   │   ├── TvFocusHelper.java            # Android TV 遙控器焦點高光輔助
│   │   ├── UpdateManager.java            # GitHub Release 版本檢測、下載與清理
│   │   └── ...
│   ├── src/main/res/                     # 資源目錄
│   │   ├── values/                       # 預設回退資源（English 英文）
│   │   ├── values-zh-rCN/                # 簡體中文資源
│   │   ├── values-zh-rTW/                # 繁體中文資源
│   │   ├── values-ru/                    # 俄語資源（Русский）
│   │   └── ...
│   └── libs/xclient.aar  # gomobile 編譯產物（CI 自動建置注入）
├── golib/                # Go 核心網路庫（module xclient，Go 1.27）
│   ├── android.go        # gomobile 入口與協定分派
│   ├── gcm/              # GCM 協定後端轉接器（gcmlib 裝配與繞過選項）
│   ├── xtunnel/          # X-Tunnel 協定後端轉接器（xtlib 裝配與繞過選項）
│   ├── socks5/           # SOCKS5 協定後端（RFC 1928/1929 上游 TCP 中繼與繞過）
│   └── ipopt/            # 優選 IP 演算法、探測與網路掃描核心
│   # 註：共享基礎模組（config/dialer/dns/ech/httpproxy/logger/pipe/routing/socks5）
│   # 均由上游函式庫 github.com/v2up-32mb/xshared 提供
└── .github/workflows/    # CI 自動化工作流程（AAR 快取預熱、建置、簽署與發布）
```

### 核心流量路徑

```
[ 應用程式流量 (App Traffic) ]
               │
               ▼
[ Android VpnService TUN 裝置 ]
               │
               ▼
[ hev-socks5-tunnel (C / JNI) ]
               │ (TUN 流量轉換為 SOCKS5)
               ▼
[ 本機 SOCKS5 代理 (Go / golib 監聽 127.0.0.1:1080) ]
       │                                      │
       ├─ [ 路由命中分流繞過 (Bypass) ]       └─ [ 代理後端分派 (Proxy Dispatch) ]
       │        │                                    ├──▶ [ GCM / X-Tunnel ] ──▶ [ ECH / TLS 1.3 ] ──▶ [ 遠端伺服器 / Worker ]
       ▼        ▼                                    └──▶ [ SOCKS5 ] ──────────▶ [ 上游 SOCKS5 代理伺服器 ]
  [ 直連目標位址 (Direct) ]
```

---

## 建置與開發

> **規範提示**：本專案 Android APK 編譯與發布統一由 GitHub Actions 自動化工作流程完成，本地僅需針對 Go 核心庫進行單元測試與邏輯驗證。

### 本地 Go 核心測試

```bash
cd golib
# 執行全部 Go 核心庫單元測試（包含競爭狀態偵測）
CGO_ENABLED=1 go test -race -count=1 ./...
```

### GitHub Actions CI 工作流程

- **AAR 快取預熱 (`build-aar.yml`)**：監聽 `main` 分支 `golib/**` 變更（或手動觸發），自動觸發 gomobile 交叉編譯並將 `xclient.aar` 產物存入全域建置快取。
- **Release 與 CI 驗證 (`release.yml`)**：
  - **標籤觸發 (`v*`)**：推送語意化標籤自動觸發全架構 Release 建置、對齊簽署並建立 GitHub Release（附帶 `-preview` / `-beta` / `-rc` 後置詞將自動標記為預發布）。
  - **手動觸發 (`workflow_dispatch`)**：執行 Go `-race` 單元測試、`gomobile`/API 檢查，並建置已簽署的 `com.x.client.app.debug` APK（版本名稱 `ci-<shortsha>`），可與正式版並存安裝測試，不會建立 Release。

---

## 授權條款

本專案採用 **Apache License 2.0 OR MIT License** 雙重授權條款開放原始碼（詳見 [LICENSE](LICENSE)）。
