// Package credfile 读写 Java 侧 CredentialCache 兼容的凭证文件。
//
// prefetch 子命令拿到预认证服务下发的凭证后按这个格式落盘，
// 游戏启动时 WarmupController 就能读它预热打洞——首次进服即直连。
//
// 字节布局与 Java 侧 Credentials.encode() 逐字节一致：
//
//	byte  FORMAT_VERSION = 2
//	writeUTF(backendId)        // 2 字节大端长度 + UTF-8
//	writeInt(punchTimeoutMs)   // 4 字节大端
//	writeShort(len(params))    // 2 字节大端
//	每个参数: writeUTF(key) + writeUTF(value)
//
// writeUTF 用标准 UTF-8 即可：凭证内容（地址/UUID/房间名/密钥）不含
// null 字符与代理对，与 Java 的 modified UTF-8 在此场景下结果相同。
package credfile

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"os"
	"path/filepath"
)

// FormatVersion 与 Java 侧 Credentials.FORMAT_VERSION 一致。
const FormatVersion = 2

// Suffix 与 Java 侧 CredentialCache.SUFFIX 一致。
const Suffix = ".cred"

// KV 是一个 backend 参数，保序以匹配 Java 侧 LinkedHashMap。
type KV struct {
	Key   string
	Value string
}

// Credentials 是凭证的最小表示，仅用于编解码与落盘。
type Credentials struct {
	BackendID      string
	PunchTimeoutMs int
	Params         []KV
}

// Encode 按 Java 侧 Credentials.encode() 的字节格式编码。
func (c Credentials) Encode() []byte {
	b := []byte{FormatVersion}
	b = appendUTF(b, c.BackendID)
	b = appendInt32(b, int32(c.PunchTimeoutMs))
	b = appendUint16(b, uint16(len(c.Params)))
	for _, kv := range c.Params {
		b = appendUTF(b, kv.Key)
		b = appendUTF(b, kv.Value)
	}
	return b
}

// Room 取 room 参数；与 Java 侧 Credentials.room() 对应。
func (c Credentials) Room() string {
	for _, kv := range c.Params {
		if kv.Key == "room" {
			return kv.Value
		}
	}
	return ""
}

// DedupKey 与 Java 侧 Credentials.dedupKey() 一致。
func (c Credentials) DedupKey() string {
	return c.BackendID + ":" + c.Room()
}

// FileName 与 Java 侧 CredentialCache.fileNameOf 一致：
// dedupKey 的 SHA-256 前 8 字节的 hex + ".cred"。
func (c Credentials) FileName() string {
	return FileNameOf(c.BackendID, c.Room())
}

// FileNameOf 按 backendID + room 计算缓存文件名。
// prefetch 拿到 authbridge 下发的已编码凭证字节后，可直接用它算文件名写盘，
// 不必反向解析出 Credentials 结构再 Encode。
func FileNameOf(backendID, room string) string {
	sum := sha256.Sum256([]byte(backendID + ":" + room))
	return hex.EncodeToString(sum[:8]) + Suffix
}

// Write 把凭证写进 cacheDir，文件名按 dedupKey 派生。
// 先写临时文件再改名，与 Java 侧同纪律——同时启动两份不会读到写一半的文件。
func (c Credentials) Write(cacheDir string) (string, error) {
	return WriteRaw(c.Encode(), c.BackendID, c.Room(), cacheDir)
}

// WriteRaw 把已编码的凭证字节写进 cacheDir，文件名按 backendID+room 派生。
// prefetch 拿到 authbridge 下发的已编码字节后直接调用，不必反向解析再 Encode。
func WriteRaw(data []byte, backendID, room, cacheDir string) (string, error) {
	if err := os.MkdirAll(cacheDir, 0o755); err != nil {
		return "", fmt.Errorf("创建缓存目录: %w", err)
	}
	target := filepath.Join(cacheDir, FileNameOf(backendID, room))

	tmp, err := os.CreateTemp(cacheDir, "cred-*.part")
	if err != nil {
		return "", fmt.Errorf("创建临时文件: %w", err)
	}
	tmpPath := tmp.Name()
	defer os.Remove(tmpPath)
	if _, err := tmp.Write(data); err != nil {
		tmp.Close()
		return "", fmt.Errorf("写入凭证: %w", err)
	}
	tmp.Close()
	// 0o600 与 Java 侧 restrictToOwner 同意图：仅属主可读写
	_ = os.Chmod(tmpPath, 0o600)
	if err := os.Rename(tmpPath, target); err != nil {
		// 某些文件系统不支持原子改名，退回直接写
		if err := os.WriteFile(target, data, 0o600); err != nil {
			return "", fmt.Errorf("写入缓存文件: %w", err)
		}
	}
	return target, nil
}

func appendUTF(b []byte, s string) []byte {
	u := []byte(s)
	if len(u) > 0xFFFF {
		panic("UTF 字符串过长: " + s)
	}
	b = append(b, byte(len(u)>>8), byte(len(u)))
	return append(b, u...)
}

func appendInt32(b []byte, v int32) []byte {
	return append(b, byte(v>>24), byte(v>>16), byte(v>>8), byte(v))
}

func appendUint16(b []byte, v uint16) []byte {
	return append(b, byte(v>>8), byte(v))
}
