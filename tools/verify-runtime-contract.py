"""Focused HTTP and database checks, after the browser run on the disposable runtime DB."""
import argparse
import json
import subprocess
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import urlsplit
from urllib.request import ProxyHandler, Request, build_opener
from uuid import uuid4

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("run", type=Path)
parser.add_argument("--psql", required=True)
parser.add_argument("--port", type=int, default=55472)
args = parser.parse_args()
ready = json.loads((args.run / "browser/ready.json").read_text(encoding="utf-8-sig"))
scenario = json.loads((args.run / "browser/assignments.json").read_text(encoding="utf-8-sig"))
assert ready["mode"] == "runtime" and urlsplit(ready["origin"]).hostname == "127.0.0.1"
opener = build_opener(ProxyHandler({}))
base = f"host=127.0.0.1 port={args.port} dbname=chemicallab_t12_runtime_test user=t12_runtime_local"
checks = []


def request(method, path, token=None, data=None, expected=200, discard=False):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    content = None if data is None else json.dumps(data).encode("utf-8")
    req = Request(ready["origin"] + "/api" + path, data=content, headers=headers, method=method)
    try:
        with opener.open(req, timeout=35) as response:
            assert response.status == expected, (path, expected, response.status)
            if discard or response.status == 204:
                return None
            return json.load(response)
    except HTTPError as failure:
        assert failure.code == expected, (path, expected, failure.code)
        return None


def psql(sql, client="runtime_verify"):
    result = subprocess.run([args.psql, "-XAtq", "-v", "ON_ERROR_STOP=1", "-d",
                             base + " application_name=" + client, "-c", sql],
                            capture_output=True, text=True, check=True)
    return result.stdout.strip()


def cmd(**values):
    return {"operationId": str(uuid4()), **values}


def login(label):
    return request("POST", "/auth/login", data={
        "usernameOrEmail": ready[label], "password": ready["password"]})["token"]


def context(inst, group):
    return f"/institutions/{inst}/classes/{group}"


def catalog(scope, kind):
    return scope + "/evaluation-catalog/NEW/v1/" + kind


def revisions(scope, token):
    return request("GET", catalog(scope, "revisions"), token)["items"]


def edition(scope, identity):
    return f"{scope}/evaluation-authoring/NEW/v1/evaluations/{identity['evaluationId']}/revisions/{identity['revisionId']}"


def assign(scope, token, rev, pupil_episode, due):
    path = edition(scope, rev).replace("evaluation-authoring", "evaluation-assignments") + "/deliveries"
    now = datetime.now(timezone.utc)
    return request("POST", path, token, cmd(expectedRevisionVersion=rev["version"], enrollmentIds=[pupil_episode],
        startsAt=(now-timedelta(seconds=10)).isoformat().replace("+00:00", "Z"),
        dueAt=due.isoformat().replace("+00:00", "Z")))


def execute(scope, row):
    return (f"{scope}/evaluation-execution/NEW/v1/evaluations/{row['evaluationId']}"
            f"/deliveries/{row['deliveryId']}/revisions/{row['revisionId']}")


def log(label):
    checks.append(label)
    print(label, flush=True)


def main():
    assert psql("select current_database()||':'||inet_server_addr()") == "chemicallab_t12_runtime_test:127.0.0.1"
    teacher_a, teacher_b, pupil = (login(name) for name in ("teacherA", "teacherB", "student"))
    choices = request("GET", "/institutions/evaluation-catalog/NEW/v1/classes?limit=2", pupil)
    more = request("GET", "/institutions/evaluation-catalog/NEW/v1/classes?after=" + str(choices["nextAfter"]), pupil)
    visible = choices["items"] + more["items"]
    a = next(row for row in visible if row["institutionId"] == ready["a"] and "CHEM" in row["className"])
    aa = next(row for row in visible if row["institutionId"] == ready["a"] and "PHYS" in row["className"])
    b = next(row for row in visible if row["institutionId"] == ready["b"])
    scope_a, scope_aa, scope_b = (context(row["institutionId"], row["classId"]) for row in (a, aa, b))
    rev_a = revisions(scope_a, teacher_a)[0]
    rev_b = revisions(scope_b, teacher_b)[0]
    from_browser_a = {**rev_a, "deliveryId": scenario["a"]["delivery"]}
    from_browser_b = {**rev_b, "deliveryId": scenario["b"]["delivery"]}
    request("GET", catalog(context(a["institutionId"], b["classId"]), "own-deliveries"), pupil, expected=403)
    request("GET", catalog(context(b["institutionId"], a["classId"]), "own-deliveries"), pupil, expected=403)
    request("GET", execute(scope_a, from_browser_b), pupil, expected=403)
    request("GET", execute(scope_b, from_browser_a), pupil, expected=403)
    request("GET", catalog(scope_b, "enrollments"), teacher_a, expected=403)
    request("GET", catalog(scope_a, "reviewable-attempts"), teacher_b, expected=403)
    request("GET", "/evaluations/student", pupil, expected=403)
    request("GET", f"/evaluations/teacher/{rev_a['evaluationId']}", teacher_a, expected=403)
    log("Bilateral selectors, teacher facilities and legacy request fence")

    history=catalog(scope_a,f"own-deliveries/{scenario['a']['delivery']}/attempts")
    browser_attempt=request("GET",history,pupil)["items"][0]["attemptId"]
    frozen=execute(scope_a,from_browser_a)+f"/own-attempts/{browser_attempt}/correction"
    assert request("GET",frozen,pupil)["finalScore"]==20
    (args.run/"browser/restart").touch()
    deadline=time.monotonic()+100
    while not (args.run/"restarted.pid").exists():
        assert time.monotonic()<deadline, "Normal application restart did not complete"
        time.sleep(0.25)
    assert request("GET",frozen,pupil)["finalScore"]==20
    log("Persisted correction recovered after restarting the normal JVM")

    payload = {
        "resource": {"title": "Presupuesto compartido", "max_attempts": 2, "question_display_mode": "ALL_AT_ONCE"},
        "questions": [{"id": 1, "question_text": "Elige A", "question_type": "MULTIPLE_CHOICE",
                       "required": True, "points": 1}],
        "options": [{"id": 1, "question_id": 1, "option_text": "A", "correct": True},
                    {"id": 2, "question_id": 1, "option_text": "B", "correct": False}],
    }
    # An unscoped lock that blocked the certified implementation cannot block a native root write.
    blocker = subprocess.Popen([args.psql, "-XAtq", "-d", base + " application_name=runtime_legacy_lock", "-c",
        "begin; lock table evaluations in share mode; select pg_sleep(12); rollback"], stdout=subprocess.DEVNULL)
    try:
        end = time.monotonic() + 8
        while psql("""select exists(select 1 from pg_locks l join pg_stat_activity a using(pid)
            where a.application_name='runtime_legacy_lock' and relation='evaluations'::regclass and granted)::int""") != "1":
            assert time.monotonic() < end, "legacy blocker not acquired"
            time.sleep(0.15)
        create_path = scope_a + "/evaluation-authoring/NEW/v1/evaluations"
        created = request("POST", create_path, teacher_a, cmd(payload=payload))
        assert blocker.poll() is None, "native creation did not complete while the unrelated lock was held"
    finally:
        blocker.wait(timeout=16)
    log("Normal write completed while the legacy evaluations table retained the historical blocking lock")

    rev = request("POST", edition(scope_a, created)+"/publish", teacher_a, cmd(expectedVersion=1))
    request("PUT", edition(scope_a, rev), teacher_a, cmd(expectedVersion=rev["version"], payload=payload), expected=409)
    episode_a = request("GET", catalog(scope_a, "enrollments"), teacher_a)["items"][0]["enrollmentId"]
    episode_aa = request("GET", catalog(scope_aa, "enrollments"), teacher_a)["items"][0]["enrollmentId"]
    due = datetime.now(timezone.utc)+timedelta(minutes=5)
    delivered = assign(scope_a,teacher_a,rev,episode_a,due)
    successor = request("POST", edition(scope_a,rev)+"/successor",teacher_a,cmd(expectedVersion=rev["version"]))
    publication = request("POST",edition(scope_a,successor)+"/publish",teacher_a,cmd(expectedVersion=successor["version"]))
    other = assign(scope_aa,teacher_a,publication,episode_aa,due)
    first_url, second_url = execute(scope_a,delivered)+"/own-attempts", execute(scope_aa,other)+"/own-attempts"
    first_key=cmd()
    request("POST", first_url, pupil, first_key, discard=True)
    one = request("POST", first_url, pupil, first_key)
    assert one["attemptNumber"] == 1
    request("POST", second_url, pupil, cmd(), expected=403)
    request("GET", second_url+"/"+str(one["attemptId"]),pupil,expected=403)
    request("POST", first_url+"/"+str(one["attemptId"])+"/submit",pupil,
        cmd(answers=[{"revisionId":rev["revisionId"],"questionId":1,"selectedOptionId":1}]))
    request("GET", first_url+"/"+str(one["attemptId"])+"/correction",pupil,expected=403)
    log("Receipt recovered after discarded response, with one active attempt across classes and revisions")

    resource=rev["evaluationId"]
    snapshot=f"""select json_build_array(
      (select count(*) from evaluation_runtime.attempts where resource_id={resource}),
      (select count(*) from evaluation_runtime.operations where evaluation_id={resource}),
      (select used_rows from evaluation_runtime.quotas where institution_id={a['institutionId']}),
      (select used_bytes from evaluation_runtime.quotas where institution_id={a['institutionId']}))::text"""
    before=psql(snapshot)
    two_key=cmd()
    psql("""create function public.runtime_verify_reject_audit() returns trigger language plpgsql as $$
    begin if new.action='START_NEW_EXECUTION' then raise exception 'deliberate disposable T08 fault'; end if;
    return new; end $$;
    create trigger runtime_verify_reject_audit before insert on system_logs for each row
    execute function public.runtime_verify_reject_audit()""")
    try:
        request("POST",second_url,pupil,two_key,expected=503)
        assert psql(snapshot)==before
    finally:
        psql("drop trigger runtime_verify_reject_audit on system_logs; drop function public.runtime_verify_reject_audit()")
    with ThreadPoolExecutor(max_workers=2) as workers:
        responses=list(workers.map(lambda _: request("POST",second_url,pupil,two_key), range(2)))
    two=responses[0]
    assert two["attemptNumber"]==2 and responses[1]==two
    request("POST",second_url+"/"+str(two["attemptId"])+"/submit",pupil,
        cmd(answers=[{"revisionId":publication["revisionId"],"questionId":1,"selectedOptionId":1}]))
    request("POST",first_url,pupil,cmd(),expected=403)
    request("POST",second_url,pupil,cmd(),expected=403)
    log("Mandatory audit failure rolled back attempt and quota; explicit same-key recovery consumed opportunity two")

    maximum=psql(f"select max_rows from evaluation_runtime.quotas where institution_id={a['institutionId']}")
    psql(f"update evaluation_runtime.quotas set max_rows=used_rows where institution_id={a['institutionId']}")
    before=psql(snapshot)
    try:
        request("POST",create_path,teacher_a,cmd(payload=payload),expected=503)
        assert psql(snapshot)==before
    finally:
        psql(f"update evaluation_runtime.quotas set max_rows={int(maximum)} where institution_id={a['institutionId']}")
    log("Quota rejection left counters, receipts and academic state unchanged")

    delegated=request("POST",create_path,teacher_a,cmd(payload=payload))
    root=delegated["evaluationId"]
    institution=a["institutionId"]
    psql(f"""
      begin;
      select pg_advisory_xact_lock(evaluation_runtime.institution_lock({institution}));
      insert into evaluation_runtime.memberships(institution_id,account_id,valid_from)
        select {institution},id,clock_timestamp() from user_accounts where username='runtime-teacher-b';
      insert into evaluation_runtime.roles(membership_id,institution_id,role,valid_from)
        select m.id,m.institution_id,case when u.username='runtime-teacher-a' then 'ADMIN_INSTITUCION' else 'DOCENTE' end,clock_timestamp()
        from evaluation_runtime.memberships m join user_accounts u on u.id=m.account_id
        where m.institution_id={institution} and u.username in ('runtime-teacher-a','runtime-teacher-b');
      insert into evaluation_runtime.faculties(resource_id,institution_id,membership_id,faculty,granted_by_membership_id,valid_from,valid_until)
        select {root},{institution},b.id,'PUBLISH',a.id,clock_timestamp(),clock_timestamp()+interval '5 minutes'
        from evaluation_runtime.memberships a join user_accounts ua on ua.id=a.account_id
        cross join evaluation_runtime.memberships b join user_accounts ub on ub.id=b.account_id
        where a.institution_id={institution} and b.institution_id={institution}
          and ua.username='runtime-teacher-a' and ub.username='runtime-teacher-b';
      commit;
    """)
    listing=revisions(scope_a,teacher_b)
    assert [row["evaluationId"] for row in listing]==[root] and listing[0]["canPublish"] and not listing[0]["canEdit"]
    request("GET",edition(scope_a,delegated),teacher_b,expected=403)
    pubkey=cmd(expectedVersion=delegated["version"])
    request("POST",edition(scope_a,delegated)+"/publish",teacher_b,pubkey)
    psql(f"update evaluation_runtime.faculties set revoked_at=clock_timestamp() where resource_id={root}")
    request("POST",edition(scope_a,delegated)+"/publish",teacher_b,pubkey,expected=403)
    log("An exact PUBLISH grant confers neither EDIT_DRAFT nor access after grant revocation")

    fresh_b = request("POST",scope_b+"/evaluation-authoring/NEW/v1/evaluations",teacher_b,cmd(payload=payload))
    fresh_b = request("POST",edition(scope_b,fresh_b)+"/publish",teacher_b,cmd(expectedVersion=1))
    episode_b = request("GET",catalog(scope_b,"enrollments"),teacher_b)["items"][0]["enrollmentId"]
    current_b = assign(scope_b,teacher_b,fresh_b,episode_b,datetime.now(timezone.utc)+timedelta(minutes=2))
    psql(f"""update evaluation_runtime.memberships set active=false where institution_id={a['institutionId']}
      and account_id=(select id from user_accounts where username='runtime-student')""")
    request("GET",catalog(scope_a,"own-deliveries"),pupil,expected=403)
    b_page=request("GET",catalog(scope_b,"own-deliveries"),pupil)
    assert [row["evaluationId"] for row in b_page["items"]]==[fresh_b["evaluationId"]]
    request("GET",execute(scope_b,current_b),pupil)
    assert [x["institutionId"] for x in request("GET","/institutions/evaluation-catalog/NEW/v1/classes",pupil)["items"]]==[b["institutionId"]]
    psql(f"""update evaluation_runtime.teaching set ended_at=clock_timestamp() where class_id={a['classId']}
      and membership_id in (select m.id from evaluation_runtime.memberships m
        join user_accounts u on m.account_id=u.id where u.username='runtime-teacher-a')""")
    request("GET",catalog(scope_a,"reviewable-attempts"),teacher_a,expected=403)
    request("POST","/auth/logout-all",pupil,expected=204)
    request("GET",catalog(scope_a,"own-deliveries"),pupil,expected=401)
    request("GET",catalog(scope_b,"own-deliveries"),pupil,expected=401)
    log("Local membership and teaching revocations; global session revocation cuts both schools")


try:
    main()
finally:
    (args.run/"runtime-verification.json").write_text(json.dumps(checks,indent=2)+"\n",encoding="utf-8")
