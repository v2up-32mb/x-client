# X Client

[English](README_en.md) | [简体中文](README.md) | [繁體中文](README_zh-TW.md) | [Русский](README_ru.md)

---

[![Release](https://img.shields.io/github/v/release/v2up-32mb/x-client?color=blue&logo=github)](https://github.com/v2up-32mb/x-client/releases)
[![API](https://img.shields.io/badge/API-24%2B-brightgreen.svg?logo=android)](https://android-arsenal.com/api?level=24)
[![Go Version](https://img.shields.io/badge/Go-1.25%2B-00ADD8.svg?logo=go)](https://golang.org)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

X Client is a high-performance multi-protocol VPN client designed for Android. Each configuration profile can independently select its proxy protocol while sharing a mature, unified VPN tunneling framework, optimal relay node management, routing bypass rules, and global settings.

- **Multi-Protocol Architecture**: Built and evolved upon [gcm-client](https://github.com/v2up-32mb/gcm-client), supporting both GCM and X-Tunnel protocols.
- **Go Native Core**: Core network engine written in Go (`golib/`, module `xclient`), compiled to a native library `xclient.aar` via `gomobile bind`.
- **High-Performance TUN Forwarding**: VPN tunnel powered by [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel), forwarding TUN traffic to the local SOCKS5 proxy at near-native performance.

---

## Features

- **Multi-Protocol**: Full support for GCM and X-Tunnel protocols, configurable per profile.
- **Comprehensive Multi-Language Support (i18n)**:
  - Supports **English**, **Simplified Chinese** (简体中文), **Traditional Chinese** (繁體中文), and **Russian** (Русский);
  - Automatically matches the device's system language, falling back to English for unsupported locales;
  - Dynamic in-app language switching in Global Settings, applying changes immediately without restarting the app.
- **Global Optimal IP System**:
  - **Multiple Sources**: Intelligent CIDR sampling from official Cloudflare IPv4/IPv6 ranges, third-party subscription lists, and manual/TXT batch import;
  - **Actual Target Verification**: Validates handshakes and WebSocket upgrades against the user's real profile target, avoiding false-positive HTTPS tests;
  - **In-Memory Speed Test Ledger**: 10-second 50MiB per-node speed testing with automatic bandwidth throttling and safety checks;
  - **Adaptive Takeover**: Seamless switching between "Follow Global" and "Custom Configuration" modes.
- **Advanced ECH & DoH Anti-Censorship**:
  - Robust 3-stage fallback: DoH multi-server fallback → UDP DNS (8.8.8.8:53) → Standard TLS 1.3;
  - Prevents SNI-based domain snooping and blocking.
- **High-Performance Connection Pooling**:
  - Multiplexed WebSocket connections with dynamic pool auto-scaling;
  - Weighted relay node scoring and load balancing;
  - Real-time connection quality monitoring and adaptive network failover.
- **Smart Routing & Bypass**:
  - Direct bypass for local and private LAN addresses;
  - Built-in GeoIP:CN and GeoSite:CN direct bypass rules;
  - Custom user rules for domain and CIDR bypass, as well as force-proxy domains.
- **Per-App Proxy**:
  - Fine-grained control over which installed applications route traffic through the VPN, with search, select-all, and invert-selection features.
- **Proxy Latency Testing**:
  - Built-in latency probe supporting custom test URLs (default `gstatic 204`) and configurable test intervals.
- **Material 3 Design & UI**:
  - 3 custom color palettes: Cyber Aurora, Titanium Minimal, and Swiss Dark Shield;
  - 5-state dynamic floating action button (Connection FAB) with expandable status cards and marquee detail display;
  - Swipe reveal action menus for profiles (Pin / Edit / Share / Copy / Delete) and contextual empty state prompts.
- **Diagnostics & Runtime Logs**:
  - In-memory ring buffer with timestamps synchronized to the Android system timezone;
  - 4 verbosity levels (Debug, Info, Warn, Error) with one-tap clipboard export.

---

## Protocol Comparison

| Core Feature | GCM Protocol | X-Tunnel Protocol |
|---|---|---|
| **Header Overhead** | 2 bytes (`[STREAM_ID:1][TYPE:1]`) | 8 bytes (`connID` + `msgType`) |
| **Multiplexing** | 256 streams per WS, shared connection pool | Dedicated `connID` space per channel |
| **Channel Scheduling** | Relay node scoring + weighted balancing | Channel race routing + Hot Pair pre-binding |
| **UDP Forwarding** | Not supported | Full SOCKS5 UDP associate support |
| **HTTP Proxy** | In-stream transport | Independent local HTTP proxy listener |
| **Route Bypass** | ✅ LAN / GeoIP / GeoSite / Custom rules | ✅ Supported (SOCKS5 & HTTP direct bypass) |
| **ECH / DoH** | ✅ Shared Go anti-censorship module | ✅ Shared Go anti-censorship module |

---

## Download & Installation

Download the latest signed Release APK from [GitHub Releases](https://github.com/v2up-32mb/x-client/releases):

| APK Name | Target Architecture & Devices |
|---|---|
| `x-client-arm64-v8a-release.apk` | 64-bit ARM (most modern Android phones & tablets, **recommended**) |
| `x-client-armeabi-v7a-release.apk` | 32-bit ARM (older Android devices) |
| `x-client-x86_64-release.apk` | 64-bit x86 (Android emulators, ChromeOS) |
| `x-client-x86-release.apk` | 32-bit x86 (legacy PC emulators) |
| `x-client-universal-release.apk` | All-in-one universal package (larger file size, compatible with all devices) |

---

## Configuration & URI Specifications

X Client supports importing profiles via QR code scan or clipboard:

### GCM Protocol URI
```
gcm://<workerHost>?ip=<relayIP:port>&fip=<egressProxyIP>&user_id=<userID>&dns=<dohServer>&domain=<echDomain>&disable_ech=1#<ProfileName>
```

### X-Tunnel Protocol URI
```
xtunnel://<serverAddr>?token=<token>&relay_nodes=<nodes>&connections=<count>&ech=0|1&domain=<echDomain>&dns=<dohServer>&insecure=1&hotpair=<pairs 1-8>&ips=<strategy>#<ProfileName>
```

- **Backward Compatibility**: `ech://` scheme links remain fully supported;
- **Hot Pair**: `hotpair=1` (or `true`) enables 1 warm standby channel pair; `hotpair=2..8` enables multiple pairs;
- **IP Strategy**: Supports `IPv4,IPv6` (default), `IPv6,IPv4`, `IPv4`, and `IPv6` outbound resolution preferences.

---

## Project Structure

```
x-client/
├── app/                  # Android application source (Java 17, Material 3, VpnService)
│   ├── src/main/java/com/x/client/app/   # UI, services, managers, and storage layer
│   │   ├── LanguageManager.java          # In-app dynamic locale management
│   │   ├── ThemeManager.java             # Color palette and night mode management
│   │   ├── TProxyService.java            # Foreground VPN service (:vpn isolated process)
│   │   └── ...
│   ├── src/main/res/                     # Resource directories
│   │   ├── values/                       # Default fallback resources (English)
│   │   ├── values-zh-rCN/                # Simplified Chinese resources
│   │   ├── values-zh-rTW/                # Traditional Chinese resources
│   │   ├── values-ru/                    # Russian resources (Русский)
│   │   └── ...
│   └── libs/xclient.aar  # gomobile compiled binary (injected by CI)
├── golib/                # Go core library (module xclient, Go 1.25.5)
│   ├── gcm/              # GCM backend (connection pool, multiplexing, relay scoring)
│   ├── xtunnel/          # X-Tunnel backend (channel race, Hot Pair, backpressure)
│   ├── ipopt/            # Optimal IP algorithms, probing, and subnet scanner
│   └── shared/           # Shared modules (DNS, ECH, Logger, Routing, SOCKS5)
└── .github/workflows/    # CI pipelines (AAR warming, building, signing, and release)
```

### Traffic Flow Architecture

```
[ App Traffic ]
       │
       ▼
[ Android VpnService TUN Device ]
       │
       ▼
[ hev-socks5-tunnel (C / JNI) ]
       │ (Converts TUN packets to SOCKS5)
       ▼
[ Local SOCKS5 Proxy (Go / golib listening on 127.0.0.1:1080) ]
       │                                     │
       ├─ [ Route Matches Bypass Rule ]      └─ [ Proxy Tunnel ]
       │        │                                    │
       ▼        ▼                                    ▼
  [ Direct Destination ]                   [ Go Backend (GCM / X-Tunnel) ]
                                                     │
                                                     ▼
                                      [ ECH / TLS 1.3 Encrypted Tunnel ]
                                                     │
                                                     ▼
                                            [ Remote Server / Worker ]
```

---

## Building & Development

> **Build Constraint**: Android APK compilation and signing are automated through GitHub Actions. Local verification only requires running Go unit tests.

### Running Go Core Unit Tests

```bash
cd golib
# Run all unit tests with race detection
CGO_ENABLED=1 go test -race -count=1 ./...
```

### GitHub Actions CI Workflows

- **AAR Cache Warming (`build-aar.yml`)**: Triggers on pushes modifying `golib/**` on branch `main`, caching `xclient.aar` for fast builds.
- **Release Automation (`release.yml`)**: Pushing a `v*` tag triggers complete APK compilation, alignment, and signing across all ABI architectures, generating a GitHub Release automatically.

---

## License

This project is licensed under the [Apache License 2.0](LICENSE).
