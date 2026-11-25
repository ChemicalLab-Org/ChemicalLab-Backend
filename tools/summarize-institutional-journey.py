"""Summarize the local Chromium recording and PostgreSQL top-level statement log."""

import argparse
import json
import re
from datetime import datetime
from pathlib import Path
from statistics import median

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("run", type=Path)
args = parser.parse_args()
browser_file = args.run / "browser" / "browser-metrics.json"
browser = json.loads(browser_file.read_text(encoding="utf-8"))
assert "error" not in browser, browser.get("error")


def row_summary(rows):
    completed = [row for row in rows if "durationMs" in row]
    times = [row["durationMs"] for row in completed]
    return {
        "requests": len(rows),
        "complete": len(completed),
        "request_body_bytes": sum(row["requestBodyBytes"] for row in rows),
        "chromium_received_bytes": sum(row["receivedBytes"] for row in completed),
        "response_ms_median": median(times) if times else None,
        "response_ms_max": max(times) if times else None,
    }


started = datetime.fromisoformat(browser["steps"][0]["timestamp"])
ended = datetime.fromisoformat(browser["steps"][-1]["timestamp"])
statement = re.compile(
    r"^(?P<stamp>\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d+ [+-]\d\d) \[\d+\] LOG:\s+"
    r"duration: (?P<ms>[\d.]+) ms\s+(?:execute [^:]+:|statement:)\s*(?P<sql>\w+)",
    re.IGNORECASE,
)
queries = []
transaction = {"BEGIN", "COMMIT", "ROLLBACK", "SAVEPOINT", "RELEASE"}

with (args.run / "postgres.log").open(encoding="utf-8", errors="replace") as log:
    for line in log:
        match = statement.match(line)
        if match:
            stamp = datetime.fromisoformat(match["stamp"].replace(" ", "T", 1).replace(" ", ""))
            if started <= stamp <= ended:
                queries.append((stamp, float(match["ms"]), match["sql"].upper()))

phases = {}
for first, last in zip(browser["steps"], browser["steps"][1:]):
    start = datetime.fromisoformat(first["timestamp"])
    end = datetime.fromisoformat(last["timestamp"])
    statements = [q for q in queries if start <= q[0] < end]
    phases[last["step"]] = {
        "wall_ms": round((end - start).total_seconds() * 1000),
        "postgres_statements": len(statements),
        "postgres_reported_execution_ms": round(sum(q[1] for q in statements), 3),
    }

rows = browser["requests"]
api = [row for row in rows if row["path"].startswith("/api/")]
result = {
    "browser_total": row_summary(rows),
    "browser_api": row_summary(api),
    "institutional_http": row_summary([row for row in api if row["path"].startswith("/api/institutions/")]),
    "postgres": {
        "statements": len(queries),
        "select": sum(q[2] == "SELECT" for q in queries),
        "transaction_control": sum(q[2] in transaction for q in queries),
        "reported_execution_ms": round(sum(q[1] for q in queries), 3),
    },
    "phases": phases,
    "elapsed_ms_including_manual_approval_and_academic_wait": round((ended - started).total_seconds() * 1000),
}
print(json.dumps(result, indent=2, ensure_ascii=False))
