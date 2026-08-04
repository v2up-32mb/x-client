package protocol

import (
	"bytes"
	"encoding/binary"
	"testing"
)

// TestDecodeMessageRoundtrip 测试正常消息编解码 roundtrip
func TestDecodeMessageRoundtrip(t *testing.T) {
	tests := []struct {
		name    string
		tp      MessageType
		connID  string
		meta    []byte
		payload []byte
	}{
		{"empty", MsgTCPConnect, "", nil, nil},
		{"simple", MsgTCPData, "conn-1", []byte("meta"), []byte("hello")},
		{"longConnID", MsgTCPConnect, string(bytes.Repeat([]byte("a"), 255)), nil, nil},
		{"largeMeta", MsgTCPData, "c", bytes.Repeat([]byte("m"), 65535), nil},
		{"largePayload", MsgTCPData, "c", nil, bytes.Repeat([]byte("p"), 65535)},
		{"allFields", MsgTCPConnect, "abc", []byte("m1"), []byte("payload1")},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			encoded := EncodeMessage(tt.tp, tt.connID, tt.meta, tt.payload)
			tp2, connID2, meta2, payload2, err := DecodeMessage(encoded)
			if err != nil {
				t.Fatalf("unexpected error: %v", err)
			}
			if tp2 != tt.tp {
				t.Errorf("type: got %v, want %v", tp2, tt.tp)
			}
			if connID2 != tt.connID {
				t.Errorf("connID: got %q, want %q", connID2, tt.connID)
			}
			if !bytes.Equal(meta2, tt.meta) {
				t.Errorf("meta: got %v, want %v", meta2, tt.meta)
			}
			if !bytes.Equal(payload2, tt.payload) {
				t.Errorf("payload: got %v, want %v", payload2, tt.payload)
			}
		})
	}
}

// TestDecodeMessageTooShort 测试帧过短
func TestDecodeMessageTooShort(t *testing.T) {
	for i := 0; i < headerLen; i++ {
		b := make([]byte, i)
		_, _, _, _, err := DecodeMessage(b)
		if err == nil {
			t.Errorf("expected error for %d-byte input, got nil", i)
		}
	}
}

// TestDecodeMessageTruncated 测试截断包（header 完整但数据不足）
func TestDecodeMessageTruncated(t *testing.T) {
	// connID 长度声明为 5，但 body 只有 3 字节
	b := []byte{byte(MsgTCPConnect), 5, 0, 0, 0, 0, 0, 0, 'a', 'b', 'c'}
	_, _, _, _, err := DecodeMessage(b)
	if err == nil {
		t.Error("expected error for truncated message, got nil")
	}
}

// TestDecodeMessageMetaLenExceedsBuffer 测试 metaLen 超出 buffer
func TestDecodeMessageMetaLenExceedsBuffer(t *testing.T) {
	// metaLen=10, 但 body 只有 0 字节 (header 后无数据)
	b := make([]byte, headerLen)
	b[0] = byte(MsgTCPConnect)
	b[2] = 0  // metaLen high byte
	b[3] = 10 // metaLen = 10
	_, _, _, _, err := DecodeMessage(b)
	if err == nil {
		t.Error("expected error for metaLen exceeding buffer, got nil")
	}
}

// TestDecodeMessagePayloadLenExceedsBuffer 测试 payloadLen 超出 buffer
func TestDecodeMessagePayloadLenExceedsBuffer(t *testing.T) {
	// payloadLen=10, metaLen=0, connID=0, 但 buffer 只有 headerLen+1 字节
	b := make([]byte, headerLen+1)
	b[0] = byte(MsgTCPConnect)
	binary.BigEndian.PutUint32(b[4:8], 10) // payloadLen = 10
	_, _, _, _, err := DecodeMessage(b)
	if err == nil {
		t.Error("expected error for payloadLen exceeding buffer, got nil")
	}
}

// TestDecodeMessageIDLenExceedsBuffer 测试 idLen 超出 buffer
func TestDecodeMessageIDLenExceedsBuffer(t *testing.T) {
	// idLen=10, 但 buffer 只有 headerLen 字节
	b := make([]byte, headerLen)
	b[0] = byte(MsgTCPConnect)
	b[1] = 10 // idLen = 10
	_, _, _, _, err := DecodeMessage(b)
	if err == nil {
		t.Error("expected error for idLen exceeding buffer, got nil")
	}
}

// TestDecodeMessageHugePayloadLen 测试超大 payload 长度头导致 uint64 溢出场景
func TestDecodeMessageHugePayloadLen(t *testing.T) {
	// payloadLen = max uint32, 在 32 位平台上 int 会溢出
	b := make([]byte, headerLen)
	b[0] = byte(MsgTCPConnect)
	binary.BigEndian.PutUint32(b[4:8], 0xFFFFFFFF) // payloadLen = max uint32
	_, _, _, _, err := DecodeMessage(b)
	if err == nil {
		t.Error("expected error for huge payloadLen, got nil")
	}
}

// TestDecodeMessageHugePayloadLenWithinBuffer 测试超大 payload 长度头但 buffer 足够大的情况
func TestDecodeMessageHugePayloadLenWithinBuffer(t *testing.T) {
	// payloadLen 声明为非常大的值，但提供的 buffer 实际更小
	// total 应超过 len(b)，返回长度无效
	payloadLen := uint32(1000)
	b := make([]byte, headerLen+1+10)
	b[0] = byte(MsgTCPConnect)
	b[1] = 1           // idLen = 1
	b[2], b[3] = 0, 10 // metaLen = 10
	binary.BigEndian.PutUint32(b[4:8], payloadLen)
	// buffer 只有 headerLen+1+10=19 字节，但声明总长度需要 1019 字节
	_, _, _, _, err := DecodeMessage(b)
	if err == nil {
		t.Error("expected error for declared length exceeding actual buffer, got nil")
	}
}

// FuzzDecodeMessage 模糊测试：任意输入不应 panic
func FuzzDecodeMessage(f *testing.F) {
	// 添加一些种子语料
	f.Add([]byte{byte(MsgTCPConnect), 0, 0, 0, 0, 0, 0, 0})
	f.Add([]byte{byte(MsgTCPConnect), 1, 0, 0, 0, 0, 0, 0, 'a'})
	f.Add([]byte{byte(MsgTCPConnect), 0, 0, 0, 0, 0xFF, 0xFF, 0xFF, 0xFF})

	f.Fuzz(func(t *testing.T, data []byte) {
		DecodeMessage(data)
	})
}

// TestDecodeMessagePayloadLenExceedsMaxInt 测试 payloadLen 导致 total > maxInt 的场景
// 在 64 位平台上 maxInt 极大，但仍有逻辑保护；在 32 位平台上该检查至关重要
func TestDecodeMessagePayloadLenExceedsMaxInt(t *testing.T) {
	// 验证 DecodeMessage 不会 panic 或意外接受超大声明长度
	b := make([]byte, headerLen)
	b[0] = byte(MsgTCPConnect)
	// payloadLen 声明为 max uint32，但 buffer 极小
	binary.BigEndian.PutUint32(b[4:8], 0xFFFFFFFF)
	_, _, _, _, err := DecodeMessage(b)
	if err == nil {
		t.Error("expected error for total exceeding maxInt, got nil")
	}
}

// TestMessageTypeHasPrebindAndReset 验证 MsgPrebindRequest 和 MsgChannelReset 的值
func TestMessageTypeHasPrebindAndReset(t *testing.T) {
	if MsgPrebindRequest != 0x10 {
		t.Errorf("MsgPrebindRequest: got 0x%02x, want 0x10", MsgPrebindRequest)
	}
	if MsgChannelReset != 0x11 {
		t.Errorf("MsgChannelReset: got 0x%02x, want 0x11", MsgChannelReset)
	}
}

// TestPrebindTargetConstant 验证 PrebindTarget 常量值
func TestPrebindTargetConstant(t *testing.T) {
	if PrebindTarget != "x-tunnel.prebind" {
		t.Errorf("PrebindTarget: got %q, want %q", PrebindTarget, "x-tunnel.prebind")
	}
}
