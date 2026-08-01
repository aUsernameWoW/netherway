//go:build windows

package main

import (
	"os"
	"os/exec"
	"syscall"
)

// CREATE_NO_WINDOW：不给子进程分配控制台。
// 少了这个，玩家启动游戏时会弹出一个黑色命令行窗口，"无感"就破功了。
const createNoWindow = 0x08000000

func detach(cmd *exec.Cmd) {
	cmd.SysProcAttr = &syscall.SysProcAttr{
		HideWindow:    true,
		CreationFlags: createNoWindow,
	}
}

// Windows 没有 SIGTERM 语义，os.Process.Signal 只支持 Kill。
func terminate(proc *os.Process) error {
	return proc.Kill()
}

// Windows 上 FindProcess 底层是 OpenProcess，进程不存在时会返回错误。
func processAlive(pid int) bool {
	proc, err := os.FindProcess(pid)
	if err != nil {
		return false
	}
	defer proc.Release()
	return true
}
