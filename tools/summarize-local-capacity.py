"""Summarize bounded capacity runs and convert native EMI pWh deltas to joules."""

import json
from pathlib import Path
from sys import argv


def delta(row, kind, channel):
    before = row[kind]["before"][channel]
    after = row[kind]["after"][channel]
    return ((after["energyPWh"] - before["energyPWh"]) * 3.6e-9,
            (after["driverTime100ns"] - before["driverTime100ns"]) / 1e7)


def electricity(row):
    domains = {}
    for channel in row["work"]["before"]:
        idle_j, idle_s = delta(row, "idle", channel)
        load_j, load_s = delta(row, "work", channel)
        adjusted_j = load_j - idle_j * load_s / idle_s
        domains[channel] = {
            "idleJ": round(idle_j, 4), "loadJ": round(load_j, 4),
            "idleSeconds": round(idle_s, 5), "loadSeconds": round(load_s, 5),
            "grossJPerResponse": round(load_j / row["completed"], 5),
            "idleAdjustedJPerResponse": round(adjusted_j / row["completed"], 5),
        }
    return domains


source = Path(argv[1])
destination = Path(argv[2])
stage_names = ["A1", "B1", "A2", "B2"]
if (source / "B3.json").is_file():
    stage_names.append("B3")
stage_names.append("C")
stages = [json.loads((source / f"{name}.json").read_text(encoding="utf-8")) for name in stage_names]
assert len({stage["bodySha256"] for stage in stages}) == 1
results = []
for stage in stages:
    for row in stage["ranges"]:
        results.append({
            "stage": stage["stage"], "jarSha256": stage["jarSha256"], "jvm": stage["jvm"], "pool": stage["pool"],
            **{key: row[key] for key in (
                "actors", "requested", "completed", "httpBytes", "httpErrors", "bodyMismatches", "medianMs",
                "p95Ms", "sqlExecutions", "javaCpuMs", "postgresCpuMs", "javaRssBytes", "postgresClientRssBytes", "stop",
            )},
            "energyDomains": {} if row["stop"] else electricity(row),
        })
destination.write_text(json.dumps({"bodySha256": stages[0]["bodySha256"], "results": results}, indent=2) + "\n",
                       encoding="utf-8")
