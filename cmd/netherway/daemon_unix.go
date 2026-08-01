//go:build !windows

package main

import (
	"errors"
	"os"
	"os/exec"
	"syscall"
)

// detach 让子进程脱离当前会话，这样终端关闭后隧道仍然存活。
func detach(cmd *exec.Cmd) {
	cmd.SysProcAttr = &syscall.SysProcAttr{Setsid: true}
}

func terminate(proc *os.Process) error {
	return proc.Signal(syscall.SIGTERM)
}

// processAlive 用信号 0 探测：Unix 上 os.FindProcess 对不存在的进程
// 也会返回成功，不能用它判断存活。EPERM 说明进程存在但不属于当前用户。
func processAlive(pid int) bool {
	proc, err := os.FindProcess(pid)
	if err != nil {
		return false
	}
	err = proc.Signal(syscall.Signal(0))
	return err == nil || errors.Is(err, syscall.EPERM)
}
