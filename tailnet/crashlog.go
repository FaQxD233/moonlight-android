package tailnet

import (
	"os"
	"runtime/debug"
)

// SetCrashLog also writes fatal Go errors (unrecovered panics, runtime faults)
// to path, so the app can show them after a crash; otherwise they only reach
// logcat. The file is appended to, and the app truncates it once shown.
func SetCrashLog(path string) error {
	f, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_APPEND, 0o600)
	if err != nil {
		return err
	}
	// SetCrashOutput keeps its own duplicate of the descriptor.
	defer f.Close()
	return debug.SetCrashOutput(f, debug.CrashOptions{})
}
