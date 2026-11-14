"""Opt-in GitHub smoke test. This does not emulate a Fabric player or verify the handshake."""
import argparse
import queue
import shutil
import subprocess
import threading
import time
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--server-jar", type=Path, required=True)
    parser.add_argument("--plugin-jar", type=Path, required=True)
    parser.add_argument("--runtime-dir", type=Path, required=True)
    parser.add_argument("--accept-eula", action="store_true")
    args = parser.parse_args()
    if not args.accept_eula:
        parser.error("Explicit acceptance of https://aka.ms/MinecraftEULA is required")
    server = args.server_jar.resolve(strict=True)
    plugin = args.plugin_jar.resolve(strict=True)
    runtime = args.runtime_dir.resolve()
    # New empty directory only: do not overwrite an existing world or run beside downloaded code.
    runtime.mkdir(parents=False, exist_ok=False)
    (runtime / "plugins").mkdir()
    (runtime / "logs").mkdir()
    shutil.copy2(plugin, runtime / "plugins" / plugin.name)
    (runtime / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    (runtime / "server.properties").write_text(
        "server-ip=127.0.0.1\nserver-port=25566\nmax-players=1\nview-distance=2\n"
        "simulation-distance=2\nonline-mode=true\nspawn-protection=0\n", encoding="utf-8")
    messages = queue.Queue()
    lines = []
    process = subprocess.Popen(
        ["java", "-Xms512M", "-Xmx2G", "-jar", str(server), "--nogui"],
        cwd=runtime, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        text=True, encoding="utf-8", errors="replace")

    def reader():
        with (runtime / "logs" / "smoke-console.log").open("w", encoding="utf-8") as log:
            for line in process.stdout:
                log.write(line)
                log.flush()
                lines.append(line)
                messages.put(line)
            messages.put(None)

    thread = threading.Thread(target=reader, daemon=True)
    thread.start()
    try:
        deadline = time.monotonic() + 480
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError("Server did not finish startup in 480 seconds")
            line = messages.get(timeout=remaining)
            if line is None:
                raise RuntimeError("Server exited before startup; inspect smoke-console.log")
            if "Done (" in line:
                break
        if not any("已加载" in line and "[NetSqueeze]" in line for line in lines):
            raise RuntimeError("NetSqueeze did not enable; expected a supported Minecraft 26+ release with matching network APIs")
        # Console commands are queued in order. Wait for evidence before requesting stop.
        process.stdin.write("nsq\nnsq players\nnsq reset\nnsq pipeline\n")
        process.stdin.flush()
        deadline = time.monotonic() + 60
        while True:
            text = "".join(lines)
            if "统计已清零" in text and "还没有玩家进服" in text and "还没有数据" in text:
                break
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError("Command smoke test did not produce the expected responses")
            if messages.get(timeout=remaining) is None:
                raise RuntimeError("Server exited during command smoke test")
        process.stdin.write("stop\n")
        process.stdin.flush()
        if process.wait(timeout=90) != 0:
            raise RuntimeError("Server shutdown returned a non-zero status")
        thread.join(timeout=10)
        text = "".join(lines)
        if "Disabling NetSqueeze" not in text:
            raise RuntimeError("Missing plugin disable evidence")
        if any(marker in text for marker in ("Error occurred while", "NoClassDefFoundError", "Exception in thread")):
            raise RuntimeError("Server log includes a startup/shutdown error; inspect uploaded logs")
        print("PASS: startup, console commands, clean shutdown. Player handshake was NOT tested.")
    finally:
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)
        thread.join(timeout=10)


if __name__ == "__main__":
    main()
