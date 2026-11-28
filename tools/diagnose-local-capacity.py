"""Instrument bounded HTTP windows on a disposable Windows PostgreSQL fixture."""

import argparse
from base64 import b64encode
from concurrent.futures import ThreadPoolExecutor
from contextlib import ExitStack, contextmanager
from hashlib import file_digest, sha256
import importlib.util
import json
from pathlib import Path
import secrets
import subprocess
from threading import Barrier
from time import monotonic, sleep, time


TOOLS = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("capacity", TOOLS / "measure-local-capacity.py")
capacity = importlib.util.module_from_spec(spec)
spec.loader.exec_module(capacity)


@contextmanager
def background(command, stdout, stderr, cwd=None):
    with stdout.open("w", encoding="utf-8") as out, stderr.open("w", encoding="utf-8") as err:
        with subprocess.Popen(command, stdout=out, stderr=err, cwd=cwd,
                              creationflags=subprocess.CREATE_NO_WINDOW) as child:
            try:
                yield child
            finally:
                if child.poll() is None:
                    child.terminate()


def ready(child, path, marker, seconds):
    deadline = monotonic() + seconds
    while monotonic() < deadline and child.poll() is None:
        if marker in path.read_text(encoding="utf-8", errors="replace"):
            return
        sleep(0.1)
    raise RuntimeError(f"Startup failed: {path}")


def windows(args, path, tokens, expected):
    for actors in args.actors:
        records = []
        planned = actors * args.rounds
        abort = False
        started = monotonic()
        started_ms = int(time() * 1000)
        with ThreadPoolExecutor(max_workers=actors) as threads:
            for step in range(args.rounds):
                sleep(max(0, started + step / 3 - monotonic()))
                gate = Barrier(actors, timeout=3)

                def read(index):
                    gate.wait()
                    began = monotonic()
                    at = int(time() * 1000)
                    try:
                        status, body, elapsed = capacity.request(args.http_port, path, tokens[index])
                        return dict(actor=index, at=at, ms=elapsed, status=status, matches=sha256(body).digest() == expected)
                    except (OSError, capacity.http.client.HTTPException) as error:
                        return dict(actor=index, at=at, ms=(monotonic() - began) * 1000, error=type(error).__name__)

                batch = list(threads.map(read, range(actors)))
                records.extend(batch)
                if any(row.get("status") != 200 or not row.get("matches") or row["ms"] > 750 for row in batch):
                    abort = True
                    break
        if not abort:
            sleep(max(0, started + args.rounds / 3 - monotonic()))
        outcomes = dict(ok=sum(row.get("status") == 200 and row.get("matches", False) for row in records),
                        timedOut=sum(row.get("error") == "TimeoutError" for row in records))
        outcomes["failed"] = len(records) - outcomes["ok"] - outcomes["timedOut"]
        yield dict(actors=actors, planned=planned, sent=len(records), unsent=planned - len(records),
                   elapsedSeconds=monotonic() - started, startEpochMs=started_ms, endEpochMs=int(time() * 1000),
                   stopped=abort, requests=records, **outcomes)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for path in ("fixture", "jar", "jdk", "pg", "output"):
        parser.add_argument(path, type=lambda value: Path(value).resolve())
    parser.add_argument("--db-port", type=int, default=55571)
    parser.add_argument("--http-port", type=int, default=18571)
    parser.add_argument("--pool", type=int, choices=range(1, 11), default=4)
    parser.add_argument("--heap-mib", type=int, choices=(256, 512), default=512)
    parser.add_argument("--actors", type=int, nargs="+", default=[1, 2, 4])
    parser.add_argument("--rounds", type=int, default=18)
    parser.add_argument("--label", required=True)
    parser.add_argument("--journey", type=lambda value: Path(value).resolve())
    parser.add_argument("--students", type=int, default=3)
    args = parser.parse_args()
    if any(count not in range(1, 5) for count in args.actors) or not 1 <= args.rounds <= 180:
        parser.error("Use 1–4 actors and 1–180 rounds for the closed-board read probe.")
    if not (args.fixture / "prepared").is_file() or (args.fixture / "pgdata/postmaster.pid").exists():
        parser.error("Use a stopped, disposable 20-participant fixture marked prepared.")
    args.output.mkdir()
    with args.jar.open("rb") as artifact:
        digest = file_digest(artifact, "sha256").hexdigest()
    harness = {path.name: sha256(path.read_bytes()).hexdigest() for path in
               (Path(__file__), TOOLS / "SampleLocalPool.java", TOOLS / "SampleLocalProcesses.ps1",
                TOOLS / "sample-local-postgres.sql", *(p for p in [args.journey] if p))}
    gc = args.output / "gc.log"
    jvm = ["-Xms128m", f"-Xmx{args.heap_mib}m", "-XX:+UseG1GC", "-XX:ActiveProcessorCount=2",
           f"-Xlog:gc*:file={gc.name}:time,uptime"]
    launch = [args.jdk / "bin/java.exe", *jvm, "-jar", args.jar, "--spring.profiles.active=dev",
              "--server.address=127.0.0.1", f"--server.port={args.http_port}",
              f"--spring.datasource.url=jdbc:postgresql://127.0.0.1:{args.db_port}/chemicallab_t12_runtime_test",
              "--spring.datasource.username=t12_runtime_local", "--spring.datasource.password=fictitious",
              "--spring.datasource.hikari.pool-name=CapacityPool", "--spring.datasource.hikari.register-mbeans=true",
              f"--spring.datasource.hikari.minimum-idle={args.pool}", f"--spring.datasource.hikari.maximum-pool-size={args.pool}",
              f"--app.jwt.secret={b64encode(secrets.token_bytes(48)).decode('ascii')}", "--spring.jpa.hibernate.ddl-auto=validate",
              "--app.demo.enabled=false", "--app.bootstrap.admin.enabled=false", "--app.agreements.enabled=false",
              "--app.subscriptions.mode=DISABLED"]
    pg_log = args.output / "postgres.log"
    app_log = args.output / "application.log"
    pool_log = args.output / "pool.csv"
    rows = []
    with (args.output / "config.json").open("w", encoding="utf-8") as config:
        json.dump(dict(label=args.label, jarSha256=digest, jvm=jvm, pool=args.pool, databasePort=args.db_port,
                       httpPort=args.http_port, actors=args.actors, rounds=args.rounds, clientTimeoutSeconds=2,
                       mixedStudentsA=args.students if args.journey else None, harnessSha256=harness), config, indent=2)
    with capacity.cluster(args, pg_log), background(launch, app_log, args.output / "application.err", args.output) as app:
        ready(app, app_log, "Started ChemicalLabBackendApplication", 100)
        # Allow startup/JIT tasks to settle before testing the 2 s client deadline.
        sleep(3)
        if not args.journey:
            path = capacity.prepare(args)
            names = ["runtime-teacher-a", *(f"green-load-teacher-{n}" for n in range(1, 4))]
            tokens = []
            for name in names:
                status, body, _ = capacity.request(args.http_port, "/api/auth/login", login={
                    "usernameOrEmail": name, "password": "FictitiousRuntime-2026!"})
                if status != 200:
                    raise RuntimeError(f"Synthetic login failed ({status})")
                tokens.append(json.loads(body)["token"])
            status, body, _ = capacity.request(args.http_port, path, tokens[0])
            assert status == 200
            for token in tokens:
                code, warm, _ = capacity.request(args.http_port, path, token)
                assert code == 200 and warm == body
        psql = [args.pg / "psql.exe", "-h", "127.0.0.1", "-p", str(args.db_port), "-U", "t12_runtime_local",
                "-d", "chemicallab_t12_runtime_test", "-XqtA", "-f", TOOLS / "sample-local-postgres.sql"]
        probe = [args.jdk / "bin/java.exe", TOOLS / "SampleLocalPool.java", str(app.pid)]
        with ExitStack() as observers:
            pg = observers.enter_context(background(psql, args.output / "postgres-samples.jsonl", args.output / "postgres-samples.err"))
            sampler = observers.enter_context(background(probe, pool_log, args.output / "pool-stacks.log"))
            pids = capacity.sql(args, "select pid from pg_stat_activity where application_name='PostgreSQL JDBC Driver'")
            processes = ["pwsh.exe", "-NoProfile", "-NonInteractive", "-File", TOOLS / "SampleLocalProcesses.ps1",
                         "-JavaId", str(app.pid), "-PostgresIds", ",".join(pids.split())]
            process_log = args.output / "processes.jsonl"
            process_sampler = observers.enter_context(background(processes, process_log, args.output / "processes.err"))
            if process_sampler.poll() is not None:
                raise RuntimeError("Process sampler did not start")
            # Wait for the attach/source compiler to finish before timing any requests.
            deadline = monotonic() + 25
            while (not pool_log.stat().st_size or not process_log.stat().st_size) and sampler.poll() is None and monotonic() < deadline:
                sleep(0.1)
            if not pool_log.stat().st_size or not process_log.stat().st_size or pg.poll() is not None:
                raise RuntimeError("A sampler did not start")
            if args.journey:
                if args.students > 20:
                    capacity.command([*psql[:-2], "-v", "ON_ERROR_STOP=1", "-f", TOOLS / "prepare-local-class.sql"])
                subprocess.run(["node", args.journey, args.fixture, args.pg / "psql.exe", str(args.db_port),
                                str(args.http_port), args.output, str(args.students), str(args.rounds)],
                               check=True, timeout=240)
            else:
                before = capacity.resources(args, app.pid)
                for row in windows(args, path, tokens, sha256(body).digest()):
                    after = capacity.resources(args, app.pid)
                    row["processSamples"] = dict(before=list(before.values()), after=list(after.values()), java=app.pid)
                    before = after
                    rows.append(row)
                    print({key: value for key, value in row.items() if key not in {"requests", "processSamples"}}, flush=True)
                    if row["stopped"]:
                        break
            # Let the observer record blocked server work after client deadlines too.
            sleep(2.5)
    (args.output / "windows.json").write_text(json.dumps(rows, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
