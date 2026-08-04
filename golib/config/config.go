package config

import (
	"encoding/json"
	"fmt"
	"math"
	"strings"
	"time"
)

// LogLevel 日志级别类型
type LogLevel int

const (
	DEBUG LogLevel = iota
	INFO
	WARN
	ERROR
)

// String 实现 Stringer 接口
func (l LogLevel) String() string {
	switch l {
	case DEBUG:
		return "DEBUG"
	case INFO:
		return "INFO"
	case WARN:
		return "WARN"
	case ERROR:
		return "ERROR"
	default:
		return "INFO"
	}
}

// MarshalYAML 实现 yaml.Marshaler 接口
func (l LogLevel) MarshalYAML() (interface{}, error) {
	return l.String(), nil
}

// UnmarshalYAML 实现 yaml.Unmarshaler 接口
func (l *LogLevel) UnmarshalYAML(unmarshal func(interface{}) error) error {
	var s string
	if err := unmarshal(&s); err != nil {
		return err
	}
	*l = ParseLogLevel(s)
	return nil
}

// MarshalJSON 实现 json.Marshaler 接口
func (l LogLevel) MarshalJSON() ([]byte, error) {
	return json.Marshal(l.String())
}

// UnmarshalJSON 实现 json.Unmarshaler 接口
func (l *LogLevel) UnmarshalJSON(data []byte) error {
	var s string
	if err := json.Unmarshal(data, &s); err != nil {
		return err
	}
	*l = ParseLogLevel(s)
	return nil
}

// ParseLogLevel 解析日志级别字符串
func ParseLogLevel(s string) LogLevel {
	switch strings.ToUpper(s) {
	case "DEBUG":
		return DEBUG
	case "INFO":
		return INFO
	case "WARN":
		return WARN
	case "ERROR":
		return ERROR
	default:
		return INFO
	}
}

// yamlDuration 是 time.Duration 的包装器，支持 YAML 中的字符串格式
type yamlDuration struct {
	time.Duration
}

// MarshalYAML 实现 yaml.Marshaler 接口
func (yd yamlDuration) MarshalYAML() (interface{}, error) {
	return yd.Duration.String(), nil
}

// UnmarshalYAML 实现 yaml.Unmarshaler 接口
func (yd *yamlDuration) UnmarshalYAML(unmarshal func(interface{}) error) error {
	var v interface{}
	if err := unmarshal(&v); err != nil {
		return err
	}

	switch value := v.(type) {
	case float64:
		// JSON 数字格式（毫秒）
		yd.Duration = time.Duration(value) * time.Millisecond
	case int:
		// JSON 整数格式（毫秒）
		yd.Duration = time.Duration(value) * time.Millisecond
	case string:
		// YAML 字符串格式（如 "5m", "1s"）
		d, err := time.ParseDuration(value)
		if err != nil {
			return fmt.Errorf("无法解析持续时间: %q: %w", value, err)
		}
		yd.Duration = d
	default:
		return fmt.Errorf("无效的持续时间类型: %T", v)
	}

	return nil
}

// yamlByteSize 是字节大小的包装器，支持 YAML 中的字符串格式（如 "256KB", "1MB"）
type yamlByteSize struct {
	Bytes int64
}

// MarshalYAML 实现 yaml.Marshaler 接口
func (yb yamlByteSize) MarshalYAML() (interface{}, error) {
	return formatBytes(yb.Bytes), nil
}

// UnmarshalYAML 实现 yaml.Unmarshaler 接口
func (yb *yamlByteSize) UnmarshalYAML(unmarshal func(interface{}) error) error {
	var v interface{}
	if err := unmarshal(&v); err != nil {
		return err
	}

	switch value := v.(type) {
	case float64:
		// JSON 数字格式（字节）
		yb.Bytes = int64(value)
	case int:
		// JSON 整数格式（字节）
		yb.Bytes = int64(value)
	case string:
		// YAML 字符串格式（如 "256KB", "1MB"）
		bytes, err := parseByteSize(value)
		if err != nil {
			return fmt.Errorf("无法解析字节大小: %q: %w", value, err)
		}
		yb.Bytes = bytes
	default:
		return fmt.Errorf("无效的字节大小类型: %T", v)
	}

	return nil
}

// parseByteSize 解析字节大小字符串（如 "256KB", "1MB"）
func parseByteSize(s string) (int64, error) {
	s = strings.TrimSpace(strings.ToUpper(s))

	// 提取数字和单位
	var num float64
	var unit string
	_, err := fmt.Sscanf(s, "%f%s", &num, &unit)
	if err != nil {
		// 尝试只解析数字（默认为字节）
		_, err2 := fmt.Sscanf(s, "%f", &num)
		if err2 != nil {
			return 0, fmt.Errorf("无效的字节大小格式: %s", s)
		}
		return int64(num), nil
	}

	// 转换单位
	var multiplier int64
	switch unit {
	case "B", "":
		multiplier = 1
	case "KB", "K":
		multiplier = 1024
	case "MB", "M":
		multiplier = 1024 * 1024
	case "GB", "G":
		multiplier = 1024 * 1024 * 1024
	default:
		return 0, fmt.Errorf("未知的字节单位: %s", unit)
	}

	// 检查整数溢出
	result := num * float64(multiplier)
	if result > float64(math.MaxInt64) {
		return 0, fmt.Errorf("字节大小超出范围: %s (最大支持 %d 字节)", s, int64(math.MaxInt64))
	}

	return int64(result), nil
}

// formatBytes 格式化字节大小为可读字符串
func formatBytes(bytes int64) string {
	const unit = 1024
	if bytes < unit {
		return fmt.Sprintf("%dB", bytes)
	}
	div, exp := int64(unit), 0
	for n := bytes / unit; n >= unit; n /= unit {
		div *= unit
		exp++
	}
	// 防止数组越界（最大支持到 E = Exabyte）
	if exp >= len("KMGTPE") {
		exp = len("KMGTPE") - 1
	}
	return fmt.Sprintf("%.0f%cB", float64(bytes)/float64(div), "KMGTPE"[exp])
}

// Config 应用配置
type Config struct {
	// 基本配置
	WorkerHost    string   `yaml:"workerHost" json:"workerHost"`
	ListenAddress string   `yaml:"listenAddress" json:"listenAddress"` // 监听地址，如 ":10080" 或 "0.0.0.0:10080"
	UserID        string   `yaml:"userID,omitempty" json:"userID,omitempty"`
	LogLevel      LogLevel `yaml:"logLevel" json:"logLevel"`

	// 出口端代理配置
	ProxyIP string `yaml:"proxyIP,omitempty" json:"proxyIP,omitempty"` // 出口端代理IP，传递给 Worker，留空时 Worker 使用自身已配置的 proxyIP

	// DoH 超时配置
	DoHTimeout yamlDuration `yaml:"dohTimeout,omitempty" json:"dohTimeout,omitempty"`

	// 连接池配置
	MinPoolSize       int          `yaml:"minPoolSize" json:"minPoolSize"`
	MaxPoolSize       int          `yaml:"maxPoolSize" json:"maxPoolSize"`
	ConnectionTTL     yamlDuration `yaml:"connectionTTL" json:"connectionTTL"`
	ConnectionTimeout yamlDuration `yaml:"connectionTimeout" json:"connectionTimeout"`

	// 中转节点配置
	RelayIPs                  []string     `yaml:"relayIPs" json:"relayIPs"`
	RelayMonitorInterval      yamlDuration `yaml:"relayMonitorInterval" json:"relayMonitorInterval"`
	RelayMaxLatency           yamlDuration `yaml:"relayMaxLatency" json:"relayMaxLatency"`
	RelayFailureThreshold     int          `yaml:"relayFailureThreshold" json:"relayFailureThreshold"`
	RelayRescoreInterval      yamlDuration `yaml:"relayRescoreInterval" json:"relayRescoreInterval"`
	RelayForceRescoreCooldown yamlDuration `yaml:"relayForceRescoreCooldown" json:"relayForceRescoreCooldown"`

	// DNS 缓存配置
	EnableDoH               bool         `yaml:"enableDoH" json:"enableDoH"`
	DoHUrl                  string       `yaml:"dohUrl" json:"dohUrl"`
	DNSCacheTTL             yamlDuration `yaml:"dnsCacheTTL" json:"dnsCacheTTL"`
	DNSCacheCleanupInterval yamlDuration `yaml:"dnsCacheCleanupInterval" json:"dnsCacheCleanupInterval"`
	EnableDNSWarmup         bool         `yaml:"enableDNSWarmup" json:"enableDNSWarmup"`
	DNSWarmupDomains        []string     `yaml:"dnsWarmupDomains" json:"dnsWarmupDomains"`
	EnableDoHProxy          bool         `yaml:"enableDoHProxy" json:"enableDoHProxy"`

	// ECH 配置
	EnableECH          bool         `yaml:"enableECH" json:"enableECH"`
	ECHDomain          string       `yaml:"echDomain" json:"echDomain"`
	ECHCacheTTL        yamlDuration `yaml:"echCacheTTL" json:"echCacheTTL"`
	ECHRefreshInterval yamlDuration `yaml:"echRefreshInterval" json:"echRefreshInterval"`

	// 心跳保活配置
	HeartbeatInterval yamlDuration `yaml:"heartbeatInterval" json:"heartbeatInterval"`
	HeartbeatTimeout  yamlDuration `yaml:"heartbeatTimeout" json:"heartbeatTimeout"`
	EnableTcpNoDelay  bool         `yaml:"enableTcpNoDelay" json:"enableTcpNoDelay"`

	// 连接池预热配置
	EnablePoolWarmup  bool         `yaml:"enablePoolWarmup" json:"enablePoolWarmup"`
	WarmupConcurrency int          `yaml:"warmupConcurrency" json:"warmupConcurrency"`
	WarmupTimeout     yamlDuration `yaml:"warmupTimeout" json:"warmupTimeout"`

	// 断线重连配置
	EnableAutoReconnect  bool         `yaml:"enableAutoReconnect" json:"enableAutoReconnect"`
	MaxReconnectAttempts int          `yaml:"maxReconnectAttempts" json:"maxReconnectAttempts"`
	ReconnectDelay       yamlDuration `yaml:"reconnectDelay" json:"reconnectDelay"`

	// 请求超时配置
	TunnelTimeout yamlDuration `yaml:"tunnelTimeout" json:"tunnelTimeout"`

	// 连接池动态调整配置
	EnableDynamicPool        bool         `yaml:"enableDynamicPool" json:"enableDynamicPool"`
	DynamicPoolInterval      yamlDuration `yaml:"dynamicPoolInterval" json:"dynamicPoolInterval"`
	DynamicPoolMinSize       int          `yaml:"dynamicPoolMinSize" json:"dynamicPoolMinSize"`
	DynamicPoolMaxSize       int          `yaml:"dynamicPoolMaxSize" json:"dynamicPoolMaxSize"`
	DynamicPoolLowThreshold  float64      `yaml:"dynamicPoolLowThreshold" json:"dynamicPoolLowThreshold"`
	DynamicPoolHighThreshold float64      `yaml:"dynamicPoolHighThreshold" json:"dynamicPoolHighThreshold"`

	// 日志文件配置
	EnableLogFile      bool   `yaml:"enableLogFile" json:"enableLogFile"`
	LogFilePath        string `yaml:"logFilePath" json:"logFilePath"`
	LogFileMaxSize     int64  `yaml:"logFileMaxSize" json:"logFileMaxSize"`
	LogFileBackupCount int    `yaml:"logFileBackupCount" json:"logFileBackupCount"`

	// 多路复用配置
	EnableMultiplex         bool `yaml:"enableMultiplex" json:"enableMultiplex"`
	MaxStreamsPerConnection int  `yaml:"maxStreamsPerConnection" json:"maxStreamsPerConnection"`

	// 窗口流控配置
	DefaultWindowSize yamlByteSize `yaml:"defaultWindowSize" json:"defaultWindowSize"` // 默认窗口大小
	MinWindowSize     yamlByteSize `yaml:"minWindowSize" json:"minWindowSize"`         // 最小窗口大小
	MaxWindowSize     yamlByteSize `yaml:"maxWindowSize" json:"maxWindowSize"`         // 最大窗口大小
	WindowTimeout     yamlDuration `yaml:"windowTimeout" json:"windowTimeout"`         // 窗口等待超时

	// 拥塞控制配置
	CongestionControlInterval yamlDuration `yaml:"congestionControlInterval" json:"congestionControlInterval"` // 拥塞控制检查间隔

	// 连接质量监控配置
	EnableQualityMonitor       bool         `yaml:"enableQualityMonitor" json:"enableQualityMonitor"`             // 是否启用质量监控
	QualityCheckInterval       yamlDuration `yaml:"qualityCheckInterval" json:"qualityCheckInterval"`             // 质量检查间隔
	QualityDegradeThreshold    int64        `yaml:"qualityDegradeThreshold" json:"qualityDegradeThreshold"`       // 劣化阈值（分数 < 60）
	QualityRelaySwitchCooldown yamlDuration `yaml:"qualityRelaySwitchCooldown" json:"qualityRelaySwitchCooldown"` // 节点切换冷却期
	QualityMinDegradedCount    int          `yaml:"qualityMinDegradedCount" json:"qualityMinDegradedCount"`       // 触发切换的最小劣化连接数
}

// GetConnectionTTL 返回连接 TTL 的 time.Duration 值
func (c *Config) GetConnectionTTL() time.Duration {
	return c.ConnectionTTL.Duration
}

// GetConnectionTimeout 返回连接超时的 time.Duration 值
func (c *Config) GetConnectionTimeout() time.Duration {
	return c.ConnectionTimeout.Duration
}

// GetRelayMonitorInterval 返回节点监控间隔的 time.Duration 值
func (c *Config) GetRelayMonitorInterval() time.Duration {
	return c.RelayMonitorInterval.Duration
}

// GetRelayMaxLatency 返回节点最大延迟的 time.Duration 值
func (c *Config) GetRelayMaxLatency() time.Duration {
	return c.RelayMaxLatency.Duration
}

// GetRelayRescoreInterval 返回节点重评间隔的 time.Duration 值
func (c *Config) GetRelayRescoreInterval() time.Duration {
	return c.RelayRescoreInterval.Duration
}

// GetRelayForceRescoreCooldown 返回强制重评冷却时间的 time.Duration 值
func (c *Config) GetRelayForceRescoreCooldown() time.Duration {
	return c.RelayForceRescoreCooldown.Duration
}

// GetDNSCacheTTL 返回 DNS 缓存 TTL 的 time.Duration 值
func (c *Config) GetDNSCacheTTL() time.Duration {
	return c.DNSCacheTTL.Duration
}

// GetDNSCacheCleanupInterval 返回 DNS 缓存清理间隔的 time.Duration 值
func (c *Config) GetDNSCacheCleanupInterval() time.Duration {
	return c.DNSCacheCleanupInterval.Duration
}

// GetECHCacheTTL 返回 ECH 缓存 TTL 的 time.Duration 值
func (c *Config) GetECHCacheTTL() time.Duration {
	return c.ECHCacheTTL.Duration
}

// GetECHRefreshInterval 返回 ECH 刷新间隔的 time.Duration 值
func (c *Config) GetECHRefreshInterval() time.Duration {
	return c.ECHRefreshInterval.Duration
}

// GetHeartbeatInterval 返回心跳间隔的 time.Duration 值
func (c *Config) GetHeartbeatInterval() time.Duration {
	return c.HeartbeatInterval.Duration
}

// GetHeartbeatTimeout 返回心跳超时的 time.Duration 值
func (c *Config) GetHeartbeatTimeout() time.Duration {
	return c.HeartbeatTimeout.Duration
}

// GetWarmupTimeout 返回预热超时的 time.Duration 值
func (c *Config) GetWarmupTimeout() time.Duration {
	return c.WarmupTimeout.Duration
}

// GetReconnectDelay 返回重连延迟的 time.Duration 值
func (c *Config) GetReconnectDelay() time.Duration {
	return c.ReconnectDelay.Duration
}

// GetTunnelTimeout 返回隧道超时的 time.Duration 值
func (c *Config) GetTunnelTimeout() time.Duration {
	return c.TunnelTimeout.Duration
}

// GetDynamicPoolInterval 返回动态池调整间隔的 time.Duration 值
func (c *Config) GetDynamicPoolInterval() time.Duration {
	return c.DynamicPoolInterval.Duration
}

// GetDefaultWindowSize 返回默认窗口大小的字节数
func (c *Config) GetDefaultWindowSize() int64 {
	return c.DefaultWindowSize.Bytes
}

// GetMinWindowSize 返回最小窗口大小的字节数
func (c *Config) GetMinWindowSize() int64 {
	return c.MinWindowSize.Bytes
}

// GetMaxWindowSize 返回最大窗口大小的字节数
func (c *Config) GetMaxWindowSize() int64 {
	return c.MaxWindowSize.Bytes
}

// GetWindowTimeout 返回窗口超时的 time.Duration 值
func (c *Config) GetWindowTimeout() time.Duration {
	return c.WindowTimeout.Duration
}

// GetCongestionControlInterval 返回拥塞控制间隔的 time.Duration 值
func (c *Config) GetCongestionControlInterval() time.Duration {
	return c.CongestionControlInterval.Duration
}

// GetQualityCheckInterval 返回质量检查间隔的 time.Duration 值
func (c *Config) GetQualityCheckInterval() time.Duration {
	return c.QualityCheckInterval.Duration
}

// GetQualityRelaySwitchCooldown 返回节点切换冷却期的 time.Duration 值
func (c *Config) GetQualityRelaySwitchCooldown() time.Duration {
	return c.QualityRelaySwitchCooldown.Duration
}

// GetDoHTimeout 返回 DoH 查询超时的 time.Duration 值
func (c *Config) GetDoHTimeout() time.Duration {
	return c.DoHTimeout.Duration
}

// DefaultConfig 返回默认配置
func DefaultConfig() *Config {
	return &Config{
		// 基本配置
		WorkerHost:    "",      // 必须通过 --worker 参数或配置文件指定
		ListenAddress: ":1080", // 标准 SOCKS5 端口
		UserID:        "",      // 无鉴权时留空
		LogLevel:      INFO,

		// 连接池配置
		MinPoolSize:       5,
		MaxPoolSize:       15,
		ConnectionTTL:     yamlDuration{5 * time.Minute},
		ConnectionTimeout: yamlDuration{time.Second},

		// 中转节点配置
		RelayIPs:                  nil, // 默认不走中转，直连 Worker
		RelayMonitorInterval:      yamlDuration{30 * time.Second},
		RelayMaxLatency:           yamlDuration{500 * time.Millisecond},
		RelayFailureThreshold:     3,
		RelayRescoreInterval:      yamlDuration{10 * time.Minute},
		RelayForceRescoreCooldown: yamlDuration{time.Minute},

		// DNS 缓存配置
		EnableDoH:               true,
		DoHUrl:                  "", // 空=使用内置备用DoH列表
		DNSCacheTTL:             yamlDuration{5 * time.Minute},
		DNSCacheCleanupInterval: yamlDuration{time.Minute},
		EnableDNSWarmup:         true,
		DNSWarmupDomains:        []string{},
		EnableDoHProxy:          false,

		// ECH 配置
		EnableECH:          false,
		ECHDomain:          "cloudflare-ech.com",
		ECHCacheTTL:        yamlDuration{24 * time.Hour},
		ECHRefreshInterval: yamlDuration{12 * time.Hour},

		// DoH 超时配置
		DoHTimeout: yamlDuration{3 * time.Second},

		// 心跳保活配置
		HeartbeatInterval: yamlDuration{15 * time.Second},
		HeartbeatTimeout:  yamlDuration{3 * time.Second},
		EnableTcpNoDelay:  true,

		// 连接池预热配置
		EnablePoolWarmup:  true,
		WarmupConcurrency: 3,
		WarmupTimeout:     yamlDuration{30 * time.Second},

		// 断线重连配置
		EnableAutoReconnect:  true,
		MaxReconnectAttempts: 3,
		ReconnectDelay:       yamlDuration{time.Second},

		// 请求超时配置
		TunnelTimeout: yamlDuration{time.Minute},

		// 连接池动态调整配置
		EnableDynamicPool:        true,
		DynamicPoolInterval:      yamlDuration{time.Minute},
		DynamicPoolMinSize:       5,
		DynamicPoolMaxSize:       15,
		DynamicPoolLowThreshold:  0.3,
		DynamicPoolHighThreshold: 0.8,

		// 日志文件配置
		EnableLogFile:      false,
		LogFilePath:        "./gcm.log",
		LogFileMaxSize:     10 * 1024 * 1024,
		LogFileBackupCount: 3,

		// 多路复用配置
		EnableMultiplex:         true,
		MaxStreamsPerConnection: 5,

		// 窗口流控配置
		DefaultWindowSize: yamlByteSize{1024 * 1024},     // 1MB
		MinWindowSize:     yamlByteSize{64 * 1024},       // 64KB
		MaxWindowSize:     yamlByteSize{4 * 1024 * 1024}, // 4MB
		WindowTimeout:     yamlDuration{5 * time.Second}, // 5秒

		// 拥塞控制配置
		CongestionControlInterval: yamlDuration{time.Minute}, // 60秒

		// 连接质量监控配置
		EnableQualityMonitor:       true,                           // 默认启用
		QualityCheckInterval:       yamlDuration{10 * time.Second}, // 10秒检查一次
		QualityDegradeThreshold:    60,                             // 分数 < 60 视为劣化
		QualityRelaySwitchCooldown: yamlDuration{5 * time.Minute},  // 5分钟冷却期
		QualityMinDegradedCount:    2,                              // 至少2个劣化连接才触发切换
	}
}
