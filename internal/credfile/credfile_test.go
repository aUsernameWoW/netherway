package credfile

import (
	"bytes"
	"os"
	"path/filepath"
	"testing"
)

// TestEncodeGolden 用黄金向量钉住字节布局：这是与 Java 侧
// Credentials.encode() 的跨语言契约，改动必须两边同步。
func TestEncodeGolden(t *testing.T) {
	c := Credentials{
		BackendID:      "frp-xtcp",
		PunchTimeoutMs: 15000,
		Params: []KV{
			{Key: "room", Value: "gtnh"},
			{Key: "secret", Value: "s3"},
		},
	}
	want := []byte{
		2,                                        // FORMAT_VERSION
		0, 8, 'f', 'r', 'p', '-', 'x', 't', 'c', 'p', // writeUTF(backendId)
		0, 0, 0x3A, 0x98, // writeInt(15000)
		0, 2, // writeShort(参数个数)
		0, 4, 'r', 'o', 'o', 'm', 0, 4, 'g', 't', 'n', 'h',
		0, 6, 's', 'e', 'c', 'r', 'e', 't', 0, 2, 's', '3',
	}
	if got := c.Encode(); !bytes.Equal(got, want) {
		t.Fatalf("Encode 与黄金向量不符：\n got %x\nwant %x", got, want)
	}
}

// TestFileName 与 Java 侧 CredentialCache.fileNameOf 的派生规则一致：
// SHA-256(dedupKey) 前 8 字节的 hex + ".cred"。
// 期望值由 Java 侧同规则算出（dedupKey = "frp-xtcp:gtnh"）。
func TestFileName(t *testing.T) {
	c := Credentials{
		BackendID: "frp-xtcp",
		Params:    []KV{{Key: "room", Value: "gtnh"}},
	}
	if got, want := c.DedupKey(), "frp-xtcp:gtnh"; got != want {
		t.Fatalf("DedupKey = %q, want %q", got, want)
	}
	name := c.FileName()
	if len(name) != 16+len(Suffix) {
		t.Fatalf("文件名长度 %d 不符（%q）", len(name), name)
	}
	if name != FileNameOf("frp-xtcp", "gtnh") {
		t.Fatalf("FileName 与 FileNameOf 不一致：%q vs %q",
			name, FileNameOf("frp-xtcp", "gtnh"))
	}
}

func TestWriteRoundTrip(t *testing.T) {
	dir := t.TempDir()
	c := Credentials{
		BackendID:      "frp-xtcp",
		PunchTimeoutMs: 0,
		Params:         []KV{{Key: "room", Value: "gtnh"}},
	}
	path, err := c.Write(dir)
	if err != nil {
		t.Fatal(err)
	}
	if filepath.Base(path) != c.FileName() {
		t.Fatalf("落盘文件名 %q 与派生名 %q 不符", filepath.Base(path), c.FileName())
	}
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(data, c.Encode()) {
		t.Fatal("落盘内容与 Encode 不一致")
	}
	// 目录里不应残留临时文件
	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 1 {
		t.Fatalf("缓存目录应只有 1 个文件，实际 %d 个", len(entries))
	}
}
