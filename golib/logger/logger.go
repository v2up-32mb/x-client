package logger

import (
	"fmt"
	"io"
	"os"
	"strings"
	"sync"
	"time"
	"unicode/utf8"

	"xclient/config"
)

const (
	maxRuntimeLogLines = 2000
	maxRuntimeLogBytes = 256 * 1024
)

type runtimeLogStore struct {
	mu       sync.RWMutex
	lines    []string
	bytes    int
	maxLines int
	maxBytes int
}

func newRuntimeLogStore(maxLines, maxBytes int) *runtimeLogStore {
	return &runtimeLogStore{maxLines: maxLines, maxBytes: maxBytes}
}

func (s *runtimeLogStore) append(line string) {
	if s.maxLines <= 0 || s.maxBytes <= 1 {
		return
	}
	if len(line)+1 > s.maxBytes {
		line = truncateUTF8(line, s.maxBytes-1)
	}
	lineBytes := len(line) + 1

	s.mu.Lock()
	defer s.mu.Unlock()
	for len(s.lines) > 0 && (len(s.lines) >= s.maxLines || s.bytes+lineBytes > s.maxBytes) {
		s.bytes -= len(s.lines[0]) + 1
		s.lines = s.lines[1:]
	}
	s.lines = append(s.lines, line)
	s.bytes += lineBytes
}

func truncateUTF8(value string, maxBytes int) string {
	if maxBytes <= 0 {
		return ""
	}
	if len(value) <= maxBytes {
		return value
	}
	value = value[:maxBytes]
	for !utf8.ValidString(value) {
		value = value[:len(value)-1]
	}
	return value
}

func (s *runtimeLogStore) snapshot() string {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return strings.Join(s.lines, "\n")
}

func (s *runtimeLogStore) clear() {
	s.mu.Lock()
	s.lines = nil
	s.bytes = 0
	s.mu.Unlock()
}

var runtimeLogs = newRuntimeLogStore(maxRuntimeLogLines, maxRuntimeLogBytes)

// AppendRuntimeLog adds an Android lifecycle event to the current VPN session.
func AppendRuntimeLog(scope, message string) {
	if scope = strings.TrimSpace(scope); scope == "" {
		scope = "Android"
	}
	if message = strings.TrimSpace(message); message == "" {
		return
	}
	line := fmt.Sprintf("[%s] [I] [%s] %s", time.Now().Format("15:04:05"), scope, message)
	runtimeLogs.append(line)
	fmt.Println(line)
}

// GetRuntimeLogs returns a bounded snapshot of the current VPN session logs.
func GetRuntimeLogs() string {
	return runtimeLogs.snapshot()
}

// ClearRuntimeLogs starts a fresh in-memory log session.
func ClearRuntimeLogs() {
	runtimeLogs.clear()
}

// Logger 日志器
type Logger struct {
	mu         sync.RWMutex
	level      config.LogLevel
	scope      string
	fileLogger *FileLogger
}

// NewLogger 创建新的日志器
func NewLogger(level config.LogLevel, scope string, fileLogger *FileLogger) *Logger {
	return &Logger{
		level:      level,
		scope:      scope,
		fileLogger: fileLogger,
	}
}

// SetLevel 设置日志级别
func (l *Logger) SetLevel(level config.LogLevel) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.level = level
}

// log 内部日志方法
func (l *Logger) log(level config.LogLevel, format string, args ...interface{}) {
	l.mu.RLock()
	if level < l.level {
		l.mu.RUnlock()
		return
	}
	l.mu.RUnlock()

	var levelTag string
	switch level {
	case config.DEBUG:
		levelTag = "D"
	case config.INFO:
		levelTag = "I"
	case config.WARN:
		levelTag = "W"
	case config.ERROR:
		levelTag = "E"
	default:
		levelTag = "I"
	}

	timestamp := time.Now().Format("15:04:05")
	msg := fmt.Sprintf(format, args...)
	logMsg := fmt.Sprintf("[%s] [%s] [%s] %s", timestamp, levelTag, l.scope, msg)
	runtimeLogs.append(logMsg)

	// 输出到控制台
	fmt.Println(logMsg)

	// 写入文件
	if l.fileLogger != nil {
		l.fileLogger.Write(logMsg)
	}
}

// Debug 输出 DEBUG 级别日志
func (l *Logger) Debug(format string, args ...interface{}) {
	l.log(config.DEBUG, format, args...)
}

// Info 输出 INFO 级别日志
func (l *Logger) Info(format string, args ...interface{}) {
	l.log(config.INFO, format, args...)
}

// Warn 输出 WARN 级别日志
func (l *Logger) Warn(format string, args ...interface{}) {
	l.log(config.WARN, format, args...)
}

// Error 输出 ERROR 级别日志
func (l *Logger) Error(format string, args ...interface{}) {
	l.log(config.ERROR, format, args...)
}

// FileLogger 文件日志写入器
type FileLogger struct {
	mu           sync.Mutex
	enabled      bool
	filePath     string
	maxSize      int64
	backupCount  int
	file         *os.File
	currentSize  int64
	writtenBytes int64
	rotatedCount int
}

// NewFileLogger 创建文件日志写入器
func NewFileLogger(cfg *config.Config) *FileLogger {
	if !cfg.EnableLogFile {
		return &FileLogger{enabled: false}
	}

	fl := &FileLogger{
		enabled:      true,
		filePath:     cfg.LogFilePath,
		maxSize:      cfg.LogFileMaxSize,
		backupCount:  cfg.LogFileBackupCount,
		currentSize:  0,
		writtenBytes: 0,
		rotatedCount: 0,
	}

	if err := fl.init(); err != nil {
		fmt.Printf("[FileLogger] 初始化失败: %v\n", err)
		fl.enabled = false
		return fl
	}

	fmt.Printf("[FileLogger] 文件日志已启用: %s (最大%.1fMB, 保留%d个备份)\n",
		fl.filePath, float64(fl.maxSize)/1024/1024, fl.backupCount)

	return fl
}

// init 初始化文件日志
func (fl *FileLogger) init() error {
	fl.mu.Lock()
	defer fl.mu.Unlock()

	// 检查并处理日志文件轮转
	if info, err := os.Stat(fl.filePath); err == nil {
		fl.currentSize = info.Size()
		if fl.currentSize >= fl.maxSize {
			fmt.Printf("[FileLogger] 初始化时检测到日志文件已满 (%dKB)，执行轮转\n", fl.currentSize/1024)
			fl.rotate()
		}
	}

	// 打开文件（追加模式）
	file, err := os.OpenFile(fl.filePath, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0644)
	if err != nil {
		return err
	}
	fl.file = file

	return nil
}

// rotate 执行日志轮转
func (fl *FileLogger) rotate() {
	startTime := time.Now()
	fl.rotatedCount++

	// 删除最老的备份
	oldestBackup := fmt.Sprintf("%s.%d", fl.filePath, fl.backupCount)
	if _, err := os.Stat(oldestBackup); err == nil {
		os.Remove(oldestBackup)
		fmt.Printf("[FileLogger] 已删除最老备份: %s\n", oldestBackup)
	}

	// 轮转现有备份
	rotated := 0
	for i := fl.backupCount - 1; i >= 1; i-- {
		currentBackup := fmt.Sprintf("%s.%d", fl.filePath, i)
		nextBackup := fmt.Sprintf("%s.%d", fl.filePath, i+1)
		if _, err := os.Stat(currentBackup); err == nil {
			os.Rename(currentBackup, nextBackup)
			rotated++
		}
	}

	// 将当前日志文件重命名为 .1
	if fl.file != nil {
		fl.file.Close()
	}
	if _, err := os.Stat(fl.filePath); err == nil {
		os.Rename(fl.filePath, fmt.Sprintf("%s.1", fl.filePath))
	}

	// 重新打开文件
	file, err := os.OpenFile(fl.filePath, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0644)
	if err != nil {
		fmt.Printf("[FileLogger] 轮转后重新打开文件失败: %v\n", err)
		return
	}
	fl.file = file
	fl.currentSize = 0

	elapsed := time.Since(startTime)
	fmt.Printf("[FileLogger] 日志轮转完成，耗时%dms，轮转%d个备份文件\n", elapsed.Milliseconds(), rotated)
}

// Write 写入日志
func (fl *FileLogger) Write(msg string) {
	if !fl.enabled || fl.file == nil {
		return
	}

	fl.mu.Lock()
	defer fl.mu.Unlock()

	logLine := msg + "\n"
	lineSize := int64(len(logLine))
	fl.currentSize += lineSize
	fl.writtenBytes += lineSize

	// 检查是否需要轮转
	if fl.currentSize >= fl.maxSize {
		fl.rotate()
	}

	fl.file.WriteString(logLine)
}

// GetStats 获取统计信息
func (fl *FileLogger) GetStats() map[string]interface{} {
	fl.mu.Lock()
	defer fl.mu.Unlock()

	utilization := 0.0
	if fl.maxSize > 0 {
		utilization = float64(fl.currentSize) / float64(fl.maxSize) * 100
	}

	return map[string]interface{}{
		"enabled":      fl.enabled,
		"filePath":     fl.filePath,
		"currentSize":  fl.currentSize,
		"writtenBytes": fl.writtenBytes,
		"rotatedCount": fl.rotatedCount,
		"utilization":  fmt.Sprintf("%.1f%%", utilization),
	}
}

// Close 关闭文件日志
func (fl *FileLogger) Close() {
	if fl.file != nil {
		stats := fl.GetStats()
		fmt.Printf("[FileLogger] 关闭文件日志: 总写入%.1fKB，轮转%d次\n",
			float64(stats["writtenBytes"].(int64))/1024, stats["rotatedCount"].(int))
		fl.file.Close()
		fl.file = nil
	}
}

// MultiWriter 多输出写入器
type MultiWriter struct {
	writers []io.Writer
	mu      sync.Mutex
}

// NewMultiWriter 创建多输出写入器
func NewMultiWriter(writers ...io.Writer) *MultiWriter {
	return &MultiWriter{
		writers: writers,
	}
}

// Write 实现 io.Writer 接口
func (mw *MultiWriter) Write(p []byte) (n int, err error) {
	mw.mu.Lock()
	defer mw.mu.Unlock()

	for _, w := range mw.writers {
		w.Write(p)
	}
	return len(p), nil
}

// AddWriter 添加写入器
func (mw *MultiWriter) AddWriter(w io.Writer) {
	mw.mu.Lock()
	defer mw.mu.Unlock()
	mw.writers = append(mw.writers, w)
}

// GlobalLogger 全局日志器实例
var (
	globalLevel      config.LogLevel = config.INFO
	globalFileLogger *FileLogger
	loggerMap        = make(map[string]*Logger)
	loggerMapMu      sync.RWMutex
)

// InitGlobalLogger 初始化全局日志器
func InitGlobalLogger(cfg *config.Config) {
	globalLevel = cfg.LogLevel
	globalFileLogger = NewFileLogger(cfg)
}

// GetLogger 获取指定作用域的日志器
func GetLogger(scope string) *Logger {
	loggerMapMu.RLock()
	if logger, ok := loggerMap[scope]; ok {
		loggerMapMu.RUnlock()
		return logger
	}
	loggerMapMu.RUnlock()

	loggerMapMu.Lock()
	defer loggerMapMu.Unlock()

	// 再次检查，防止并发创建
	if logger, ok := loggerMap[scope]; ok {
		return logger
	}

	logger := NewLogger(globalLevel, scope, globalFileLogger)
	loggerMap[scope] = logger
	return logger
}

// SetGlobalLevel 设置全局日志级别
func SetGlobalLevel(level config.LogLevel) {
	loggerMapMu.Lock()
	defer loggerMapMu.Unlock()

	globalLevel = level
	for _, logger := range loggerMap {
		logger.SetLevel(level)
	}
}

// Close 关闭全局日志器
func Close() {
	if globalFileLogger != nil {
		globalFileLogger.Close()
	}
}
