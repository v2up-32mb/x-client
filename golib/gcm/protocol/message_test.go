package protocol

import (
	"bytes"
	"testing"
)

func TestEncodeDecode(t *testing.T) {
	tests := []struct {
		name     string
		streamID byte
		msgType  byte
		data     []byte
	}{
		{
			name:     "CONNECT",
			streamID: 0x05,
			msgType:  MsgTypeConnect,
			data:     []byte("example.com:443|"),
		},
		{
			name:     "CONNECTED",
			streamID: 0xFF,
			msgType:  MsgTypeConnected,
			data:     nil,
		},
		{
			name:     "DATA",
			streamID: 0x42,
			msgType:  MsgTypeData,
			data:     []byte{0xDE, 0xAD, 0xBE, 0xEF},
		},
		{
			name:     "CLOSE",
			streamID: 0x00,
			msgType:  MsgTypeClose,
			data:     nil,
		},
		{
			name:     "DATA empty payload",
			streamID: 0x01,
			msgType:  MsgTypeData,
			data:     []byte{},
		},
		{
			name:     "DATA large payload",
			streamID: 0xAB,
			msgType:  MsgTypeData,
			data:     bytes.Repeat([]byte{0xAA}, 1000),
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			msg := NewMessage(tt.streamID, tt.msgType, tt.data)
			encoded := msg.Encode()

			// 验证头大小
			if len(encoded) < HeaderSize {
				t.Fatalf("encoded too short: %d bytes", len(encoded))
			}

			// 验证字节布局
			if encoded[0] != tt.streamID {
				t.Errorf("StreamID mismatch: got 0x%02x, want 0x%02x", encoded[0], tt.streamID)
			}
			if encoded[1] != tt.msgType {
				t.Errorf("Type mismatch: got 0x%02x, want 0x%02x", encoded[1], tt.msgType)
			}

			// 解码回环
			decoded, err := Decode(encoded)
			if err != nil {
				t.Fatalf("Decode error: %v", err)
			}
			if decoded.StreamID != tt.streamID {
				t.Errorf("decoded StreamID: got 0x%02x, want 0x%02x", decoded.StreamID, tt.streamID)
			}
			if decoded.Type != tt.msgType {
				t.Errorf("decoded Type: got 0x%02x, want 0x%02x", decoded.Type, tt.msgType)
			}
			if !bytes.Equal(decoded.Data, tt.data) {
				t.Errorf("decoded Data: got %v, want %v", decoded.Data, tt.data)
			}
		})
	}
}

func TestDecodeTooShort(t *testing.T) {
	// 1 字节不够 HeaderSize=2
	_, err := Decode([]byte{0x05})
	if err == nil {
		t.Fatal("expected error for too-short message, got nil")
	}

	// 0 字节
	_, err = Decode([]byte{})
	if err == nil {
		t.Fatal("expected error for empty message, got nil")
	}
}

func TestHeaderSize(t *testing.T) {
	if HeaderSize != 2 {
		t.Errorf("HeaderSize = %d, want 2", HeaderSize)
	}
}

func TestNewConnectMessage(t *testing.T) {
	msg := NewConnectMessage(0x10, "example.com", 443)
	encoded := msg.Encode()

	// [0x10][0x00] + "example.com:443|"
	if encoded[0] != 0x10 {
		t.Errorf("StreamID: got 0x%02x, want 0x10", encoded[0])
	}
	if encoded[1] != MsgTypeConnect {
		t.Errorf("Type: got 0x%02x, want 0x%02x (CONNECT)", encoded[1], MsgTypeConnect)
	}
	wantPayload := []byte("example.com:443|")
	if !bytes.Equal(encoded[2:], wantPayload) {
		t.Errorf("payload: got %q, want %q", encoded[2:], wantPayload)
	}
}

func TestNewDataMessage(t *testing.T) {
	data := []byte("hello world")
	msg := NewDataMessage(0x20, data)
	encoded := msg.Encode()

	if encoded[0] != 0x20 {
		t.Errorf("StreamID: got 0x%02x, want 0x20", encoded[0])
	}
	if encoded[1] != MsgTypeData {
		t.Errorf("Type: got 0x%02x, want 0x%02x (DATA)", encoded[1], MsgTypeData)
	}
	if !bytes.Equal(encoded[2:], data) {
		t.Errorf("payload mismatch")
	}
}

func TestNewCloseMessage(t *testing.T) {
	msg := NewCloseMessage(0x30)
	encoded := msg.Encode()

	if len(encoded) != HeaderSize {
		t.Errorf("CLOSE message length: got %d, want %d", len(encoded), HeaderSize)
	}
	if encoded[0] != 0x30 {
		t.Errorf("StreamID: got 0x%02x, want 0x30", encoded[0])
	}
	if encoded[1] != MsgTypeClose {
		t.Errorf("Type: got 0x%02x, want 0x%02x (CLOSE)", encoded[1], MsgTypeClose)
	}
}

func TestStreamIDToString(t *testing.T) {
	tests := []struct {
		input byte
		want  string
	}{
		{0x00, "00"},
		{0x0F, "0f"},
		{0xFF, "ff"},
		{0x42, "42"},
	}
	for _, tt := range tests {
		got := StreamIDToString(tt.input)
		if got != tt.want {
			t.Errorf("StreamIDToString(0x%02x) = %q, want %q", tt.input, got, tt.want)
		}
	}
}
