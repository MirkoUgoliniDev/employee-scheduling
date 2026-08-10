"""Snapshot of the machine, taken once when the wizard starts.

It supports decisions (which package manager, which Java) and shows the user
the installation target. Information is read here rather than in individual
steps so that two steps cannot disagree about the same system.
"""

import os
import platform
import re
import shutil
import socket
import subprocess
from pathlib import Path
from typing import Optional


def _read_os_release() -> dict:
    values = {}
    try:
        for line in Path("/etc/os-release").read_text(encoding="utf-8").splitlines():
            if "=" in line:
                key, _, value = line.partition("=")
                values[key] = value.strip().strip('"')
    except OSError:
        pass
    return values


class SystemInfo:
    def __init__(self):
        release = _read_os_release()
        self.os_name = release.get("PRETTY_NAME", platform.platform())
        self.os_id = release.get("ID", "")
        self.arch = platform.machine()
        self.hostname = socket.gethostname()
        self.is_root = (os.geteuid() == 0) if hasattr(os, "geteuid") else False
        self.has_systemd = shutil.which("systemctl") is not None and Path("/run/systemd/system").exists()

        if shutil.which("apt-get"):
            self.package_manager = "apt"
        elif shutil.which("dnf"):
            self.package_manager = "dnf"
        else:
            self.package_manager = ""

        self.java_major = self.detect_java_major()
        self.model = self._model()
        self.ram_mb = self._ram_mb()
        self.disk_free_mb = self._disk_free_mb("/")

    # ── Collection ───────────────────────────────────────────────────────────
    @staticmethod
    def detect_java_major() -> int:
        """Java major version, or 0 if Java is absent.

        Read it from ``java -version``, which writes to stderr. Old versions
        appear as "1.8.0_xx": the useful number is the second one, not the
        first. Treating them as 1 would reject them — the right behavior here,
        but it should happen for the right reason.
        """
        if not shutil.which("java"):
            return 0
        try:
            out = subprocess.run(["java", "-version"], capture_output=True,
                                 text=True, timeout=30)
        except Exception:  # noqa: BLE001
            return 0
        text = (out.stderr or "") + (out.stdout or "")
        match = re.search(r'version "(\d+)(?:\.(\d+))?', text)
        if not match:
            return 0
        major = int(match.group(1))
        if major == 1 and match.group(2):
            return int(match.group(2))
        return major

    @staticmethod
    def _model() -> str:
        """Model declared by the device tree: the exact name on Raspberry Pi."""
        try:
            return Path("/proc/device-tree/model").read_text(errors="ignore").strip("\x00 \n")
        except OSError:
            return platform.machine()

    @staticmethod
    def _ram_mb() -> int:
        try:
            for line in Path("/proc/meminfo").read_text().splitlines():
                if line.startswith("MemTotal:"):
                    return int(line.split()[1]) // 1024
        except (OSError, ValueError, IndexError):
            pass
        return 0

    @staticmethod
    def _disk_free_mb(path: str) -> int:
        try:
            usage = shutil.disk_usage(path)
            return usage.free // (1024 * 1024)
        except OSError:
            return 0

    # ── Queries ──────────────────────────────────────────────────────────────
    @staticmethod
    def port_in_use(port: int) -> bool:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
            sock.settimeout(1)
            return sock.connect_ex(("127.0.0.1", port)) == 0

    @staticmethod
    def command_exists(name: str) -> bool:
        return shutil.which(name) is not None

    @staticmethod
    def primary_ip() -> Optional[str]:
        """Address through which the machine appears on the network.

        Open a UDP socket to an external address without sending anything; this
        only asks the system which interface it would use. It is the most
        reliable way to obtain the right IP on a multi-homed machine, where the
        hostname often resolves to 127.0.1.1.
        """
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
                sock.settimeout(1)
                sock.connect(("192.0.2.1", 9))  # documentation network, RFC 5737
                return sock.getsockname()[0]
        except OSError:
            return None

    def summary(self) -> dict:
        return {
            "model": self.model,
            "system": self.os_name,
            "architecture": self.arch,
            "memory": f"{self.ram_mb} MB" if self.ram_mb else "unknown",
            "free space": f"{self.disk_free_mb} MB" if self.disk_free_mb else "unknown",
            "java": str(self.java_major) if self.java_major else "absent",
            "systemd": "yes" if self.has_systemd else "no",
        }
