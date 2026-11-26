"""Compare the original certified run with the native local run."""
import argparse
import json
import re
from collections import defaultdict
from pathlib import Path
from statistics import median

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument("historical",type=Path)
parser.add_argument("native",type=Path)
args=parser.parse_args()


def read(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def grouped(run):
    rows=read(run/"browser/browser-metrics.json")["requests"]
    groups=defaultdict(list)
    for row in rows:
        if row["path"].startswith("/api/institutions/") and 200<=row.get("status",0)<300 and "durationMs" in row:
            shape=re.sub(r"(?<=/)\d+(?=/|$)","{id}",row["path"])
            groups[(row["method"],shape)].append(row)
    return groups


def totals(rows):
    return {"requests":len(rows),"received_bytes":sum(r["receivedBytes"] for r in rows),
            "request_body_bytes":sum(r["requestBodyBytes"] for r in rows),
            "median_ms":median(r["durationMs"] for r in rows),
            "max_ms":max(r["durationMs"] for r in rows)}


old=read(args.historical/"baseline.json")
new=read(args.native/"baseline.json")
old_groups,new_groups=grouped(args.historical),grouped(args.native)
matched_old,matched_new=[],[]
for key in sorted(old_groups.keys() & new_groups.keys()):
    size=min(len(old_groups[key]),len(new_groups[key]))
    matched_old.extend(old_groups[key][:size])
    matched_new.extend(new_groups[key][:size])
old_f08=next(row for row in old["phases"] if row["to"]=="conciliación aplicada")
print(json.dumps({
    "protocol": {
        "http_matching": "Earliest completed 2xx requests at the same method and normalized path, up to the common count.",
        "sql_window": "From browser start to exposed result; excludes DDL/seeding and focused follow-up checks.",
        "fixture_f08_interval": "Entire interval between pending-delivery assertions and applied marker, including any concurrent background work.",
        "native_f08": "No F08 commands: native origin has typed context from creation.",
    },
    "historical": {
        "institutional_http":old["institutional_http"], "matched_http":totals(matched_old),
        "postgres_all_in_window":old["postgres"],
        "postgres_f08_interval":old_f08,
        "postgres_statements_outside_f08_interval":old["postgres"]["statements"]-old_f08["postgres_statements"],
        "postgres_ms_outside_f08_interval":round(old["postgres"]["reported_execution_ms"]-old_f08["postgres_reported_execution_ms"],3),
        "local_process_metrics":old["local_process_metrics"],
    },
    "native":{
        "institutional_http":new["institutional_http"],"matched_http":totals(matched_new),
        "postgres_all_in_window":new["postgres"],
        "postgres_f08_interval":None,
        "postgres_statements_outside_f08_interval":new["postgres"]["statements"],
        "postgres_ms_outside_f08_interval":new["postgres"]["reported_execution_ms"],
        "local_process_metrics":new["local_process_metrics"],
    },
},indent=2))
