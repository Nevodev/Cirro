"""Build, run and summarize the liked-songs Macrobenchmark without shell commands."""

from __future__ import annotations

import argparse
from datetime import datetime
import json
import math
import os
from pathlib import Path
import shutil
import statistics
import subprocess
import sys
import time


ROOT = Path(__file__).resolve().parents[1]
TEST_CLASS = "com.nevoit.cirro.macrobenchmark.TrackRowBenchmark"
SCENARIOS = {"open": "openCollection", "down": "scrollDown", "back": "scrollBack"}


def execute(arguments: list[str], *, capture: bool = False, env: dict | None = None):
    return subprocess.run(
        arguments,
        cwd=ROOT,
        shell=False,
        check=True,
        encoding="utf-8",
        stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.STDOUT if capture else None,
        env=env,
    )


def gradle(arguments: list[str], env: dict | None = None):
    java_home = os.environ.get("JAVA_HOME")
    java = str(Path(java_home) / "bin" / "java.exe") if java_home else shutil.which("java")
    if not java or not Path(java).is_file():
        raise ValueError("Set JAVA_HOME to the JDK used by this project's Gradle wrapper.")
    execute(
        [java, "-classpath", str(ROOT / "gradle/wrapper/gradle-wrapper.jar"),
         "org.gradle.wrapper.GradleWrapperMain", *arguments],
        env=env,
    )


def percentile(values: list[float], percentage: float) -> float:
    ordered = sorted(values)
    position = (len(ordered) - 1) * percentage / 100
    lower = math.floor(position)
    upper = math.ceil(position)
    return ordered[lower] + (ordered[upper] - ordered[lower]) * (position - lower)


def summarize_benchmark(benchmark: dict) -> dict:
    summary = {"name": benchmark["name"], "metrics": {}}
    for name, metric in benchmark.get("sampledMetrics", {}).items():
        runs = [run for run in metric.get("runs", []) if run]
        values = [float(value) for run in runs for value in run]
        if not values:
            continue
        worst = [max(run) for run in runs]
        result = {
            "iterations": len(runs),
            "frames": len(values),
            "p50_ms": percentile(values, 50),
            "p95_ms": percentile(values, 95),
            "p99_ms": percentile(values, 99),
            "max_ms": max(values),
            "iteration_worst_median_ms": statistics.median(worst),
            "iteration_worst_p95_ms": percentile(worst, 95),
        }
        if name == "frameOverrunMs":
            fractions = [100 * sum(value > 0 for value in run) / len(run) for run in runs]
            result["overrun_frame_percent"] = 100 * sum(value > 0 for value in values) / len(values)
            result["iteration_overrun_percent_median"] = statistics.median(fractions)
        summary["metrics"][name] = result
    summary["thermal_throttle_sleep_seconds"] = benchmark.get("thermalThrottleSleepSeconds")
    return summary


def analyze(path: Path) -> list[dict]:
    candidates = [path] if path.is_file() else sorted(path.rglob("*benchmarkData.json"))
    summaries = []
    for candidate in candidates:
        report = json.loads(candidate.read_text(encoding="utf-8"))
        for benchmark in report.get("benchmarks", []):
            summary = summarize_benchmark(benchmark)
            summary["source"] = str(candidate.resolve())
            summaries.append(summary)
            print(f"\n{benchmark['name']}")
            for name, metric in summary["metrics"].items():
                print(
                    f"  {name}: {metric['iterations']} iterations, {metric['frames']} frames; "
                    f"P50 {metric['p50_ms']:.2f}, P95 {metric['p95_ms']:.2f}, "
                    f"P99 {metric['p99_ms']:.2f} ms"
                )
                print(
                    "    worst frame per iteration: "
                    f"median {metric['iteration_worst_median_ms']:.2f}, "
                    f"P95 {metric['iteration_worst_p95_ms']:.2f} ms"
                )
                if "overrun_frame_percent" in metric:
                    print(f"    overrun frames: {metric['overrun_frame_percent']:.2f}%")
            if "frameOverrunMs" not in summary["metrics"]:
                print("  No frameOverrunMs: do not infer missed deadlines from CPU duration alone.")
    if not summaries:
        raise ValueError(f"No benchmark results found under {path}")
    return summaries


def run_benchmark(args):
    label = args.label
    if not label or any(character not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_" for character in label):
        raise ValueError("Use letters, digits, '-' and '_' for --label.")
    if args.iterations < 5:
        raise ValueError("Use at least 5 iterations; 25 is recommended.")
    started = time.time()
    timestamp = datetime.now().strftime("%Y%m%d-%H%M%S-%f")
    destination = ROOT / "macrobenchmark/build/runs" / f"{timestamp}-{label}"
    destination.mkdir(parents=True, exist_ok=False)
    metadata = {
        "label": label,
        "started_at": datetime.now().astimezone().isoformat(),
        "scenario": args.scenario,
        "iterations": args.iterations,
        "git_head": execute(["git", "rev-parse", "HEAD"], capture=True).stdout.strip(),
        "git_status": execute(["git", "status", "--short"], capture=True).stdout,
        "compilation": "Partial; baseline disabled; 3 profile warmups; WARM process",
        "notes": args.notes,
    }
    (destination / "run.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2), encoding="utf-8")
    arguments = [
        ":macrobenchmark:connectedBenchmarkAndroidTest",
        f"-Pbenchmark.iterations={args.iterations}",
        "-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true",
        "-Pandroid.experimental.testOptions.uninstallIncompatibleApks=false",
    ]
    test = TEST_CLASS
    if args.scenario != "all":
        test += "#" + SCENARIOS[args.scenario]
    arguments.append(f"-Pandroid.testInstrumentationRunnerArguments.class={test}")
    environment = os.environ.copy()
    if args.serial:
        environment["ANDROID_SERIAL"] = args.serial
    try:
        gradle(arguments, environment)
    finally:
        outputs = ROOT / "macrobenchmark/build/outputs/connected_android_test_additional_output"
        if outputs.exists():
            for source in outputs.rglob("*"):
                if source.is_file() and source.stat().st_mtime >= started - 2:
                    target = destination / source.relative_to(outputs)
                    target.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copy2(source, target)
        print(f"\nRun artifacts: {destination}", flush=True)
    summaries = analyze(destination)
    (destination / "summary.json").write_text(json.dumps(summaries, ensure_ascii=False, indent=2), encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("build", help="Build the optimized target APK and benchmark APK; no installation.")
    run = commands.add_parser("run", help="Install and measure 资料库 → 喜爱歌曲; retain app data.")
    run.add_argument("--label", required=True, help="For example baseline-a or optimized-b.")
    run.add_argument("--scenario", choices=["open", "down", "back", "all"], default="all")
    run.add_argument("--iterations", type=int, default=25)
    run.add_argument("--serial", help="Select one device when several are connected.")
    run.add_argument("--notes", default="", help="Record refresh rate, playback/cache state and temperature conditions.")
    report = commands.add_parser("analyze", help="Summarize an existing benchmarkData JSON file or run directory.")
    report.add_argument("path", type=Path)
    args = parser.parse_args()
    if args.command == "build":
        gradle([":app:assembleBenchmark", ":macrobenchmark:assembleBenchmark"])
    elif args.command == "run":
        run_benchmark(args)
    else:
        print(json.dumps(analyze(args.path), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    try:
        main()
    except (ValueError, subprocess.CalledProcessError) as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(1) from error
