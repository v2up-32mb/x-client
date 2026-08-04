// Package protocol 定义 GCM Worker 通信协议的消息格式和编解码。
//
// 协议格式（2 字节头）：
//
//	[STREAM_ID:1字节][TYPE:1字节][可选DATA]
//
// 支持的消息类型：
//   - CONNECT (0): 发起连接请求，DATA 为 ASCII 文本 "host:port|"
//   - CONNECTED (1): 连接建立成功，无 DATA
//   - DATA (2): 数据传输，DATA 为任意二进制
//   - CLOSE (3): 关闭流，无 DATA
package protocol

import (
	"bytes"
	"fmt"
)

// 消息类型常量
const (
	MsgTypeConnect   = 0 // 发起连接请求
	MsgTypeConnected = 1 // 连接建立成功
	MsgTypeData      = 2 // 数据传输
	MsgTypeClose     = 3 // 关闭连接
)

// HeaderSize 是协议头的大小（字节）。
//
// 头结构：[STREAM_ID:1][TYPE:1] = 2 字节。
const HeaderSize = 2

// Message 表示协议消息。
//
// 消息格式：[STREAM_ID:1字节][TYPE:1字节][可选DATA]
type Message struct {
	StreamID byte   // 1 byte: 流 ID (0-255)
	Type     byte   // 1 byte: 消息类型 (CONNECT/CONNECTED/DATA/CLOSE)
	Data     []byte // 可选负载
}

// NewMessage 创建新消息。
func NewMessage(streamID byte, msgType byte, data []byte) *Message {
	return &Message{
		StreamID: streamID,
		Type:     msgType,
		Data:     data,
	}
}

// Encode 将消息编码为字节。
func (m *Message) Encode() []byte {
	buf := new(bytes.Buffer)
	buf.WriteByte(m.StreamID) // 1 byte
	buf.WriteByte(m.Type)     // 1 byte
	buf.Write(m.Data)         // 可选 data
	return buf.Bytes()
}

// Decode 从字节解码消息。
func Decode(data []byte) (*Message, error) {
	if len(data) < HeaderSize {
		return nil, fmt.Errorf("invalid message size: %d < %d", len(data), HeaderSize)
	}

	return &Message{
		StreamID: data[0],
		Type:     data[1],
		Data:     data[2:],
	}, nil
}

// NewConnectMessage 创建 CONNECT 消息。
// DATA 为 ASCII 文本 "host:port|"。
func NewConnectMessage(streamID byte, host string, port uint16) *Message {
	payload := fmt.Sprintf("%s:%d|", host, port)
	return NewMessage(streamID, MsgTypeConnect, []byte(payload))
}

// NewDataMessage 创建 DATA 消息。
func NewDataMessage(streamID byte, data []byte) *Message {
	return NewMessage(streamID, MsgTypeData, data)
}

// NewCloseMessage 创建 CLOSE 消息。
func NewCloseMessage(streamID byte) *Message {
	return NewMessage(streamID, MsgTypeClose, nil)
}

// StreamIDToString 将流 ID 转换为十六进制字符串。
func StreamIDToString(streamID byte) string {
	return fmt.Sprintf("%02x", streamID)
}
