#!/usr/bin/env python3
"""Convert a qjs-alloc-trace aggregate dump to Chrome Trace Event format flamegraph JSON.

The X axis represents memory (bytes), not time: each bucket's weight
(alloc_bytes by default) becomes the width of its frames. Every stack depth
is emitted on its own "thread" (lane), so chrome://tracing or Perfetto renders
it as a flamegraph.

Usage:
  python3 alloc_trace_flamegraph.py alloc_trace.txt -o flame.json
  python3 alloc_trace_flamegraph.py alloc_trace.txt --metric free --min-bytes 100000
  python3 alloc_trace_flamegraph.py alloc_trace.txt --native /path/to/libquickjs.so \
      --symbolizer ~/Library/Android/sdk/ndk/*/toolchains/llvm/prebuilt/*/bin/llvm-symbolizer
  python3 alloc_trace_flamegraph.py --diff dump1.txt dump2.txt -o diff.json
"""

import argparse
import json
import re
import subprocess
import sys

LINE_RE = re.compile(
    r"^S allocs=(\d+) alloc_bytes=(\d+) frees=(\d+) free_bytes=(\d+) "
    r"reallocs=(\d+) js=(.*?) native=(\S*)\s*$"
)


class Node:
    __slots__ = ("name", "weight", "self_weight", "children")

    def __init__(self, name):
        self.name = name
        self.weight = 0        # total weight of the subtree
        self.self_weight = 0   # weight of buckets ending exactly at this node
        self.children = {}


def parse_buckets(path, metric):
    buckets = []
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        for line in f:
            m = LINE_RE.match(line)
            if not m:
                continue
            allocs, alloc_bytes, frees, free_bytes, _reallocs, js, native = m.groups()
            alloc_bytes = int(alloc_bytes)
            free_bytes = int(free_bytes)
            if metric == "alloc":
                weight = alloc_bytes
            elif metric == "free":
                weight = free_bytes
            else:  # retained
                weight = alloc_bytes - free_bytes
            frames = [f for f in js.split(";") if f]
            if not frames:
                frames = ["<no js stack>"]
            # dump order is innermost-first; flamegraph wants root-first
            frames.reverse()
            buckets.append((weight, frames, native.split(",") if native else []))
    return buckets


def symbolize(native_offsets, library, symbolizer):
    unique = sorted({o for o in native_offsets if o})
    if not unique:
        return {}
    proc = subprocess.run(
        [symbolizer, "--obj=" + library, "--functions=linkage"],
        input="\n".join("0x" + o for o in unique),
        capture_output=True,
        text=True,
    )
    result = {}
    lines = proc.stdout.splitlines()
    for i, off in enumerate(unique):
        name = lines[2 * i] if 2 * i < len(lines) else ""
        result[off] = name if name and name != "??" else "0x" + off
    return result


def build_trie(buckets, min_bytes, native_names):
    """Build a synthetic-rooted trie keyed on the JS frames of each bucket.

    Frames are reversed to root-first; native C frames captured by the
    tracer are deeper than any JS frame in the call stack, so they are
    appended at the deepest positions.
    """
    root = Node("root")
    dropped = 0
    for weight, frames, native in buckets:
        if weight <= 0:
            continue
        if weight < min_bytes:
            dropped += 1
            continue
        if native_names:
            native_frames = ["[native] " + native_names.get(o, "0x" + o) for o in native if o]
        else:
            native_frames = ["[n:%04x]" % (int(o, 16) & 0xffff) for o in native if o]
        full_frames = frames + list(reversed(native_frames))
        node = root
        node.weight += weight
        for frame in full_frames:
            child = node.children.get(frame)
            if child is None:
                child = Node(frame)
                node.children[frame] = child
            child.weight += weight
            node = child
        node.self_weight += weight
    return root, dropped


def compute_max_depth(node):
    if not node.children:
        return 0
    return 1 + max(compute_max_depth(c) for c in node.children.values())


def emit_events(root):
    """One X event per trie node.

    tid = stack depth so trace viewers draw the events as a flamegraph;
    sf references the stackFrames map so each slice also carries its full
    call chain (rootmost parent -> ... -> this frame).
    """
    events = []
    stack_frames = {}
    next_id = [0]
    max_depth = compute_max_depth(root)

    def frame_id(node, parent_id):
        fid = str(next_id[0])
        next_id[0] += 1
        entry = {"name": node.name, "category": "alloc"}
        if parent_id is not None:
            entry["parent"] = parent_id
        stack_frames[fid] = entry
        return fid

    def emit(node, node_id, start, depth):
        events.append({
            "name": node.name,
            "cat": "alloc",
            "ph": "X",
            "ts": start,
            "dur": node.weight,
            "pid": 1,
            "tid": depth,
            "sf": node_id,
        })
        offset = start
        for child in sorted(node.children.values(), key=lambda c: -c.weight):
            cid = frame_id(child, node_id)
            emit(child, cid, offset, depth + 1)
            offset += child.weight

    root_id = frame_id(root, None)
    emit(root, root_id, 0, 0)
    for depth in range(max_depth + 1):
        events.append({
            "name": "thread_name",
            "ph": "M",
            "pid": 1,
            "tid": depth,
            "args": {"name": "depth %d" % depth},
        })
        events.append({
            "name": "thread_sort_index",
            "ph": "M",
            "pid": 1,
            "tid": depth,
            "args": {"sort_index": depth},
        })
    events.append({
        "name": "process_name",
        "ph": "M",
        "pid": 1,
        "args": {"name": "QuickJS allocations (X axis = bytes)"},
    })
    return events, stack_frames


def parse_buckets_diff(path1, path2, metric):
    """Per-stack delta of `metric` (positive = growth from dump1 to dump2)."""
    by_key = {}

    def add(path, sign):
        for weight, frames, native in parse_buckets(path, metric):
            key = (tuple(frames), tuple(native))
            by_key[key] = by_key.get(key, 0) + sign * weight

    add(path1, -1)
    add(path2, +1)
    return [(w, list(k[0]), list(k[1])) for k, w in by_key.items() if w > 0]


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("inputs", nargs="+",
                        help="qjs-alloc-trace dump(s): one or two for independent flamegraphs, "
                             "or two with --diff to compute newly-allocated objects")
    parser.add_argument("-o", "--outputs", nargs="+", default=[],
                        help="output JSON path(s), one per input (default: <input>.flame.json)")
    parser.add_argument("--metric", choices=["alloc", "free", "retained"], default="alloc",
                        help="weight per stack: alloc_bytes (default), free_bytes, or alloc-free")
    parser.add_argument("--min-bytes", type=int, default=65536,
                        help="drop buckets with weight below this (default: 65536)")
    parser.add_argument("--diff", action="store_true",
                        help="treat the two positional inputs as dump1 (earlier) and dump2 "
                             "(later); emit one flamegraph of positive per-stack deltas")
    parser.add_argument("--native", metavar="LIBQUICKJS.SO",
                        help="append symbolized native frames using this unstripped library")
    parser.add_argument("--symbolizer", default="llvm-symbolizer",
                        help="path to llvm-symbolizer (default: from PATH)")
    args = parser.parse_args()

    if args.diff:
        if len(args.inputs) != 2:
            parser.error("--diff requires exactly two inputs")
        output = args.outputs[0] if args.outputs else args.inputs[1] + ".diff.flame.json"
        buckets = parse_buckets_diff(args.inputs[0], args.inputs[1], args.metric)
        native_names = None
        if args.native:
            all_offsets = [o for _, _, native in buckets for o in native]
            native_names = symbolize(all_offsets, args.native, args.symbolizer)
        root, dropped = build_trie(buckets, args.min_bytes, native_names)
        events, stack_frames = emit_events(root)
        out = json.dumps({"traceEvents": events, "stackFrames": stack_frames}, indent=1)
        if output == "-":
            print(out)
        else:
            with open(output, "w") as f:
                f.write(out)
        print("diff: buckets=%d positive=%d dropped=%d total_growth_bytes=%d events=%d -> %s" % (
            len(buckets), len(buckets) - dropped, dropped, root.weight, len(events),
            output if output != "-" else "stdout"), file=sys.stderr)
        return

    outputs = list(args.outputs)
    while len(outputs) < len(args.inputs):
        outputs.append(args.inputs[len(outputs)] + ".flame.json")

    for input_path, output_path in zip(args.inputs, outputs):
        buckets = parse_buckets(input_path, args.metric)
        native_names = None
        if args.native:
            all_offsets = [o for _, _, native in buckets for o in native]
            native_names = symbolize(all_offsets, args.native, args.symbolizer)

        root, dropped = build_trie(buckets, args.min_bytes, native_names)
        events, stack_frames = emit_events(root)

        out = json.dumps({"traceEvents": events, "stackFrames": stack_frames}, indent=1)
        if output_path == "-":
            print(out)
        else:
            with open(output_path, "w") as f:
                f.write(out)
        print("buckets=%d pruned=%d total_%s_bytes=%d events=%d -> %s" % (
            len(buckets), dropped, args.metric, root.weight, len(events),
            output_path if output_path != "-" else "stdout"), file=sys.stderr)


if __name__ == "__main__":
    main()
