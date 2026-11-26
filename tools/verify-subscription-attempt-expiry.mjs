import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { setTimeout as pause } from 'node:timers/promises';

const [run, psql, port] = process.argv.slice(2);
assert(run && psql && /^\d{4,5}$/.test(port));
const browser = join(run, 'browser');
const ready = JSON.parse(readFileSync(join(browser, 'ready.json'), 'utf8'));
const origin = new URL(ready.origin);
assert.equal(origin.hostname, '127.0.0.1');
const checks = [];
let requests = 0;
let error = null;

function sql(statement) {
  return execFileSync(psql, ['-XqtA', '-v', 'ON_ERROR_STOP=1', '-h', '127.0.0.1', '-p', port,
    '-U', 't12_runtime_local', '-d', 'chemicallab_t12_runtime_test', '-c', statement],
  { encoding: 'utf8', windowsHide: true }).trim();
}

async function api(method, path, token, body, code = 200) {
  const headers = {};
  if (token) headers.Authorization = 'Bearer ' + token;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const res = await fetch(new URL('/api' + path, origin), { method, headers,
    body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(30000) });
  const result = await res.text();
  requests++;
  assert.equal(res.status, code, method + ' ' + path + ' ' + result.slice(0, 240));
  return result ? JSON.parse(result) : null;
}
const get = (path, token, code) => api('GET', path, token, undefined, code);
const post = (path, token, body, code) => api('POST', path, token, body, code);
const command = fields => ({ operationId: randomUUID(), ...fields });
const log = label => { checks.push(label); console.log(label); };
const login = async (username, password = ready.password) =>
  (await post('/auth/login', null, { usernameOrEmail: username, password })).token;

async function waitFor(probe, label) {
  for (let i = 0; i < 360; i++) {
    if (await probe()) return;
    await pause(300);
  }
  throw new Error('Tiempo agotado: ' + label);
}

function setTime(position) {
  const relative = { before: "- interval '1 microsecond'", exact: '', after: "+ interval '1 microsecond'" }[position];
  const micros = { before: '-1', exact: '0', after: '1' }[position];
  sql(`UPDATE expiry_fixture.clock SET instant=(SELECT trial_end ${relative}
       FROM subscription_runtime.school WHERE institution_id=${ready.a})`);
  assert.equal(sql(`SELECT (extract(epoch FROM statement_timestamp()-trial_end)*1000000)::integer
      FROM subscription_runtime.school WHERE institution_id=${ready.a}`), micros);
}

function snapshot(school) {
  return JSON.parse(sql(`SELECT json_build_object('attempts',
    (SELECT count(*) FROM evaluation_runtime.attempts WHERE institution_id=${school}), 'receipts',
    (SELECT count(*) FROM evaluation_runtime.operations WHERE institution_id=${school}),
    'rows',used_rows,'bytes',used_bytes) FROM evaluation_runtime.quotas WHERE institution_id=${school}`));
}

const payload = title => ({
  resource: { title, max_attempts: 2, question_display_mode: 'ALL_AT_ONCE' },
  questions: [{ id: 1, question_text: 'Elige A', question_type: 'MULTIPLE_CHOICE', required: true, points: 1,
    explanation: 'Corrección ficticia reservada' }],
  options: [{ id: 1, question_id: 1, option_text: 'A', correct: true },
    { id: 2, question_id: 1, option_text: 'B', correct: false }],
});

async function makeDelivery(school, teacher, title, deadline) {
  const classId = Number(sql(`SELECT min(id) FROM evaluation_runtime.classes WHERE institution_id=${school}`));
  const scope = `/institutions/${school}/classes/${classId}`;
  const created = await post(scope + '/evaluation-authoring/NEW/v1/evaluations', teacher, command({ payload: payload(title) }));
  const revised = `/NEW/v1/evaluations/${created.evaluationId}/revisions/${created.revisionId}`;
  const revision = await post(scope + '/evaluation-authoring' + revised + '/publish', teacher, command({ expectedVersion: 1 }));
  const enrollments = await get(scope + '/evaluation-catalog/NEW/v1/enrollments', teacher);
  const delivery = await post(scope + '/evaluation-assignments' + revised + '/deliveries', teacher, command({
    expectedRevisionVersion: revision.version, enrollmentIds: [enrollments.items[0].enrollmentId],
    startsAt: new Date(Date.now() - 2000).toISOString(), dueAt: deadline,
  }));
  return {
    path: `${scope}/evaluation-execution/NEW/v1/evaluations/${created.evaluationId}/deliveries/${delivery.deliveryId}/revisions/${created.revisionId}`,
    revision: created.revisionId, delivery: delivery.deliveryId,
  };
}

try {
  assert.equal(sql("SELECT current_database()||':'||host(inet_server_addr())"), 'chemicallab_t12_runtime_test:127.0.0.1');
  assert.equal(sql("SELECT count(*) FROM subscription_runtime.school"), '0');
  // Shadow the SQL clock only in this disposable database. The API, comparison predicate and PostgreSQL remain real.
  sql(`
    CREATE SCHEMA expiry_fixture;
    CREATE TABLE expiry_fixture.clock(instant timestamptz);
    INSERT INTO expiry_fixture.clock VALUES (NULL);
    CREATE FUNCTION expiry_fixture.statement_timestamp() RETURNS timestamptz LANGUAGE sql STABLE
    AS $$ SELECT coalesce(instant,pg_catalog.statement_timestamp()) FROM expiry_fixture.clock $$;
    ALTER DATABASE chemicallab_t12_runtime_test SET search_path TO public,expiry_fixture,pg_catalog;
  `);
  writeFileSync(join(browser, 'restart'), '1');
  await waitFor(() => existsSync(join(run, 'restarted.pid')), 'nuevas conexiones PostgreSQL del backend');
  const initial = await login(ready.platform, ready.platformTemporary);
  const updated = 'Expiry-Local-Fixture-Changed-2026!';
  const platform = (await api('PATCH', '/auth/change-temporary-password', initial, {
    currentPassword: ready.platformTemporary, newPassword: updated, confirmPassword: updated,
  })).token;
  const teacherA = await login(ready.teacherA);
  const teacherB = await login(ready.teacherB);
  const student = await login(ready.student);
  for (const [school, ref] of [[ready.a, 'EXPIRY-TEST-A'], [ready.b, 'EXPIRY-TEST-B']]) {
    await post(`/platform/institutions/${school}/subscription/v1/verify`, platform, { evidenceReference: ref }, 204);
  }
  const aDeadline = new Date(Date.now() + 65000).toISOString();
  const bDeadline = new Date(Date.now() + 130000).toISOString();
  const active = await makeDelivery(ready.a, teacherA, 'Intento continuado', aDeadline);
  const atCutoff = await makeDelivery(ready.a, teacherA, 'Entrega exacta', aDeadline);
  const untouched = await makeDelivery(ready.a, teacherA, 'Nuevo intento', aDeadline);
  const other = await makeDelivery(ready.b, teacherB, 'Continuidad otro colegio', bDeadline);
  // The immutable trial row is adjusted only in this synthetic setup and its trigger is restored in the same transaction.
  sql(`BEGIN; ALTER TABLE subscription_runtime.school DISABLE TRIGGER subscription_trial_guard;
       UPDATE subscription_runtime.school SET trial_start=pg_catalog.statement_timestamp()-interval '1 month',
          trial_end=pg_catalog.statement_timestamp() WHERE institution_id=${ready.a};
       ALTER TABLE subscription_runtime.school ENABLE TRIGGER subscription_trial_guard; COMMIT;`);

  setTime('before');
  const start = await post(active.path + '/own-attempts', student, command());
  const exactStart = await post(atCutoff.path + '/own-attempts', student, command());
  const own = active.path + '/own-attempts/' + start.attemptId;
  const exactOwn = atCutoff.path + '/own-attempts/' + exactStart.attemptId;
  const answer = (revision, selectedOptionId) => ({ revisionId: revision, questionId: 1, selectedOptionId });
  const readOwn = async (path, expectedAnswer) => {
    const row = await get(path, student);
    assert.equal(new Date(row.deadline).toISOString(), aDeadline);
    assert.equal(row.attemptNumber, 1);
    assert.equal(row.answers[0]?.selectedOptionId, expectedAnswer);
    return row;
  };
  await post(own + '/answers', student, command({ answer: answer(active.revision, 2) }));
  await readOwn(own, 2);
  await post(exactOwn + '/answers', student, command({ answer: answer(atCutoff.revision, 1) }));
  await get(own + '/correction', student, 403);
  log('T−1 µs | inicio, guardado y recuperación con el dueAt original');

  setTime('exact');
  const before = snapshot(ready.a);
  const refused = await post(untouched.path + '/own-attempts', student, command(), 402);
  assert.equal(refused.code, 'INSTITUTION_SUBSCRIPTION_REQUIRED');
  assert.deepEqual(snapshot(ready.a), before);
  await readOwn(own, 2);
  await post(own + '/answers', student, command({ answer: answer(active.revision, 1) }));
  await readOwn(own, 1);
  assert.equal((await post(exactOwn + '/submit', student, command())).status, 'GRADED');
  await get(exactOwn + '/correction', student, 403);
  await post(own + '/answers', teacherB, command({ answer: answer(active.revision, 2) }), 403);
  log('T exacto | nuevo intento 402 sin consumo, recuperación, guardado y envío autorizados');

  setTime('after');
  const consumed = snapshot(ready.a);
  await post(untouched.path + '/own-attempts', student, command(), 402);
  assert.deepEqual(snapshot(ready.a), consumed);
  await readOwn(own, 1);
  await post(own + '/answers', student, command({ answer: answer(active.revision, 2) }));
  await readOwn(own, 2);
  const submit = command();
  assert.equal((await post(own + '/submit', student, submit)).status, 'GRADED');
  const completed = snapshot(ready.a);
  assert.equal(completed.attempts, before.attempts);
  assert(completed.rows >= before.rows && completed.bytes >= before.bytes);
  assert.equal((await post(own + '/submit', student, submit)).attemptId, start.attemptId);
  await post(own + '/answers', student, command({ answer: answer(active.revision, 1) }), 403);
  await post(own + '/submit', student, command(), 403);
  assert.deepEqual(snapshot(ready.a), completed);
  await readOwn(own, 2);
  assert.equal(new Date(sql(`SELECT due_at::text FROM evaluation_runtime.deliveries WHERE id=${active.delivery}`)).toISOString(), aDeadline);
  await get(own + '/correction', student, 403);
  const b = await post(other.path + '/own-attempts', student, command());
  await post(other.path + '/own-attempts/' + b.attemptId + '/answers', student, command({ answer: answer(other.revision, 1) }));
  await get(other.path + '/own-attempts/' + start.attemptId, student, 403);
  log('T+1 µs | respuestas y consumo conservados, sin reapertura ni efecto sobre el colegio B');

  // The T05 resolver also uses statement_timestamp(). Restore the live clock before testing academic closure.
  sql('UPDATE expiry_fixture.clock SET instant=NULL');
  await waitFor(() => sql(`SELECT (statement_timestamp()>=due_at)::int FROM evaluation_runtime.deliveries
    WHERE id=${active.delivery}`) === '1', 'vencimiento académico real');
  assert.equal((await get(own + '/correction', student)).answers[0].selectedOptionId, 2);
  await post(own + '/answers', student, command({ answer: answer(active.revision, 1) }), 403);
  assert.deepEqual(snapshot(ready.a), completed);
  log('T05 | corrección oculta antes del plazo académico y recuperable después. Sin cambios del dueAt ni del consumo.');
} catch (failure) {
  error = String(failure);
  throw failure;
} finally {
  writeFileSync(join(run, 'subscription-expiry-http.json'), JSON.stringify({
    backend: readFileSync(join(run, 'backend-sha.txt'), 'utf8').trim(), requests, checks, error,
  }, null, 2));
  writeFileSync(join(browser, 'stop'), '1');
}
