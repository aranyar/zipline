#!/usr/bin/env python3
"""Plot per-cycle JVM heap and RSS from MemoryTrackingTest.memoryProfileForPlot.

The test prints CSV lines (memprofile,cycle,heapBytes,rssBytes) into the
Gradle test XML reports. This script extracts them and writes a PNG.

Usage:
  ./gradlew :zipline:jvmTest --tests "app.cash.zipline.MemoryTrackingTest.memoryProfileForPlot"
  ./gradlew :zipline:iosSimulatorArm64Test --tests "app.cash.zipline.MemoryTrackingTest.memoryProfileForPlot"
  python3 zipline/plot_memory_profile.py [output.png]
"""

import re
import sys
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt

ROOT = Path(__file__).resolve().parent

SOURCES = {
    "JVM": ROOT / "build/test-results/jvmTest/TEST-app.cash.zipline.MemoryTrackingTest.xml",
    "iOS simulator": (
        ROOT / "build/test-results/iosSimulatorArm64Test"
        / "TEST-iosSimulatorArm64Test.app.cash.zipline.MemoryTrackingTest.xml"
    ),
}

LINE = re.compile(r"memprofile,(\d+),(-?\d+),(-?\d+)")


def load(path):
    cycles, heap, rss = [], [], []
    for match in LINE.finditer(path.read_text(errors="replace")):
        cycles.append(int(match.group(1)))
        heap.append(int(match.group(2)))
        rss.append(int(match.group(3)))
    return cycles, heap, rss


def main():
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "memory_profile.png"
    fig, axes = plt.subplots(len(SOURCES), 1, figsize=(10, 6 * len(SOURCES)), sharex=False)
    if len(SOURCES) == 1:
        axes = [axes]

    for ax, (platform, path) in zip(axes, SOURCES.items()):
        if not path.exists():
            ax.set_title(f"{platform}: report not found ({path.name})")
            continue
        cycles, heap, rss = load(path)
        if not cycles:
            ax.set_title(f"{platform}: no memprofile lines found")
            continue
        ax.plot(cycles, [b / 1048576 for b in rss], label="RSS", color="tab:red")
        ax.set_ylabel("RSS, MB", color="tab:red")
        ax.tick_params(axis="y", labelcolor="tab:red")
        if any(h >= 0 for h in heap):
            ax_heap = ax.twinx()
            ax_heap.plot(
                cycles,
                [h / 1048576 if h >= 0 else float("nan") for h in heap],
                label="JVM heap (used)",
                color="tab:blue",
            )
            ax_heap.set_ylabel("JVM heap, MB", color="tab:blue")
            ax_heap.tick_params(axis="y", labelcolor="tab:blue")
            # Frame the data with margins instead of starting at zero, so a
            # flat line sits mid-plot rather than hugging a border.
            heap_mb = [h / 1048576 for h in heap if h >= 0]
            lo, hi = min(heap_mb), max(heap_mb)
            pad = max((hi - lo) * 0.5, hi * 0.05)
            ax_heap.set_ylim(max(lo - pad, 0), hi + pad)
        ax.set_title(f"{platform} — create/load/destroy per cycle, no warmup")
        ax.set_xlabel("cycle")
        ax.grid(True, alpha=0.3)

    fig.tight_layout()
    fig.savefig(out, dpi=150)
    print(f"wrote {out}")


if __name__ == "__main__":
    main()
