package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// 后台模式配合启动器使用：PrismLauncher / MultiMC 的 Pre-launch 命令会
// 阻塞等待返回，所以不能在那里直接跑前台 join。正确用法是
//
//	Pre-launch:  xtcpinmc start
//	Post-exit:   xtcpinmc stop
//
// start 派生一个脱离终端的子进程后立即返回，游戏正常启动。

func stateDir() (string, error) {
	base, err := os.UserConfigDir()
	if err != nil {
		base = os.TempDir()
	}
	dir := filepath.Join(base, "xtcpinmc")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return "", fmt.Errorf("创建状态目录: %w", err)
	}
	return dir, nil
}

func pidFilePath() (string, error) {
	dir, err := stateDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(dir, "xtcpinmc.pid"), nil
}

func cmdStart(args []string) error {
	pidPath, err := pidFilePath()
	if err != nil {
		return err
	}

	if pid, ok := runningPID(pidPath); ok {
		fmt.Printf("已在后台运行（PID %d），不重复启动\n", pid)
		return nil
	}

	exe, err := os.Executable()
	if err != nil {
		return fmt.Errorf("定位自身可执行文件: %w", err)
	}

	dir, err := stateDir()
	if err != nil {
		return err
	}
	logPath := filepath.Join(dir, "xtcpinmc.log")
	logFile, err := os.OpenFile(logPath, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o644)
	if err != nil {
		return fmt.Errorf("打开日志文件: %w", err)
	}
	defer logFile.Close()

	cmd := exec.Command(exe, append([]string{"join"}, args...)...)
	cmd.Stdout = logFile
	cmd.Stderr = logFile
	detach(cmd) // 平台相关：脱离会话，Windows 上隐藏控制台窗口

	if err := cmd.Start(); err != nil {
		return fmt.Errorf("启动后台进程: %w", err)
	}

	if err := os.WriteFile(pidPath, []byte(strconv.Itoa(cmd.Process.Pid)), 0o644); err != nil {
		_ = cmd.Process.Kill()
		return fmt.Errorf("写 PID 文件: %w", err)
	}

	// 子进程可能因为参数错误立刻退出，这种情况下应当让启动器看到失败，
	// 而不是等玩家进游戏才发现连不上。
	time.Sleep(1500 * time.Millisecond)
	if _, ok := runningPID(pidPath); !ok {
		return fmt.Errorf("后台进程启动后立即退出，详见 %s", logPath)
	}

	fmt.Printf("已在后台启动（PID %d），日志 %s\n", cmd.Process.Pid, logPath)
	return nil
}

func cmdStop() error {
	pidPath, err := pidFilePath()
	if err != nil {
		return err
	}
	pid, ok := runningPID(pidPath)
	if !ok {
		fmt.Println("没有正在运行的后台实例")
		_ = os.Remove(pidPath)
		return nil
	}

	proc, err := os.FindProcess(pid)
	if err != nil {
		return fmt.Errorf("查找进程 %d: %w", pid, err)
	}
	if err := terminate(proc); err != nil {
		return fmt.Errorf("停止进程 %d: %w", pid, err)
	}

	// 等待退出，最多 5 秒；超时则强杀，避免残留进程占着端口
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		if !processAlive(pid) {
			_ = os.Remove(pidPath)
			fmt.Printf("已停止（PID %d）\n", pid)
			return nil
		}
		time.Sleep(100 * time.Millisecond)
	}
	_ = proc.Kill()
	_ = os.Remove(pidPath)
	fmt.Printf("已强制停止（PID %d）\n", pid)
	return nil
}

// runningPID 读取 PID 文件并确认该进程仍然存活。
// 进程崩溃或机器重启会留下陈旧的 PID 文件，必须校验而不能只看文件是否存在。
func runningPID(pidPath string) (int, bool) {
	data, err := os.ReadFile(pidPath)
	if err != nil {
		return 0, false
	}
	pid, err := strconv.Atoi(strings.TrimSpace(string(data)))
	if err != nil || pid <= 0 {
		return 0, false
	}
	return pid, processAlive(pid)
}
