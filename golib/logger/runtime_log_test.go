package logger

import (
	"strings"
	"testing"
	"unicode/utf8"
)

func TestRuntimeLogStoreEvictsOldestLines(t *testing.T) {
	store := newRuntimeLogStore(2, 128)
	store.append("first")
	store.append("second")
	store.append("third")

	if got, want := store.snapshot(), "second\nthird"; got != want {
		t.Fatalf("snapshot = %q, want %q", got, want)
	}
}

func TestRuntimeLogStoreStaysWithinByteLimit(t *testing.T) {
	const maxBytes = 12
	store := newRuntimeLogStore(10, maxBytes)
	store.append("123456")
	store.append("abcdef")

	if got := store.snapshot(); len(got) >= maxBytes {
		t.Fatalf("snapshot length = %d, want less than %d", len(got), maxBytes)
	}
	if strings.Contains(store.snapshot(), "123456") {
		t.Fatal("oldest line was not evicted at the byte limit")
	}
}

func TestRuntimeLogStoreClear(t *testing.T) {
	store := newRuntimeLogStore(10, 128)
	store.append("session log")
	store.clear()

	if got := store.snapshot(); got != "" {
		t.Fatalf("snapshot after clear = %q, want empty", got)
	}
}

func TestRuntimeLogStoreTruncatesOnUTF8Boundary(t *testing.T) {
	store := newRuntimeLogStore(10, 8)
	store.append("中文日志")

	got := store.snapshot()
	if !utf8.ValidString(got) {
		t.Fatalf("snapshot contains invalid UTF-8: %q", got)
	}
	if got != "中文" {
		t.Fatalf("snapshot = %q, want %q", got, "中文")
	}
}
