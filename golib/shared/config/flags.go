package config

import (
	"context"
	"fmt"
	"strings"
	"time"

	"github.com/urfave/cli/v3"
)

// DefineFlags 定义所有命令行参数
func DefineFlags() []cli.Flag {
	return []cli.Flag{
		// ===== 配置文件 =====
		&cli.StringFlag{
			Name:     "config",
			Aliases:  []string{"c"},
			Usage:    "配置文件路径 (YAML/JSON)",
			Category: "基本",
			OnlyOnce: true,
		},

		// ===== 基本配置 =====
		&cli.StringFlag{
			Name:     "worker",
			Aliases:  []string{"w"},
			Usage:    "Cloudflare Worker 地址 (必须指定)",
			Value:    "",
			Category: "基本",
			OnlyOnce: true,
		},
		&cli.StringFlag{
			Name:     "listen",
			Aliases:  []string{"l"},
			Usage:    "SOCKS5 监听地址 (如 :1080 或 0.0.0.0:1080)",
			Value:    ":1080",
			Category: "基本",
			OnlyOnce: true,
		},
		&cli.StringFlag{
			Name:     "user-id",
			Aliases:  []string{"u"},
			Usage:    "用户鉴权ID",
			Category: "基本",
			OnlyOnce: true,
		},
		&cli.StringFlag{
			Name:     "proxy-ip",
			Aliases:  []string{"p"},
			Usage:    "出口端代理IP (留空时 Worker 使用自身已配置的 proxyIP)",
			Category: "基本",
			OnlyOnce: true,
		},
		&cli.StringFlag{
			Name:     "log-level",
			Usage:    "日志级别: DEBUG/INFO/WARN/ERROR",
			Value:    "INFO",
			Category: "基本",
			OnlyOnce: true,
		},

		// ===== DNS 配置 =====
		&cli.StringFlag{
			Name:     "doh",
			Aliases:  []string{"d"},
			Usage:    "DoH 服务地址",
			Value:    "https://v.recipes/dns-query",
			Category: "网络",
			OnlyOnce: true,
		},
		&cli.DurationFlag{
			Name:     "doh-timeout",
			Usage:    "DoH 查询超时时间",
			Value:    3 * time.Second,
			Category: "网络",
			OnlyOnce: true,
		},
		&cli.BoolFlag{
			Name:     "no-doh",
			Usage:    "禁用 DoH",
			Category: "网络",
			OnlyOnce: true,
		},
		&cli.BoolFlag{
			Name:     "no-dns-warmup",
			Usage:    "禁用 DNS 预热",
			Category: "高级",
			OnlyOnce: true,
		},
		&cli.BoolFlag{
			Name:     "doh-proxy",
			Usage:    "启用 DoH 通过代理访问",
			Category: "高级",
			OnlyOnce: true,
		},

		// ===== 中转节点配置 =====
		&cli.StringSliceFlag{
			Name:     "relay",
			Aliases:  []string{"r"},
			Usage:    "中转节点列表（可多次指定或逗号分隔）",
			Category: "网络",
		},
		&cli.IntFlag{
			Name:     "min-pool",
			Usage:    "最小连接池大小",
			Value:    3,
			Category: "连接池",
			OnlyOnce: true,
		},
		&cli.IntFlag{
			Name:     "max-pool",
			Usage:    "最大连接池大小",
			Value:    15,
			Category: "连接池",
			OnlyOnce: true,
		},

		// ===== 连接池配置 =====
		&cli.BoolFlag{
			Name:     "no-warmup",
			Usage:    "禁用连接池预热",
			Category: "连接池",
			OnlyOnce: true,
		},
		&cli.BoolFlag{
			Name:     "no-reconnect",
			Usage:    "禁用断线自动重连",
			Category: "连接池",
			OnlyOnce: true,
		},
		&cli.BoolFlag{
			Name:     "no-dynamic-pool",
			Usage:    "禁用动态池大小调整",
			Category: "连接池",
			OnlyOnce: true,
		},

		// ===== 日志配置 =====
		&cli.StringFlag{
			Name:     "log-file",
			Usage:    "启用日志文件输出到指定路径",
			Category: "日志",
			OnlyOnce: true,
		},

		// ===== 隧道配置 =====
		&cli.IntFlag{
			Name:     "timeout",
			Usage:    "隧道超时时间（秒）",
			Value:    60,
			Category: "网络",
			OnlyOnce: true,
		},
		&cli.BoolFlag{
			Name:     "no-mux",
			Usage:    "禁用多路复用",
			Category: "连接池",
			OnlyOnce: true,
		},

		// ===== 高级时间配置 =====
		&cli.DurationFlag{
			Name:     "connection-ttl",
			Usage:    "连接最大存活时间",
			Value:    5 * time.Minute,
			Category: "高级",
			OnlyOnce: true,
		},
		&cli.DurationFlag{
			Name:     "connection-timeout",
			Usage:    "连接超时时间",
			Value:    time.Second,
			Category: "高级",
			OnlyOnce: true,
		},
		&cli.DurationFlag{
			Name:     "heartbeat-interval",
			Usage:    "心跳间隔",
			Value:    15 * time.Second,
			Category: "高级",
			OnlyOnce: true,
		},
		&cli.DurationFlag{
			Name:     "heartbeat-timeout",
			Usage:    "心跳响应超时",
			Value:    3 * time.Second,
			Category: "高级",
			OnlyOnce: true,
		},
		&cli.DurationFlag{
			Name:     "dns-cache-ttl",
			Usage:    "DNS 缓存过期时间",
			Value:    5 * time.Minute,
			Category: "高级",
			OnlyOnce: true,
		},
		&cli.DurationFlag{
			Name:     "relay-monitor-interval",
			Usage:    "节点监控间隔",
			Value:    30 * time.Second,
			Category: "高级",
			OnlyOnce: true,
		},
		&cli.DurationFlag{
			Name:     "relay-max-latency",
			Usage:    "节点最大可接受延迟",
			Value:    500 * time.Millisecond,
			Category: "高级",
			OnlyOnce: true,
		},

		// ===== 窗口流控配置 =====
		&cli.StringFlag{
			Name:     "default-window-size",
			Usage:    "默认窗口大小 (如 1MB, 2MB)",
			Value:    "1MB",
			Category: "高级",
			OnlyOnce: true,
		},
		&cli.StringFlag{
			Name:     "min-window-size",
			Usage:    "最小窗口大小 (如 64KB)",
			Value:    "64KB",
			Category: "高级",
			OnlyOnce: true,
		},
		&cli.StringFlag{
			Name:     "max-window-size",
			Usage:    "最大窗口大小 (如 4MB)",
			Value:    "4MB",
			Category: "高级",
			OnlyOnce: true,
		},
		&cli.DurationFlag{
			Name:     "window-timeout",
			Usage:    "窗口等待超时时间",
			Value:    5 * time.Second,
			Category: "高级",
			OnlyOnce: true,
		},

		// ===== 拥塞控制配置 =====
		&cli.DurationFlag{
			Name:     "congestion-control-interval",
			Usage:    "拥塞控制检查间隔",
			Value:    time.Minute,
			Category: "高级",
			OnlyOnce: true,
		},

		// ===== ECH 配置 =====
		&cli.BoolFlag{
			Name:     "enable-ech",
			Aliases:  []string{"e"},
			Usage:    "启用 TLS ECH (Encrypted Client Hello)",
			Category: "高级",
			OnlyOnce: true,
		},
		&cli.StringFlag{
			Name:     "ech-domain",
			Usage:    "ECH 隐藏域名",
			Value:    "cloudflare-ech.com",
			Category: "高级",
			OnlyOnce: true,
		},
	}
}

// ApplyFlags 将命令行参数应用到 Config
// urfave/cli v3 使用 context.Context 而不是 *cli.Context
func ApplyFlags(cfg *Config, ctx context.Context, cmd *cli.Command) error {
	// 从 cmd 获取参数值
	// ===== 基本配置 =====
	if cmd.IsSet("worker") {
		cfg.WorkerHost = cmd.String("worker")
	}
	if cmd.IsSet("listen") {
		cfg.ListenAddress = cmd.String("listen")
	}
	if cmd.IsSet("user-id") {
		cfg.UserID = cmd.String("user-id")
	}
	if cmd.IsSet("proxy-ip") {
		cfg.ProxyIP = cmd.String("proxy-ip")
	}
	if cmd.IsSet("log-level") {
		cfg.LogLevel = ParseLogLevel(cmd.String("log-level"))
	}

	// ===== DNS 配置 =====
	if cmd.IsSet("doh") {
		cfg.DoHUrl = cmd.String("doh")
	}
	if cmd.IsSet("doh-timeout") {
		cfg.DoHTimeout = yamlDuration{cmd.Duration("doh-timeout")}
	}
	if cmd.IsSet("no-doh") && cmd.Bool("no-doh") {
		cfg.EnableDoH = false
	}
	if cmd.IsSet("no-dns-warmup") && cmd.Bool("no-dns-warmup") {
		cfg.EnableDNSWarmup = false
	}
	if cmd.IsSet("doh-proxy") && cmd.Bool("doh-proxy") {
		cfg.EnableDoHProxy = true
	}

	// ===== 中转节点配置 =====
	if cmd.IsSet("relay") {
		relayList := cmd.StringSlice("relay")
		if len(relayList) > 0 {
			cfg.RelayIPs = flattenStringSlice(relayList)
		}
	}
	if cmd.IsSet("min-pool") {
		cfg.MinPoolSize = cmd.Int("min-pool")
	}
	if cmd.IsSet("max-pool") {
		cfg.MaxPoolSize = cmd.Int("max-pool")
	}

	// ===== 连接池配置 =====
	if cmd.IsSet("no-warmup") && cmd.Bool("no-warmup") {
		cfg.EnablePoolWarmup = false
	}
	if cmd.IsSet("no-reconnect") && cmd.Bool("no-reconnect") {
		cfg.EnableAutoReconnect = false
	}
	if cmd.IsSet("no-dynamic-pool") && cmd.Bool("no-dynamic-pool") {
		cfg.EnableDynamicPool = false
	}

	// ===== 日志配置 =====
	if cmd.IsSet("log-file") {
		cfg.EnableLogFile = true
		cfg.LogFilePath = cmd.String("log-file")
	}

	// ===== 隧道配置 =====
	if cmd.IsSet("timeout") {
		cfg.TunnelTimeout = yamlDuration{time.Duration(cmd.Int("timeout")) * time.Second}
	}
	if cmd.IsSet("no-mux") && cmd.Bool("no-mux") {
		cfg.EnableMultiplex = false
	}

	// ===== 高级时间配置 =====
	if cmd.IsSet("connection-ttl") {
		cfg.ConnectionTTL = yamlDuration{cmd.Duration("connection-ttl")}
	}
	if cmd.IsSet("connection-timeout") {
		cfg.ConnectionTimeout = yamlDuration{cmd.Duration("connection-timeout")}
	}
	if cmd.IsSet("heartbeat-interval") {
		cfg.HeartbeatInterval = yamlDuration{cmd.Duration("heartbeat-interval")}
	}
	if cmd.IsSet("heartbeat-timeout") {
		cfg.HeartbeatTimeout = yamlDuration{cmd.Duration("heartbeat-timeout")}
	}
	if cmd.IsSet("dns-cache-ttl") {
		cfg.DNSCacheTTL = yamlDuration{cmd.Duration("dns-cache-ttl")}
	}
	if cmd.IsSet("relay-monitor-interval") {
		cfg.RelayMonitorInterval = yamlDuration{cmd.Duration("relay-monitor-interval")}
	}
	if cmd.IsSet("relay-max-latency") {
		cfg.RelayMaxLatency = yamlDuration{cmd.Duration("relay-max-latency")}
	}

	// ===== 窗口流控配置 =====
	if cmd.IsSet("default-window-size") {
		bytes, err := parseByteSize(cmd.String("default-window-size"))
		if err != nil {
			return fmt.Errorf("无效的默认窗口大小: %w", err)
		}
		cfg.DefaultWindowSize = yamlByteSize{bytes}
	}
	if cmd.IsSet("min-window-size") {
		bytes, err := parseByteSize(cmd.String("min-window-size"))
		if err != nil {
			return fmt.Errorf("无效的最小窗口大小: %w", err)
		}
		cfg.MinWindowSize = yamlByteSize{bytes}
	}
	if cmd.IsSet("max-window-size") {
		bytes, err := parseByteSize(cmd.String("max-window-size"))
		if err != nil {
			return fmt.Errorf("无效的最大窗口大小: %w", err)
		}
		cfg.MaxWindowSize = yamlByteSize{bytes}
	}
	if cmd.IsSet("window-timeout") {
		cfg.WindowTimeout = yamlDuration{cmd.Duration("window-timeout")}
	}

	// ===== 拥塞控制配置 =====
	if cmd.IsSet("congestion-control-interval") {
		cfg.CongestionControlInterval = yamlDuration{cmd.Duration("congestion-control-interval")}
	}

	// ===== ECH 配置 =====
	if cmd.IsSet("enable-ech") {
		cfg.EnableECH = cmd.Bool("enable-ech")
	}

	return nil
}

// flattenStringSlice 将字符串切片中的逗号分隔元素展开
func flattenStringSlice(items []string) []string {
	var result []string
	for _, item := range items {
		// 检查是否包含逗号
		for _, part := range splitComma(item) {
			trimmed := strings.TrimSpace(part)
			if trimmed != "" {
				result = append(result, trimmed)
			}
		}
	}
	return result
}

// splitComma 按逗号分割字符串
func splitComma(s string) []string {
	return strings.Split(s, ",")
}
