#!/usr/bin/env python3
"""
simulate_protocol.py — local simulation of the finish-photo filter pipeline.

Reads a debug bundle exported from Virtual Volunteer and applies all filter
rules that run in FinishPhotoPipeline / StartPhotoIngestor to predict:
  • which protocol rows would survive after re-processing with updated code
  • which duplicate pairs could merge via the expanded series-match window
  • estimated row count vs the target

Usage
-----
  python3 scripts/simulate_protocol.py /path/to/debug_bundle [--notes notes.txt]

  --notes   optional path to a plain-text file with per-row annotations.
            Format: one line per protocol row (1-based) with a short label.
            Lines starting with '#' are ignored.  Example:
              1  ok
              2  non-participant
              3  dupe#1
              ...

Constants (mirror the Kotlin code)
-----------------------------------
  SMALL_FACE_HARD_FLOOR_PX2     = 3_000
  SMALL_FACE_MIN_AREA_PX2       = 10_000
  SMALL_FACE_RELATIVE_RATIO     = 3
  PERIPHERAL_PROFILE_ASPECT     = 0.65    (w/h threshold for profile face)
  PERIPHERAL_X_HALF             = 0.19    (|cx-0.5| threshold)
  PERIPHERAL_AREA_DOMINATION    = 1.3
  PERIPHERAL_CENTER_MARGIN      = 0.05
  START_PERIPHERAL_HARD_X       = 0.20    (absolute start-photo edge cutoff)
  SERIES_TIGHT_WINDOW_MS        = 2_000
  SERIES_TIGHT_COSINE           = 0.20
  SERIES_NORMAL_COSINE          = 0.30
  SERIES_MAX_CENTER_DELTA       = 0.12
  SERIES_MAX_SIZE_RATIO         = 3.0
"""

import argparse
import os
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict
from datetime import datetime, timezone
from typing import Optional

# ---------------------------------------------------------------------------
# Constants (keep in sync with FinishPhotoPipeline / StartPhotoIngestor)
# ---------------------------------------------------------------------------
SMALL_FACE_HARD_FLOOR_PX2 = 3_000
SMALL_FACE_MIN_AREA_PX2   = 10_000
SMALL_FACE_RELATIVE_RATIO = 3

PERIPHERAL_PROFILE_ASPECT  = 0.65
PERIPHERAL_X_HALF          = 0.19
PERIPHERAL_CENTER_MARGIN   = 0.05
FRONTAL_EDGE_X             = 0.20
FRONTAL_EDGE_MIN_AREA_PX2  = 15_000

START_PERIPHERAL_HARD_X = 0.20

SERIES_TIGHT_WINDOW_MS  = 2_000
SERIES_TIGHT_COSINE     = 0.20
SERIES_NORMAL_COSINE    = 0.30
SERIES_MAX_CENTER_DELTA = 0.12
SERIES_MAX_SIZE_RATIO   = 3.0

VW_DEFAULT = 4096
VH_DEFAULT = 3072

# ---------------------------------------------------------------------------
# Data loading
# ---------------------------------------------------------------------------

def load_manifest(debug_dir: str):
    path = os.path.join(debug_dir, "face_crop_manifest.xml")
    root = ET.parse(path).getroot()
    entries = []
    for e in root.findall("entry"):
        fn = os.path.basename(e.get("sourcePath", ""))
        l  = int(e.get("left",   0))
        t  = int(e.get("top",    0))
        r  = int(e.get("right",  0))
        b  = int(e.get("bottom", 0))
        pid = int(e.get("participantHashId", e.get("participantId", 0)))
        vw  = int(e.get("visionWidth",  VW_DEFAULT))
        vh  = int(e.get("visionHeight", VH_DEFAULT))
        w = r - l; h = b - t
        cx = (l + r) / 2 / vw
        cy = (t + b) / 2 / vh
        entries.append({
            "fn": fn, "pid": pid, "area": w * h,
            "cx": cx, "cy": cy, "w": w, "h": h,
            "is_finish": fn.startswith("finish_"),
            "is_start":  fn.startswith("start_"),
        })
    return entries


def load_protocol(debug_dir: str):
    path = os.path.join(debug_dir, "protocol.xml")
    root = ET.parse(path).getroot()
    rows = []
    for e in root.findall("finish"):
        pid = int(e.get("participantHashId", 0))
        ft  = e.get("finishTime", "")
        try:
            dt    = datetime.fromisoformat(ft.replace("Z", "+00:00"))
            ft_ms = int(dt.timestamp() * 1000)
        except Exception:
            ft_ms = 0
        rows.append({"pid": pid, "ft_ms": ft_ms})
    rows.sort(key=lambda x: x["ft_ms"])
    return rows


def load_race(debug_dir: str) -> int:
    """Return race start time in epoch-ms (0 if not found)."""
    path = os.path.join(debug_dir, "race.xml")
    if not os.path.exists(path):
        return 0
    root = ET.parse(path).getroot()
    # Try several attribute names used by different app versions
    for attr in ("startedAtEpochMillis", "startedAt", "startedAtMs"):
        val = root.get(attr) or root.findtext(attr)
        if val:
            return int(val)
    return 0


def photo_ts(fn: str) -> int:
    try:
        return int(fn.replace("finish_", "").replace(".jpg", ""))
    except Exception:
        return 0


def race_time(ts_ms: int, start_ms: int) -> str:
    if start_ms == 0 or ts_ms == 0:
        return "?:??"
    s = (ts_ms - start_ms) // 1000
    sign = "-" if s < 0 else ""
    s = abs(s)
    return f"{sign}{s // 60}:{s % 60:02d}"

# ---------------------------------------------------------------------------
# Filter predicates (mirror FinishPhotoPipeline logic)
# ---------------------------------------------------------------------------

def _has_center_face(face, photo_faces) -> bool:
    """Any other face strictly more central than this face by > PERIPHERAL_CENTER_MARGIN."""
    cx_half = abs(face["cx"] - 0.5)
    return any(
        abs(o["cx"] - 0.5) < cx_half - PERIPHERAL_CENTER_MARGIN
        for o in photo_faces if o["pid"] != face["pid"]
    )


def is_edge_filtered(face, photo_faces) -> bool:
    """Returns True if this face should be skipped by the two-part edge filter."""
    ar = face["w"] / face["h"] if face["h"] > 0 else 1.0
    cx_half = abs(face["cx"] - 0.5)
    is_profile = ar < PERIPHERAL_PROFILE_ASPECT
    is_alone   = len(photo_faces) == 1

    # Part A: profile face at edge
    if is_profile and cx_half > PERIPHERAL_X_HALF:
        return is_alone or _has_center_face(face, photo_faces)

    # Part B: frontal face at edge, large enough to be a nearby bystander
    if (not is_profile
            and cx_half > FRONTAL_EDGE_X
            and face["area"] >= FRONTAL_EDGE_MIN_AREA_PX2):
        return _has_center_face(face, photo_faces)

    return False


def size_filter_would_keep(face, photo_faces) -> bool:
    """Returns True if the face passes hard-floor + relative size filters."""
    if face["area"] < SMALL_FACE_HARD_FLOOR_PX2:
        return False
    max_area = max(f["area"] for f in photo_faces)
    if max_area >= SMALL_FACE_MIN_AREA_PX2:
        cutoff = max(SMALL_FACE_MIN_AREA_PX2, max_area // SMALL_FACE_RELATIVE_RATIO)
        if face["area"] < cutoff:
            return False
    return True


def finish_face_passes(face, photo_faces) -> bool:
    """Returns True if a finish-photo face would not be filtered."""
    return (size_filter_would_keep(face, photo_faces) and
            not is_edge_filtered(face, photo_faces))


# ---------------------------------------------------------------------------
# Series-match prediction (geometry only, no embeddings)
# ---------------------------------------------------------------------------

def series_could_merge(ref, dup) -> tuple:
    """
    Given two face detections (from different photos, ref before dup),
    returns (could_merge: bool, reason: str) based purely on geometric checks.
    We cannot know the cosine similarity without embeddings, so we report
    'GEOMETRY_OK / GEOMETRY_FAIL' and note which cosine threshold applies.
    """
    gap_ms = photo_ts(dup["fn"]) - photo_ts(ref["fn"])
    if gap_ms <= 0 or gap_ms > 2 * SERIES_TIGHT_WINDOW_MS:
        return False, f"gap={gap_ms}ms > series window"

    cx_delta = abs(ref["cx"] - dup["cx"])
    if cx_delta > SERIES_MAX_CENTER_DELTA:
        return False, f"cx_delta={cx_delta:.3f} > {SERIES_MAX_CENTER_DELTA}"

    if ref["area"] > 0 and dup["area"] > 0:
        ratio = max(ref["area"], dup["area"]) / min(ref["area"], dup["area"])
        if ratio > SERIES_MAX_SIZE_RATIO:
            return False, f"size_ratio={ratio:.2f} > {SERIES_MAX_SIZE_RATIO}"

    cosine_threshold = (SERIES_TIGHT_COSINE if gap_ms <= SERIES_TIGHT_WINDOW_MS
                        else SERIES_NORMAL_COSINE)
    return True, (f"gap={gap_ms}ms ok, cx_delta={cx_delta:.3f} ok, "
                  f"cosine >= {cosine_threshold} needed (no embedding data)")

# ---------------------------------------------------------------------------
# Notes loading
# ---------------------------------------------------------------------------

def load_notes(path: str) -> dict:
    notes = {}
    with open(path) as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split(None, 1)
            if len(parts) == 2:
                try:
                    notes[int(parts[0])] = parts[1]
                except ValueError:
                    pass
    return notes

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(description="Simulate Virtual Volunteer filter pipeline.")
    parser.add_argument("bundle", help="Path to debug bundle directory")
    parser.add_argument("--notes", default=None, help="Optional ground-truth annotations file")
    args = parser.parse_args()

    bundle = args.bundle
    if not os.path.isdir(bundle):
        print(f"ERROR: not a directory: {bundle}", file=sys.stderr)
        sys.exit(1)

    entries  = load_manifest(bundle)
    protocol = load_protocol(bundle)
    start_ms = load_race(bundle)
    notes    = load_notes(args.notes) if args.notes else {}

    finish_entries = [e for e in entries if e["is_finish"]]
    start_entries  = [e for e in entries if e["is_start"]]

    by_photo  = defaultdict(list)
    for e in finish_entries:
        by_photo[e["fn"]].append(e)

    by_pid_finish = defaultdict(list)
    for e in finish_entries:
        by_pid_finish[e["pid"]].append(e)

    pos_to_pid = {i + 1: r["pid"] for i, r in enumerate(protocol)}

    # ------------------------------------------------------------------
    # 1. Start-photo filter simulation
    # ------------------------------------------------------------------
    print("=" * 72)
    print("START PHOTO SEEDS")
    print("=" * 72)
    start_kept = []
    start_pids_by_photo = defaultdict(list)
    for e in start_entries:
        start_pids_by_photo[e["fn"]].append(e)

    for fn, faces in sorted(start_pids_by_photo.items()):
        max_area = max(f["area"] for f in faces)
        cutoff   = (max(SMALL_FACE_MIN_AREA_PX2, max_area // SMALL_FACE_RELATIVE_RATIO)
                    if max_area >= SMALL_FACE_MIN_AREA_PX2 else 0)
        for face in sorted(faces, key=lambda x: -x["area"]):
            hard_fail  = face["area"] < SMALL_FACE_HARD_FLOOR_PX2
            small_fail = cutoff > 0 and face["area"] < cutoff
            periph_fail = abs(face["cx"] - 0.5) > START_PERIPHERAL_HARD_X
            status = "SKIP" if (hard_fail or small_fail or periph_fail) else "KEEP"
            reason = ("hard_floor" if hard_fail else
                      "small_relative" if small_fail else
                      f"peripheral |cx-0.5|={abs(face['cx']-0.5):.3f}>{START_PERIPHERAL_HARD_X}" if periph_fail
                      else "ok")
            print(f"  [{status}] {fn}  pid={face['pid']}  cx={face['cx']:.3f}  "
                  f"area={face['area']:,}  ar={face['w']/face['h']:.2f}  reason={reason}")
            if status == "KEEP":
                start_kept.append(face["pid"])

    print(f"\n  Seeds kept: {len(start_kept)} pids={sorted(start_kept)}")

    # ------------------------------------------------------------------
    # 2. Finish-photo filter simulation per pid (first surviving detection)
    # ------------------------------------------------------------------
    print()
    print("=" * 72)
    print("FINISH PHOTO — FILTER SIMULATION (per protocol row)")
    print("=" * 72)
    print(f"  {'#':>3}  {'PID':>6}  {'Time':>7}  {'Note':28}  {'Status':40}")
    print("  " + "-" * 90)

    pids_fully_removed = set()
    pids_first_det_skipped = {}   # pid → first surviving detection info

    for pos, row in enumerate(protocol, 1):
        pid  = row["pid"]
        note = notes.get(pos, "")
        ents = sorted(by_pid_finish.get(pid, []), key=lambda x: x["fn"])
        if not ents:
            print(f"  {pos:>3}  {pid:>6}  {'?':>7}  {note:28}  NO MANIFEST ENTRIES")
            continue

        first_pass = None
        first_fail_reason = None
        for e in ents:
            photo = by_photo[e["fn"]]
            if not size_filter_would_keep(e, photo):
                reason = "size_filter"
            elif is_edge_filtered(e, photo):
                ar = e['w']/e['h']
                tag = "profile_peripheral" if ar < PERIPHERAL_PROFILE_ASPECT else "frontal_edge"
                reason = f"{tag} ar={ar:.2f} cx={e['cx']:.3f}"
            else:
                reason = None

            if reason is None:
                first_pass = e
                break
            elif first_fail_reason is None:
                first_fail_reason = reason

        t = race_time(row["ft_ms"], start_ms)
        if first_pass is None:
            pids_fully_removed.add(pid)
            status = f"REMOVED (all {len(ents)} det filtered: {first_fail_reason})"
        elif first_pass != ents[0]:
            pids_first_det_skipped[pid] = first_pass
            skipped = ents.index(first_pass)
            status = f"ok (first {skipped} det(s) filtered, survives at {first_pass['fn']})"
        else:
            status = "ok"

        flag = ""
        if note and any(k in note.lower() for k in ("dupe", "trash", "edge", "notface")):
            flag = "⚠ "
        print(f"  {pos:>3}  {pid:>6}  {t:>7}  {flag}{note:27}  {status}")

    removed_count = len(pids_fully_removed)
    print(f"\n  Rows fully removed by filters: {removed_count}")
    print(f"  Estimated rows after filters:  {len(protocol) - removed_count}")

    # ------------------------------------------------------------------
    # 3. Duplicate pair series-merge prediction
    # ------------------------------------------------------------------
    print()
    print("=" * 72)
    print("DUPLICATE PAIR — SERIES-MERGE GEOMETRY CHECK")
    print("  (actual merge requires cosine ≥ threshold; no embedding data here)")
    print("=" * 72)

    # Find all pairs where user notes one as dupe#N (if notes provided)
    dupe_pairs = []
    if notes:
        import re
        for pos_b, note in notes.items():
            m = re.search(r"dupe#(\d+)", note, re.IGNORECASE)
            if m:
                pos_a = int(m.group(1))
                if pos_a in pos_to_pid and pos_b in pos_to_pid:
                    dupe_pairs.append((pos_a, pos_b))

    if not dupe_pairs:
        # Show all pairs sharing protocol finish time ± 2 s as candidates
        ts_list = [(r["ft_ms"], r["pid"], i+1) for i, r in enumerate(protocol)]
        for i in range(len(ts_list)):
            for j in range(i + 1, len(ts_list)):
                if abs(ts_list[i][0] - ts_list[j][0]) < 2000:
                    dupe_pairs.append((ts_list[i][2], ts_list[j][2]))

    print(f"  {'pair':>10}  {'gap_ms':>8}  {'same_photo':>12}  {'merge_possible':>16}  reason")
    print("  " + "-" * 90)

    for pos_a, pos_b in sorted(set(dupe_pairs)):
        pid_a = pos_to_pid.get(pos_a)
        pid_b = pos_to_pid.get(pos_b)
        if pid_a is None or pid_b is None:
            continue

        ents_a = sorted(by_pid_finish.get(pid_a, []), key=lambda x: photo_ts(x["fn"]))
        ents_b = sorted(by_pid_finish.get(pid_b, []), key=lambda x: photo_ts(x["fn"]))
        if not ents_a or not ents_b:
            continue

        first_b = ents_b[0]
        b_ts    = photo_ts(first_b["fn"])

        # Most-recent detection of pid_a at or before first_b
        candidates_a = [e for e in ents_a if photo_ts(e["fn"]) <= b_ts]
        if not candidates_a:
            print(f"  pos{pos_a}/{pos_b:>3}  {'no prior det of A':>48}")
            continue
        ref_a = max(candidates_a, key=lambda x: photo_ts(x["fn"]))
        gap   = b_ts - photo_ts(ref_a["fn"])
        same  = ref_a["fn"] == first_b["fn"]

        note_b = notes.get(pos_b, "")
        if same:
            reason = "SAME PHOTO — blocked by participantIdsUsedThisPhoto"
            possible = "NO"
        else:
            possible, reason = series_could_merge(ref_a, first_b)
            possible = "YES (geom ok)" if possible else "NO"

        print(f"  pos{pos_a}/{pos_b:<3}  {gap:>8}ms  {str(same):>12}  {possible:>16}  {reason}")

    # ------------------------------------------------------------------
    # 4. Summary
    # ------------------------------------------------------------------
    print()
    print("=" * 72)
    print("SUMMARY")
    print("=" * 72)
    original_count = len(protocol)
    filter_saves   = removed_count
    ok_pids  = {pos_to_pid[p] for p, n in notes.items()
                if p in pos_to_pid and "ok" in n.lower()
                and "dupe" not in n.lower() and "trash" not in n.lower()
                and "edge" not in n.lower() and "notface" not in n.lower()}
    ok_removed = len([p for p in pids_fully_removed if p in ok_pids])

    print(f"  Original protocol rows :  {original_count}")
    print(f"  Removed by filters     : -{filter_saves}")
    if ok_removed:
        print(f"  ⚠  of which OK rows hit:  {ok_removed}  ← FALSE POSITIVES, check threshold")
    print(f"  Estimated after filters:  {original_count - filter_saves}")
    if notes:
        bad_positions = [p for p, n in notes.items()
                         if p in pos_to_pid and any(k in n.lower() for k in ("dupe","trash","edge","notface"))]
        bad_pids      = {pos_to_pid[p] for p in bad_positions}
        bad_removed   = len([p for p in pids_fully_removed if p in bad_pids])
        bad_remaining = len(bad_pids) - bad_removed
        print(f"  Bad rows in notes      :  {len(bad_pids)}")
        print(f"  Bad rows filtered      : -{bad_removed}")
        print(f"  Bad rows still present :  {bad_remaining}  ← need merge/other fixes")
    print()
    print("  Filter constants (Kotlin ↔ this script must match):")
    print(f"    PERIPHERAL_PROFILE_ASPECT  = {PERIPHERAL_PROFILE_ASPECT}")
    print(f"    PERIPHERAL_X_HALF          = {PERIPHERAL_X_HALF}")
    print(f"    PERIPHERAL_CENTER_MARGIN   = {PERIPHERAL_CENTER_MARGIN}")
    print(f"    FRONTAL_EDGE_X             = {FRONTAL_EDGE_X}")
    print(f"    FRONTAL_EDGE_MIN_AREA_PX2  = {FRONTAL_EDGE_MIN_AREA_PX2}")
    print(f"    START_PERIPHERAL_HARD_X    = {START_PERIPHERAL_HARD_X}")
    print(f"    SERIES_TIGHT_WINDOW_MS     = {SERIES_TIGHT_WINDOW_MS}")
    print(f"    SERIES_TIGHT_COSINE        = {SERIES_TIGHT_COSINE}")
    print(f"    SERIES_NORMAL_COSINE       = {SERIES_NORMAL_COSINE}")
    print(f"    SERIES_MAX_CENTER_DELTA    = {SERIES_MAX_CENTER_DELTA}")
    print(f"    SERIES_MAX_SIZE_RATIO      = {SERIES_MAX_SIZE_RATIO}")


if __name__ == "__main__":
    main()
