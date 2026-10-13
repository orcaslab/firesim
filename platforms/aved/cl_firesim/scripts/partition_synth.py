#!/usr/bin/env python3
"""Synthesize chosen modules of the generated design out of context, under a
memory budget, for the kernel's synthesis to link in as finished netlists.

usage: partition_synth.py --src <sv> --out <dir> --part <part> --budget <GB>
                          [--period <ns>] [--jobs N] [--threads N] [--vivado <path>]
                          [--directive <synth directive>] <module>...
       partition_synth.py --src <sv> --mark [--replace <sv>] <module>...
                          (source, partitions black-boxed, to stdout: the
                          kernel's copy; the modules --replace defines in
                          place of the source's)

Modules may be given as glob patterns ('*_threaded_*': every FAME-5 model);
each must match at least one module, and a match inside another partition is
part of that one.

One synth_design over the whole simulator holds every model in a single
working set; on the threaded FAME-5 images that peaks past 100 GB, more than
the build machine has. Synthesizing each model on its own bounds the peak by
the largest model instead, and the kernel's synthesis sees the models as black
boxes (fill_partitions.tcl puts the netlists back).

The budget is enforced, not estimated: every job runs in its own cgroup scope
inside one slice capped at --budget (memory, no swap), so the machine keeps
what is left whatever Vivado does. Within it, models are started while their
expected peaks fit together: the peak measured (by the job's cgroup) on the
previous build of the same model when there is one, otherwise an estimate from
its size. A model the OOM killer stops while others share the budget is rerun
alone; one that does not fit alone is rerun single-threaded (Vivado's threads
are processes, each with a copy of the working set), and one that still does
not fit fails the build, naming the model.

A model is resynthesized only when its RTL (with everything it instantiates),
the synthesis script or the options change.
"""
import argparse
import fnmatch
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
SYNTH_TCL = os.path.join(HERE, "synth_partition.tcl")
GB = 1 << 30
# Size estimate when no previous build of a model was measured: Vivado's base
# footprint plus a rate per byte of the model's RTL. Fitted on V80 FAME-5
# models (RuntimeOptimized, 2025.2; cgroup peaks: 0.1-3.5 MB models 8 GB, a
# 32 MB socket 50 GB); an underestimate only costs a rerun, an overestimate
# only serializes.
BASE_GB = 8.0
GB_PER_MB = 1.3
POLL_S = 5


def parse_modules(src):
    """module name -> (text, set of instantiated module names)."""
    text = open(src).read()
    mods = {}
    for m in re.finditer(r"^module\s+(\w+)(.*?)^endmodule", text, re.M | re.S):
        mods[m.group(1)] = m.group(0)
    names = set(mods)
    inst = re.compile(r"^\s+(\w+)\s+(?:#\s*\(.*?\)\s*)?\w+\s*\(", re.M | re.S)
    return {n: (t, {c for c in inst.findall(t) if c in names and c != n}) for n, t in mods.items()}


def closure(mods, top):
    seen, todo = set(), [top]
    while todo:
        n = todo.pop()
        if n not in seen:
            seen.add(n)
            todo.extend(mods[n][1])
    return sorted(seen)


def resolve(mods, patterns):
    out = []
    for p in patterns:
        hit = sorted(n for n in mods if fnmatch.fnmatchcase(n, p))
        if not hit:
            sys.exit(f"partition_synth: no module matches {p}")
        out += [n for n in hit if n not in out]
    # A match inside another partition (a model's own threaded RAMs) is
    # synthesized with it.
    inner = {c for n in out for c in closure(mods, n) if c != n}
    return [n for n in out if n not in inner]


def mark(src, modules, replace=None):
    want = set(modules)
    new = {n: t for n, (t, _) in parse_modules(replace).items()} if replace else {}
    skip = False
    with open(src) as f:
        for line in f:
            m = re.match(r"module\s+(\w+)", line)
            if m and m.group(1) in new:
                # The replacement, in the original's place.
                text = new.pop(m.group(1))
                if m.group(1) in want:
                    text = "(* black_box *) " + text
                sys.stdout.write(text + "\n")
                skip = True
                continue
            if skip:
                skip = not re.match(r"endmodule\b", line)
                continue
            if m and m.group(1) in want:
                line = "(* black_box *) " + line
            sys.stdout.write(line)
    for text in new.values():
        sys.stdout.write(text + "\n")


def key_of(mods, top, opts):
    h = hashlib.sha256()
    h.update(open(SYNTH_TCL, "rb").read())
    h.update(json.dumps(opts, sort_keys=True).encode())
    for n in closure(mods, top):
        h.update(mods[n][0].encode())
    return h.hexdigest()


class Cgroups:
    """Per-job scopes in a slice capped at the budget (systemd user manager)."""
    slice_name = None

    def __init__(self, out, budget):
        self.slice = None
        if not shutil.which("systemd-run") or os.environ.get("PARTITION_SYNTH_UNCAPPED"):
            print("partition_synth: WARNING: no systemd-run, the budget only schedules"
                  " and is not enforced", flush=True)
            return
        # One slice per output directory: a rebuild reuses it.
        name = "partsynth_" + hashlib.sha256(os.path.abspath(out).encode()).hexdigest()[:8] + ".slice"
        subprocess.run(["systemctl", "--user", "set-property", "--runtime", name,
                        f"MemoryMax={int(budget * GB)}", "MemorySwapMax=0"], check=True)
        self.slice = Cgroups.slice_name = name

    def wrap(self, cmd):
        if not self.slice:
            return cmd
        # OOMPolicy=kill (memory.oom.group): an OOM kill takes the whole job.
        # Vivado's threads are child processes, and a survivor of a killed job
        # would hold its memory against the job's rerun.
        return ["systemd-run", "--user", "--scope", "-q", f"--slice={self.slice}",
                "-p", "OOMPolicy=kill", "-p", "ManagedOOMPreference=avoid"] + cmd

    def oom_kills(self):
        """OOM kills in the slice so far: unlike a job's scope, it outlives the job."""
        if not self.slice:
            return 0
        uid = os.getuid()
        try:
            path = f"/sys/fs/cgroup/user.slice/user-{uid}.slice/user@{uid}.service/{self.slice}"
            ev = dict(l.split() for l in open(path + "/memory.events"))
            return int(ev.get("oom_kill", 0))
        except (OSError, ValueError):
            return 0

    @staticmethod
    def sample(job):
        """Track the job's cgroup peak and OOM kills while it runs."""
        try:
            if "cg" not in job:
                cg = open(f"/proc/{job['pid']}/cgroup").read().split("::")[-1].strip()
                # Until systemd-run has moved itself into its scope, the
                # process is still in the caller's cgroup.
                if f"/{Cgroups.slice_name}/" not in cg:
                    return
                job["cg"] = "/sys/fs/cgroup" + cg
            peak = int(open(job["cg"] + "/memory.peak").read()) / GB
            job["peak"] = max(job.get("peak", 0.0), peak)
            ev = dict(l.split() for l in open(job["cg"] + "/memory.events"))
            job["oom"] = job.get("oom", 0) or int(ev.get("oom_kill", 0))
        except (OSError, ValueError):
            pass

    @staticmethod
    def reap(job):
        """Kill whatever the job left behind in its cgroup."""
        try:
            with open(job["cg"] + "/cgroup.kill", "w") as f:
                f.write("1")
        except (KeyError, OSError):
            pass


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", required=True)
    ap.add_argument("--mark", action="store_true")
    ap.add_argument("--replace", help="with --mark: modules to use in place of the source's")
    ap.add_argument("--out")
    ap.add_argument("--part")
    ap.add_argument("--budget", type=float, help="GB")
    ap.add_argument("--period", type=float, default=0.0, help="hostClock period (ns)")
    ap.add_argument("--jobs", type=int, default=4)
    ap.add_argument("--threads", type=int, default=2, help="Vivado threads per job")
    ap.add_argument("--vivado", default="vivado")
    ap.add_argument("--directive", default="Default")
    ap.add_argument("modules", nargs="+")
    a = ap.parse_args()
    mods = parse_modules(a.src)
    a.modules = resolve(mods, a.modules)
    if a.mark:
        mark(a.src, a.modules, a.replace)
        return
    if not (a.out and a.part and a.budget):
        ap.error("--out, --part and --budget are required")

    print(f"partition_synth: {len(a.modules)} partitions, budget {a.budget:g} GB", flush=True)
    opts = {"part": a.part, "period": a.period, "directive": a.directive}
    os.makedirs(a.out, exist_ok=True)
    cg = Cgroups(a.out, a.budget)

    todo = []
    for m in a.modules:
        d = os.path.join(a.out, m)
        os.makedirs(d, exist_ok=True)
        k = key_of(mods, m, opts)
        meta = os.path.join(d, "meta.json")
        old = json.load(open(meta)) if os.path.exists(meta) else {}
        if old.get("key") == k and os.path.exists(os.path.join(d, m + ".dcp")):
            print(f"partition_synth: {m}: up to date ({old.get('peak_gb')} GB)", flush=True)
            continue
        size_mb = sum(len(mods[n][0]) for n in closure(mods, m)) / 1e6
        est = old.get("peak_gb") or BASE_GB + GB_PER_MB * size_mb
        todo.append({"m": m, "dir": d, "key": k, "est": min(est * 1.1, a.budget),
                     "size_mb": round(size_mb, 1), "alone": False,
                     # A model that last fitted only single-threaded starts so.
                     "threads": min(a.threads, old.get("threads", a.threads))})

    # Largest first: it bounds the step's duration, the rest fill around it.
    todo.sort(key=lambda j: -j["est"])
    running = {}  # pid -> job
    failed = []
    while todo or running:
        used = sum(j["est"] for j in running.values())
        for j in list(todo):
            if len(running) >= a.jobs:
                break
            if running and (j["alone"] or used + j["est"] > a.budget):
                continue
            todo.remove(j)
            for k in ("cg", "peak", "oom"):
                j.pop(k, None)
            log = open(os.path.join(j["dir"], "vivado.out"), "w")
            p = subprocess.Popen(cg.wrap(
                [a.vivado, "-mode", "batch", "-nojournal", "-log", os.path.join(j["dir"], "vivado.log"),
                 "-source", SYNTH_TCL, "-tclargs", a.src, j["m"], j["dir"], a.part,
                 str(a.period), a.directive, str(j["threads"])]),
                stdout=log, stderr=subprocess.STDOUT)
            j["t0"], j["proc"], j["pid"], j["shared"] = time.time(), p, p.pid, bool(running)
            j["kills0"] = cg.oom_kills()
            running[p.pid] = j
            used += j["est"]
            print(f"partition_synth: {j['m']}: started (expects {j['est']:.1f} GB,"
                  f" {j['size_mb']} MB of RTL, {j['threads']} threads{', alone' if j['alone'] else ''})",
                  flush=True)
        if not running:
            break

        # Polled through each job's Popen, which reaps it: subprocess reaps
        # finished children it still tracks whenever a new one starts, so a
        # wait for any child can find none left.
        pid = next((pid for pid, r in running.items() if r["proc"].poll() is not None), None)
        if pid is None:
            for r in running.values():
                cg.sample(r)
            time.sleep(POLL_S)
            continue
        j = running.pop(pid)
        cg.reap(j)
        mins = (time.time() - j["t0"]) / 60
        peak = j.get("peak", 0.0)
        rc = j["proc"].returncode
        # Killed by the OOM killer: the job's own event when sampled in time;
        # otherwise a job that died by a signal while the slice recorded a kill
        # (OOMPolicy=kill ends the rest of the job with SIGTERM: 143).
        oom = j.get("oom") or (rc in (137, 143, -9, -15) and cg.oom_kills() > j["kills0"])
        if oom:
            if j["shared"] or running:
                print(f"partition_synth: {j['m']}: out of memory at {peak:.1f} GB while sharing"
                      " the budget; rerunning it alone", flush=True)
                j.update(est=a.budget, alone=True, shared=False)
                todo.insert(0, j)
            elif j["threads"] > 1:
                print(f"partition_synth: {j['m']}: out of memory at {peak:.1f} GB alone;"
                      " rerunning it single-threaded", flush=True)
                j.update(threads=1)
                todo.insert(0, j)
            else:
                failed.append(f"{j['m']} needs more than the {a.budget:g} GB budget"
                              f" (killed at {peak:.1f} GB alone, single-threaded)")
            continue
        if rc != 0:
            failed.append(f"{j['m']} failed (see {j['dir']}/vivado.log)")
            continue
        json.dump({"key": j["key"], "peak_gb": round(peak, 1), "minutes": round(mins, 1),
                   "threads": j["threads"]},
                  open(os.path.join(j["dir"], "meta.json"), "w"))
        print(f"partition_synth: {j['m']}: done, peak {peak:.1f} GB, {mins:.0f} min", flush=True)

    if failed:
        for f in failed:
            print(f"partition_synth: ERROR: {f}", file=sys.stderr)
        sys.exit(1)

    # The kernel synthesis' post-synthesis hook: one netlist per black box.
    with open(os.path.join(a.out, "fill.tcl"), "w") as f:
        f.write("# Generated by partition_synth.py.\n")
        f.write(f"source {os.path.join(HERE, 'fill_partitions.tcl')}\n")
        f.write("fill_partitions {\n")
        for m in a.modules:
            f.write(f"    {m} {os.path.abspath(os.path.join(a.out, m, m + '.dcp'))}\n")
        f.write(f"}} {os.path.abspath(os.path.join(a.out, 'link'))}\n")


if __name__ == "__main__":
    main()
