"""Bounded, read-only HTTP load with optional Windows EMI counters."""

import argparse
import base64
from concurrent.futures import ThreadPoolExecutor
from contextlib import ExitStack, contextmanager
import ctypes as ct
import hashlib
import http.client
import importlib.util
import json
import math
from pathlib import Path
import re
import secrets
from statistics import median
import subprocess
from time import monotonic, sleep


spec = importlib.util.spec_from_file_location("windows_emi", Path(__file__).with_name("windows-emi.py"))
emi = importlib.util.module_from_spec(spec)
spec.loader.exec_module(emi)

PACED_SECONDS = 6
REQUEST_INTERVAL = 1 / 3
REQUESTS_PER_ACTOR = 18
MIB = 2**20


def command(args, **options):
    return subprocess.check_output(args, text=True, encoding="utf-8", errors="replace",
                                   timeout=35, creationflags=subprocess.CREATE_NO_WINDOW, **options).strip()


@contextmanager
def cluster(args, log):
    pgctl = args.pg / "pg_ctl.exe"
    data = args.fixture / "pgdata"
    flags = (f"-h 127.0.0.1 -p {args.db_port} -c max_connections=40 -c shared_buffers=32MB "
             "-c log_min_duration_statement=0 -c log_parameter_max_length=0")
    with (args.output / "postgres-start.log").open("w") as startup:
        subprocess.run([pgctl, "-D", data, "-l", log, "-o", flags, "-w", "start"], stdout=startup,
                       stderr=subprocess.STDOUT, check=True, timeout=45, creationflags=subprocess.CREATE_NO_WINDOW)
    try:
        yield
    finally:
        command([pgctl, "-D", data, "-m", "fast", "-w", "stop"])


def sql(args, query):
    return command([args.pg / "psql.exe", "-h", "127.0.0.1", "-p", str(args.db_port), "-U", "t12_runtime_local",
                    "-d", "chemicallab_t12_runtime_test", "-XqtA", "-v", "ON_ERROR_STOP=1", "-c", query])


def prepare(args):
    assert sql(args, "select version from board_runtime.contract") == "1"
    institution, clazz, board = json.loads(sql(args, """
        select json_build_array(s.institution_id,s.class_id,s.board_id)
        from board_runtime.boards s join whiteboard_sessions b on b.id=s.board_id
        where b.status='CLOSED' and
            (select count(*) from board_runtime.current_admissions a where a.board_id=s.board_id)=20
        """))
    assert all(isinstance(number, int) and number > 0 for number in (institution, clazz, board))
    count = sql(args, "select count(*) from user_accounts where username like 'green-load-teacher-%'")
    if count == "0":
        sql(args, f"""
            insert into user_accounts(username,password,role,active,temporary_password,credentials_version,created_at)
            select 'green-load-teacher-'||n,u.password,'DOCENTE',true,false,0,statement_timestamp()
            from generate_series(1,3) n cross join user_accounts u where u.username='runtime-teacher-a';
            insert into teacher_profiles(user_id,names,last_names)
            select id,'Docente ficticio',right(username,1) from user_accounts where username like 'green-load-teacher-%';
            insert into evaluation_runtime.memberships(institution_id,account_id,valid_from)
            select {institution},id,statement_timestamp()-interval '1 minute'
            from user_accounts where username like 'green-load-teacher-%';
            insert into evaluation_runtime.roles(membership_id,institution_id,role,valid_from)
            select m.id,m.institution_id,'DOCENTE',statement_timestamp()-interval '1 minute'
            from evaluation_runtime.memberships m join user_accounts u on u.id=m.account_id
            where u.username like 'green-load-teacher-%';
            insert into evaluation_runtime.teaching(institution_id,class_id,membership_id,faculty,valid_from,valid_until)
            select m.institution_id,{clazz},m.id,'CO_DOCENTE',statement_timestamp()-interval '1 minute',
              statement_timestamp()+interval '1 day'
            from evaluation_runtime.memberships m join user_accounts u on u.id=m.account_id
            where u.username like 'green-load-teacher-%';
            """)
    else:
        assert count == "3", "Unexpected disposable accounts"
    return f"/api/institutions/{institution}/classes/{clazz}/whiteboards/v1/teacher/{board}"


def request(port, path, token=None, login=None):
    conn = http.client.HTTPConnection("127.0.0.1", port, timeout=2)
    headers = {"Connection": "close"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if login:
        headers["Content-Type"] = "application/json"
    start = monotonic()
    try:
        conn.request("POST" if login else "GET", path, json.dumps(login) if login else None, headers)
        response = conn.getresponse()
        return response.status, response.read(), 1000 * (monotonic() - start)
    finally:
        conn.close()


def resources(args, pid):
    raw = sql(args, "select pid from pg_stat_activity where application_name='PostgreSQL JDBC Driver'")
    postgres = [int(value) for value in raw.split()]
    assert len(postgres) == args.pool
    ids = ",".join(str(value) for value in [pid, *postgres])
    readings = json.loads(command(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", f"""
        ConvertTo-Json -Compress -InputObject @(Get-Process -Id {ids} | ForEach-Object {{
            @{{ id=$_.Id; cpuMs=$_.TotalProcessorTime.TotalMilliseconds; rssBytes=$_.WorkingSet64 }}
        }})
        """]))
    assert {item["id"] for item in readings} == {pid, *postgres}
    return {item["id"]: item for item in readings}


def window(call, meter):
    before = meter.snapshot() if meter else {}
    start = monotonic()
    call(start)
    elapsed = monotonic() - start
    after = meter.snapshot() if meter else {}
    for name, current in after.items():
        assert current["energyPWh"] >= before[name]["energyPWh"]
        assert current["driverTime100ns"] > before[name]["driverTime100ns"]
    if after:
        assert after["RAPL_Package0_PKG"]["energyPWh"] > before["RAPL_Package0_PKG"]["energyPWh"]
    return {"seconds": elapsed, "before": before, "after": after}


def exercise(args, path, tokens, expected, app, workers, meter, log):
    readings = []

    def read(token):
        start = monotonic()
        try:
            return request(args.http_port, path, token)
        except (OSError, http.client.HTTPException):
            return 0, b"", 1000 * (monotonic() - start)

    def load(start):
        with ThreadPoolExecutor(max_workers=workers) as threads:
            for step in range(REQUESTS_PER_ACTOR):
                sleep(max(0, start + step * REQUEST_INTERVAL - monotonic()))
                for response in threads.map(read, tokens[:workers]):
                    readings.append(response)
                # Stop the staircase if a response fails or crosses the latency budget.
                if any(status != 200 or latency > 750 for status, _, latency in readings[-workers:]):
                    return
        sleep(max(0, start + PACED_SECONDS - monotonic()))

    idle_before = resources(args, app.pid)
    idle = window(lambda _: sleep(PACED_SECONDS), meter)
    cpu_before = resources(args, app.pid)
    log_start = log.stat().st_size
    work = window(load, meter)
    with log.open("rb") as stream:
        stream.seek(log_start)
        sql_count = len(re.findall(rb"duration: [\d.]+ ms\s+(?:execute [^:]*|statement): ", stream.read()))
    cpu_after = resources(args, app.pid)
    errors = sum(status != 200 for status, _, _ in readings)
    failures = sum(body != expected for status, body, _ in readings if status == 200)
    latencies = sorted(latency for _, _, latency in readings)
    p95 = latencies[math.ceil(len(latencies) * .95) - 1]
    report = {
        "actors": workers, "requested": REQUESTS_PER_ACTOR * workers, "completed": len(readings),
        "httpErrors": errors, "bodyMismatches": failures, "httpBytes": sum(len(body) for _, body, _ in readings),
        "statuses": [status for status, _, _ in readings],
        "latencyMs": latencies, "medianMs": median(latencies), "p95Ms": p95, "sqlExecutions": sql_count,
        "idle": idle, "work": work,
        "javaCpuMs": cpu_after[app.pid]["cpuMs"] - cpu_before[app.pid]["cpuMs"],
        "postgresCpuMs": sum(cpu_after[pid]["cpuMs"] - cpu_before[pid]["cpuMs"]
                             for pid in cpu_after if pid != app.pid),
        "javaIdleCpuMs": cpu_before[app.pid]["cpuMs"] - idle_before[app.pid]["cpuMs"],
        "javaRssBytes": cpu_after[app.pid]["rssBytes"],
        "postgresClientRssBytes": sum(values["rssBytes"] for pid, values in cpu_after.items() if pid != app.pid),
    }
    report["stop"] = (errors > 0 or failures > 0 or p95 > 350 or max(latencies) > 750
                      or report["javaRssBytes"] > 750 * MIB or len(readings) != report["requested"])
    return report


def run_stage(args, stage, jar, heap, actors, path, ready, secret, meter, log):
    java = args.java / "bin" / "java.exe"
    flags = ["-Xms128m", f"-Xmx{heap}m", "-XX:+UseG1GC", "-XX:ActiveProcessorCount=2"]
    spring = ["--spring.profiles.active=dev", "--server.address=127.0.0.1", f"--server.port={args.http_port}",
              f"--spring.datasource.url=jdbc:postgresql://127.0.0.1:{args.db_port}/chemicallab_t12_runtime_test",
              "--spring.datasource.username=t12_runtime_local", "--spring.datasource.password=fictitious",
              f"--spring.datasource.hikari.minimum-idle={args.pool}", f"--spring.datasource.hikari.maximum-pool-size={args.pool}",
              "--spring.jpa.hibernate.ddl-auto=validate", "--app.demo.enabled=false",
              "--app.bootstrap.admin.enabled=false", "--app.agreements.enabled=false", "--app.subscriptions.mode=DISABLED",
              f"--app.jwt.secret={secret}"]
    java_log = args.output / f"{stage}.java.log"
    with jar.open("rb") as binary:
        jar_sha256 = hashlib.file_digest(binary, "sha256").hexdigest()
    result = {"stage": stage, "jvm": flags, "pool": args.pool, "jarSha256": jar_sha256, "ranges": []}
    with java_log.open("w", encoding="utf-8") as handle:
        app = subprocess.Popen([java, *flags, "-jar", jar, *spring], stdout=handle, stderr=subprocess.STDOUT,
                               creationflags=subprocess.CREATE_NO_WINDOW)
        try:
            deadline = monotonic() + 120
            while "Started ChemicalLabBackendApplication" not in java_log.read_bytes().decode("utf-8", "replace"):
                if app.poll() is not None or monotonic() >= deadline:
                    raise RuntimeError(f"Startup failed: {stage}")
                sleep(.5)
            tokens = []
            for name in [ready["teacherA"], *(f"green-load-teacher-{i}" for i in range(1, 4))]:
                status, response, _ = request(args.http_port, "/api/auth/login",
                                              login={"usernameOrEmail": name, "password": ready["password"]})
                assert status == 200
                tokens.append(json.loads(response)["token"])
            for i in range(12):
                for token in tokens:
                    status, body, _ = request(args.http_port, path, token)
                    assert status == 200
                    if i == 0 and token == tokens[0]:
                        expected = body
                        data = json.loads(body)
                        assert data["session"]["participantCount"] == len(data["participants"]) == 20
                        assert data["session"]["snapshotAvailable"]
                    assert body == expected, "Fixture response changed"
                sleep(.25)
            result["bodySha256"] = hashlib.sha256(expected).hexdigest()
            for workers in actors:
                row = exercise(args, path, tokens, expected, app, workers, meter, log)
                result["ranges"].append(row)
                (args.output / f"{stage}.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
                print(stage, workers, "actors,", row["completed"], "requests", flush=True)
                if row["stop"]:
                    raise RuntimeError(f"Stop criterion reached: {stage}, actors={workers}")
        finally:
            app.terminate()
            app.wait(timeout=20)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("fixture", "reference", "change", "java", "pg", "output"):
        parser.add_argument(name, type=Path)
    parser.add_argument("--db-port", type=int, default=55529)
    parser.add_argument("--http-port", type=int, default=18529)
    parser.add_argument("--pool", type=int, choices=[4, 8], default=8)
    parser.add_argument("--stages", nargs="+", choices=["A1", "B1", "A2", "B2", "B3", "C"],
                        default=["A1", "B1", "A2", "B2", "C"])
    parser.add_argument("--energy", action="store_true")
    args = parser.parse_args()
    for name in ("fixture", "reference", "change", "java", "pg", "output"):
        setattr(args, name, getattr(args, name).resolve())
    ready = json.loads((args.fixture / "browser" / "ready.json").read_text())
    assert ready["mode"] == "runtime" and ready["origin"] == f"http://127.0.0.1:{args.http_port}"
    assert (args.fixture / "prepared").is_file() and not (args.fixture / "pgdata" / "postmaster.pid").exists()
    args.output.mkdir()
    log = args.output / "postgres.log"
    with ExitStack() as stack:
        meter = None
        if args.energy:
            paths = emi.interface_paths()
            if len(paths) != 1:
                raise RuntimeError("Expected one enabled EMI interface")
            meter = emi.Meter(paths[0])
            stack.callback(meter.close)
        stack.enter_context(cluster(args, log))
        path = prepare(args)
        secret = base64.b64encode(secrets.token_bytes(32)).decode("ascii")
        plans = {
            "A1": (args.reference, 512, [4]), "B1": (args.change, 512, [4, 1, 2]),
            "A2": (args.reference, 512, [4]), "B2": (args.change, 512, [4, 2, 1]),
            "B3": (args.change, 512, [2, 1]), "C": (args.change, 256, [1, 2, 4]),
        }
        for stage in args.stages:
            jar, heap, actors = plans[stage]
            run_stage(args, stage, jar, heap, actors, path, ready, secret, meter, log)


if __name__ == "__main__":
    main()
