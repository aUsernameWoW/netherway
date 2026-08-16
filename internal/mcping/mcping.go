// Package mcping 实现 Minecraft 的 Server List Ping，用来判断隧道是否真的可用。
//
// frp 没有提供查询 visitor 打洞状态的 API（StatusExporter 只覆盖 proxy），
// 所以就绪判断只能靠主动探测。用 Minecraft 自己的握手而不是单纯的 TCP 连接，
// 好处是能一并确认服务端进程真的在响应，而不只是隧道端口被监听着。
package mcping

import (
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"time"

	"github.com/aUsernameWoW/netherway/internal/i18n"
)

// protocolVersion 用 1.7.10 的值。服务端只是回显状态，填什么都能拿到响应，
// 填真实值可以顺带确认版本没被中间层改写。
const protocolVersion = 5

type Status struct {
	Version struct {
		Name     string `json:"name"`
		Protocol int    `json:"protocol"`
	} `json:"version"`
	Players struct {
		Online int `json:"online"`
		Max    int `json:"max"`
	} `json:"players"`
}

func putVarInt(buf []byte, v int) []byte {
	uv := uint32(v)
	for {
		b := byte(uv & 0x7F)
		uv >>= 7
		if uv != 0 {
			buf = append(buf, b|0x80)
		} else {
			return append(buf, b)
		}
	}
}

func readVarInt(r io.Reader) (int, error) {
	var n int
	var shift uint
	one := make([]byte, 1)
	for i := 0; i < 5; i++ {
		if _, err := io.ReadFull(r, one); err != nil {
			return 0, err
		}
		n |= int(one[0]&0x7F) << shift
		if one[0]&0x80 == 0 {
			return n, nil
		}
		shift += 7
	}
	return 0, i18n.Errorf("mcping.varintTooLong")
}

// packet 给负载加上长度前缀。
func packet(payload []byte) []byte {
	return append(putVarInt(nil, len(payload)), payload...)
}

// Ping 对 host:port 执行一次完整的状态查询。
func Ping(host string, port int, timeout time.Duration) (*Status, time.Duration, error) {
	start := time.Now()
	addr := net.JoinHostPort(host, fmt.Sprint(port))

	conn, err := net.DialTimeout("tcp", addr, timeout)
	if err != nil {
		return nil, 0, err
	}
	defer conn.Close()
	if err := conn.SetDeadline(time.Now().Add(timeout)); err != nil {
		return nil, 0, err
	}

	// handshake：包 ID 0x00，next state = 1（status）
	var hs []byte
	hs = putVarInt(hs, 0x00)
	hs = putVarInt(hs, protocolVersion)
	hs = putVarInt(hs, len(host))
	hs = append(hs, host...)
	hs = binary.BigEndian.AppendUint16(hs, uint16(port))
	hs = putVarInt(hs, 1)
	if _, err := conn.Write(packet(hs)); err != nil {
		return nil, 0, err
	}

	// status request：空负载
	if _, err := conn.Write(packet(putVarInt(nil, 0x00))); err != nil {
		return nil, 0, err
	}

	if _, err := readVarInt(conn); err != nil { // 整包长度
		return nil, 0, err
	}
	if _, err := readVarInt(conn); err != nil { // 包 ID
		return nil, 0, err
	}
	strLen, err := readVarInt(conn)
	if err != nil {
		return nil, 0, err
	}
	if strLen <= 0 || strLen > 1<<20 {
		return nil, 0, i18n.Errorf("mcping.badStatusLen", strLen)
	}

	body := make([]byte, strLen)
	if _, err := io.ReadFull(conn, body); err != nil {
		return nil, 0, err
	}

	var st Status
	if err := json.Unmarshal(body, &st); err != nil {
		return nil, 0, i18n.Errorf("mcping.parseStatus", err)
	}
	return &st, time.Since(start), nil
}

// WaitReady 反复探测直到成功或超过 deadline，返回首次成功的状态。
func WaitReady(host string, port int, deadline time.Time, interval, probeTimeout time.Duration) (*Status, time.Duration, error) {
	var lastErr error
	for time.Now().Before(deadline) {
		// 单次探测的超时不能超过总剩余时间，否则会越过 deadline
		timeout := min(probeTimeout, time.Until(deadline))
		st, rtt, err := Ping(host, port, timeout)
		if err == nil {
			return st, rtt, nil
		}
		lastErr = err
		time.Sleep(interval)
	}
	if lastErr == nil {
		lastErr = i18n.Errorf("mcping.timeout")
	}
	return nil, 0, lastErr
}
