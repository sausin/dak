#!/usr/bin/env python3
"""Summarise Kover coverage, write a shields.io badge JSON and enforce per-module floors.

Reads the per-module Kover XML reports (<module>/build/reports/kover/reportCoverage.xml, JaCoCo format) and the
merged root report, then:
  * prints a Markdown table (and appends it to $GITHUB_STEP_SUMMARY when set),
  * writes a shields.io endpoint JSON for the README badge (--badge),
  * fails (exit 1) if any module, or the total, is below its floor in coverage-floors.json.

Line coverage is the headline metric; branch coverage is shown alongside. See docs/testing.md.

Usage (from android/):
  scripts/coverage-summary.py [--root-report build/reports/kover/reportCoverage.xml]
                              [--floors coverage-floors.json] [--badge build/coverage.json] [--jvm-only]
"""
import argparse
import json
import os
import sys
import xml.etree.ElementTree as ET

ANDROID_DIR = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
REPORT = os.path.join("build", "reports", "kover", "reportCoverage.xml")
ANDROID_MODULES = {"core-telephony", "core-index", "app"}


def counters(path):
    """Report-level counters: {'LINE': (covered, total), 'BRANCH': (...)}."""
    root = ET.parse(path).getroot()
    out = {}
    for c in root.findall("counter"):  # direct children of <report> only: the report totals
        covered, missed = int(c.get("covered")), int(c.get("missed"))
        out[c.get("type")] = (covered, covered + missed)
    return out


def pct(pair):
    if not pair or pair[1] == 0:
        return None
    return 100.0 * pair[0] / pair[1]


def fmt(value):
    return "n/a" if value is None else f"{value:.1f}%"


def badge_color(value):
    if value is None:
        return "lightgrey"
    for limit, color in ((80, "brightgreen"), (70, "green"), (60, "yellowgreen"), (50, "yellow"), (40, "orange")):
        if value >= limit:
            return color
    return "red"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root-report", default=os.path.join(ANDROID_DIR, REPORT), help="merged Kover XML report")
    ap.add_argument("--floors", default=os.path.join(ANDROID_DIR, "coverage-floors.json"))
    ap.add_argument("--badge", help="write a shields.io endpoint JSON here")
    ap.add_argument("--jvm-only", action="store_true",
                    help="skip the Android modules and the total floor (scripts/jvm-test.sh harness)")
    args = ap.parse_args()

    with open(args.floors) as f:
        floors = json.load(f)
    module_floors = floors["modules"]
    total_floor = floors.get("total", 0)

    rows, failures = [], []
    for module in sorted(module_floors):
        if args.jvm_only and module in ANDROID_MODULES:
            continue
        floor = module_floors[module]
        path = os.path.join(ANDROID_DIR, module, REPORT)
        if not os.path.exists(path):
            rows.append((module, None, None, None, floor, "missing report"))
            failures.append(f"{module}: no coverage report at {path}")
            continue
        c = counters(path)
        line, branch = pct(c.get("LINE")), pct(c.get("BRANCH"))
        status = "ok"
        if line is not None and line < floor:
            status = "below floor"
            failures.append(f"{module}: line coverage {line:.1f}% is below its floor of {floor}%")
        rows.append((module, line, branch, c.get("LINE"), floor, status))

    if not os.path.exists(args.root_report):
        print(f"error: merged report not found: {args.root_report}", file=sys.stderr)
        return 1
    tc = counters(args.root_report)
    total_line, total_branch = pct(tc.get("LINE")), pct(tc.get("BRANCH"))
    total_status = "ok"
    if not args.jvm_only and total_line is not None and total_line < total_floor:
        total_status = "below floor"
        failures.append(f"total: line coverage {total_line:.1f}% is below its floor of {total_floor}%")

    lines = [
        "## Code coverage (Kover)",
        "",
        "| Module | Line | Branch | Lines covered | Floor (line) | |",
        "| --- | ---: | ---: | ---: | ---: | --- |",
    ]
    for module, line, branch, lc, floor, status in rows:
        covered = f"{lc[0]}/{lc[1]}" if lc else "-"
        mark = "✅" if status == "ok" else f"❌ {status}"
        lines.append(f"| `{module}` | {fmt(line)} | {fmt(branch)} | {covered} | {floor}% | {mark} |")
    tl = tc.get("LINE")
    total_mark = "✅" if total_status == "ok" else f"❌ {total_status}"
    total_floor_cell = "-" if args.jvm_only else f"{total_floor}%"
    lines.append(f"| **Total{' (JVM modules)' if args.jvm_only else ''}** | **{fmt(total_line)}** | "
                 f"**{fmt(total_branch)}** | {tl[0] if tl else 0}/{tl[1] if tl else 0} | {total_floor_cell} | {total_mark} |")
    lines += ["", "Generated code and `@Composable` UI are excluded; see `docs/testing.md`."]
    table = "\n".join(lines) + "\n"
    print(table)

    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a") as f:
            f.write(table)
            if failures:
                f.write("\n**Coverage floors failed:**\n\n" + "".join(f"- {x}\n" for x in failures))

    if args.badge:
        message = fmt(total_line) if total_line is not None else "unknown"
        os.makedirs(os.path.dirname(os.path.abspath(args.badge)), exist_ok=True)
        with open(args.badge, "w") as f:
            json.dump({"schemaVersion": 1, "label": "coverage", "message": message,
                       "color": badge_color(total_line)}, f)
            f.write("\n")

    if failures:
        print("Coverage floors failed (raise tests, or lower a floor in coverage-floors.json with a reason):",
              file=sys.stderr)
        for x in failures:
            print(f"  - {x}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
