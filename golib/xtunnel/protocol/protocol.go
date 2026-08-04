// Package common 提供 client 和 server 共享的二进制协议定义
package protocol

import (
	"encoding/binary"
	"errors"
)

// ======================== 二进制协议 ========================

// MessageType 消息类型
type MessageType uint8

const (
	MsgTCPConnect MessageType = iota + 1
	MsgTCPData
	MsgTCPClose
	MsgUDPConnect
	MsgUDPData
	MsgUDPClose
	MsgConnStatus
	MsgSelectUplink
	MsgSelectDownlink
	MsgBackpressure
	MsgPrebindRequest MessageType = 0x10 // 预绑定请求
	MsgChannelReset   MessageType = 0x11 // 通道重置通知
)

// PrebindTarget 预绑定目标标识
const PrebindTarget = "x-tunnel.prebind"

// BackpressureState 背压状态
type BackpressureState uint8

const (
	BackpressureNormal   BackpressureState = 0 // 恢复正常
	BackpressureSlowDown BackpressureState = 1 // 减速
	BackpressurePause    BackpressureState = 2 // 暂停
)

// ConnStatus 连接状态
type ConnStatus uint8

const (
	StatusOK  ConnStatus = 0
	StatusERR ConnStatus = 1
)

const headerLen = 8

// maxInt 是 int 类型的最大值，用于防止切片分配溢出
const maxInt = int(^uint(0) >> 1)

// EncodeMessage 编码消息
func EncodeMessage(t MessageType, connID string, meta, payload []byte) []byte {
	if len(connID) > 255 {
		connID = connID[:255]
	}
	buf := make([]byte, headerLen+len(connID)+len(meta)+len(payload))
	buf[0] = byte(t)
	buf[1] = byte(len(connID))
	binary.BigEndian.PutUint16(buf[2:4], uint16(len(meta)))
	binary.BigEndian.PutUint32(buf[4:8], uint32(len(payload)))
	off := headerLen
	copy(buf[off:], connID)
	off += len(connID)
	copy(buf[off:], meta)
	off += len(meta)
	copy(buf[off:], payload)
	return buf
}

// DecodeMessage 解码消息
func DecodeMessage(b []byte) (t MessageType, connID string, meta, payload []byte, err error) {
	if len(b) < headerLen {
		return 0, "", nil, nil, errors.New("帧过短")
	}
	t = MessageType(b[0])
	idLen := int(b[1])
	metaLen := int(binary.BigEndian.Uint16(b[2:4]))
	payloadLen32 := binary.BigEndian.Uint32(b[4:8])

	// 使用 uint64 计算总长度，避免 32 位平台上 int 溢出
	total := uint64(headerLen) + uint64(idLen) + uint64(metaLen) + uint64(payloadLen32)
	if total > uint64(len(b)) || total > uint64(maxInt) {
		return 0, "", nil, nil, errors.New("长度无效")
	}

	payloadLen := int(payloadLen32)
	off := headerLen
	connID = string(b[off : off+idLen])
	off += idLen
	meta = b[off : off+metaLen]
	off += metaLen
	payload = b[off : off+payloadLen]
	return t, connID, meta, payload, nil
}
