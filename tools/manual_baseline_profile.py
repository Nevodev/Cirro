"""Collect a Baseline Profile on an emulator with entirely manual app interaction."""

from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import zipfile

sys.dont_write_bytecode = True
from trackrow_benchmark import ROOT, execute, gradle


WORK = ROOT / "macrobenchmark/build/manual-profile"
SESSION = WORK / "session.json"
FINISH_FILE = "/data/local/tmp/cirro-manual-profile-finish"
TEST_CLASS = "com.nevoit.cirro.macrobenchmark.ManualBaselineProfile#capture"


def sdk_directory() -> Path:
    configured = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if configured:
        return Path(configured)
    properties = ROOT / "local.properties"
    if properties.is_file():
        for line in properties.read_text(encoding="utf-8").splitlines():
            if line.startswith("sdk.dir="):
                return Path(line.split("=", 1)[1].replace("\\:", ":").replace("\\\\", "\\"))
    adb = shutil.which("adb")
    if adb:
        return Path(adb).resolve().parent.parent
    raise ValueError("Android SDK not found. Set ANDROID_HOME.")


def adb_command(serial: str | None = None) -> list[str]:
    executable = sdk_directory() / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
    return [str(executable), *(["-s", serial] if serial else [])]


def emulator(serial: str | None) -> str:
    if not serial:
        devices = execute([*adb_command(), "devices"], capture=True).stdout
        candidates = [line.split()[0] for line in devices.splitlines()
                      if line.startswith("emulator-") and line.split()[1:2] == ["device"]]
        if len(candidates) != 1:
            raise ValueError("Start one emulator, or select it with --serial.")
        serial = candidates[0]
    is_emulator = execute(
        [*adb_command(serial), "shell", "getprop", "ro.kernel.qemu"], capture=True,
    ).stdout.strip()
    if is_emulator != "1":
        raise ValueError("Select an Android emulator; this workflow does not target your phone.")
    api = int(execute(
        [*adb_command(serial), "shell", "getprop", "ro.build.version.sdk"], capture=True,
    ).stdout.strip())
    if api < 34:
        execute([*adb_command(serial), "root"])
        execute([*adb_command(serial), "wait-for-device"])
        uid = execute([*adb_command(serial), "shell", "id", "-u"], capture=True).stdout.strip()
        if uid != "0":
            raise ValueError("Below API 34 use a rootable Google APIs image, to preserve login data.")
    return serial


def apk_metadata(module: str) -> tuple[Path, str]:
    folder = ROOT / module / "build/outputs/apk/benchmark"
    metadata = json.loads((folder / "output-metadata.json").read_text(encoding="utf-8"))
    elements = metadata["elements"]
    if len(elements) != 1:
        raise ValueError(f"Expected one {module} APK; build the manual profile variant first.")
    return folder / elements[0]["outputFile"], metadata["applicationId"]


def install(serial: str):
    target, _ = apk_metadata("app")
    test, _ = apk_metadata("macrobenchmark")
    with zipfile.ZipFile(target) as archive:
        if "lib/x86_64/libclassic.so" not in archive.namelist():
            raise ValueError("Run the build command first; the APK needs the x86_64 audio library.")
    # Replace in place: signature conflicts fail instead of uninstalling and losing login data.
    for apk in [target, test]:
        execute([*adb_command(serial), "install", "-r", "-t", str(apk)])
    print("安装完成。请在模拟器中打开 Cirro 并手动登录，之后运行 record。", flush=True)


def record(serial: str):
    if SESSION.exists():
        raise ValueError(f"A capture session already exists: {SESSION}. Finish that session first.")
    _, target_package = apk_metadata("app")
    _, test_package = apk_metadata("macrobenchmark")
    WORK.mkdir(parents=True, exist_ok=True)
    execute([*adb_command(serial), "shell", "rm", "-f", FINISH_FILE])
    SESSION.write_text(json.dumps({"serial": serial}), encoding="utf-8")
    arguments = [
        *adb_command(serial), "shell", "am", "instrument", "-w", "-r",
        "-e", "class", TEST_CLASS,
        "-e", "manualProfile", "true",
        "-e", "targetPackage", target_package,
        "-e", "androidx.benchmark.enabledRules", "BaselineProfile",
        f"{test_package}/androidx.test.runner.AndroidJUnitRunner",
    ]
    print("正在准备采集。看到‘采集已开始’后手动操作；结束时在另一终端运行 finish。", flush=True)
    try:
        # Keep raw instrumentation output: adb itself can return success for a failed test.
        log_path = WORK / "instrumentation.txt"
        with log_path.open("w", encoding="utf-8") as log, ThreadPoolExecutor(max_workers=1) as pool:
            pending = pool.submit(
                subprocess.run, arguments, cwd=ROOT, shell=False, check=True,
                stdout=log, stderr=subprocess.STDOUT, encoding="utf-8",
            )
            printed = 0
            while not pending.done():
                output = log_path.read_text(encoding="utf-8", errors="replace")
                print(output[printed:], end="", flush=True)
                printed = len(output)
                time.sleep(0.5)
            pending.result()
        output = log_path.read_text(encoding="utf-8")
        print(output[printed:], end="", flush=True)
        paths = re.findall(r"INSTRUMENTATION_STATUS: manualProfilePath=(.+)", output)
        if len(paths) != 1 or "OK (1 test)" not in output:
            raise ValueError(f"Capture failed. See {WORK / 'instrumentation.txt'}")
        raw = WORK / "baseline-prof.txt"
        execute([*adb_command(serial), "pull", paths[0].strip(), str(raw)])
        profile = raw.read_text(encoding="utf-8").strip()
        if not profile or "Lcom/nevoit/cirro/" not in profile:
            raise ValueError("No source-named Cirro rules found; the existing profile was not changed.")
        destination = ROOT / "app/src/main/baseline-prof.txt"
        destination.write_text(profile + "\n", encoding="utf-8", newline="\n")
        print(f"已写入 {destination}（{len(profile.splitlines())} 条规则）。", flush=True)
    finally:
        SESSION.unlink(missing_ok=True)


def finish(serial: str | None):
    if not SESSION.exists():
        raise ValueError("No manual capture is running. Start record first.")
    session = json.loads(SESSION.read_text(encoding="utf-8"))
    if serial and serial != session["serial"]:
        raise ValueError("The selected emulator is different from the active capture.")
    marker = WORK / "finish"
    marker.write_text("finished\n", encoding="utf-8")
    execute([*adb_command(session["serial"]), "push", str(marker), FINISH_FILE])
    print("已请求结束。请等待 record 终端完成导出。", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["build", "install", "record", "finish"])
    parser.add_argument("--serial", help="Select an emulator when several are running.")
    args = parser.parse_args()
    if args.command == "build":
        gradle([":app:assembleBenchmark", ":macrobenchmark:assembleBenchmark", "-PbaselineProfile=true"])
    elif args.command == "finish":
        finish(args.serial)
    else:
        serial = emulator(args.serial)
        if args.command == "install":
            install(serial)
        else:
            record(serial)


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(1) from error
