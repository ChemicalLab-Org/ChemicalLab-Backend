import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';

const [run, psql, port] = process.argv.slice(2);
assert(run && psql && /^\d{4,5}$/.test(port), 'Pass the disposable run directory, psql and the database port.');
const browser = join(run, 'browser');
const ready = JSON.parse(readFileSync(join(browser, 'ready.json'), 'utf8'));
const origin = new URL(ready.origin);
assert.equal(origin.hostname, '127.0.0.1');
assert.equal(ready.mode, 'runtime');
const started = process.resourceUsage();
const name = 'chemicallab_t12_runtime_test';
let database = name;
let httpRequests = 0, httpBytes = 0, messages = 0, messageBytes = 0;
const results = [];
const open = new Set();

function sql(query, db = database) {
  return execFileSync(psql, ['-h', '127.0.0.1', '-p', port, '-U', 't12_runtime_local', '-d', db, '-XqtA',
    '-v', 'ON_ERROR_STOP=1', '-c', query], { encoding: 'utf8', windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] }).trim();
}
assert.equal(sql("select current_database()||'/'||host(inet_server_addr())"), name + '/127.0.0.1');
assert.equal(sql('select version from board_runtime.contract'), '1');

const step = name => { results.push(name); console.log(name); };

async function until(probe, label, ms = 11000) {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    const result = await probe();
    if (result) return result;
    await delay(50);
  }
  throw new Error('Timeout: ' + label);
}

async function http(path, token, status = 200, method = 'GET', body, key = randomUUID()) {
  const headers = token ? { Authorization: 'Bearer ' + token } : {};
  const multipart = body instanceof FormData;
  if (body !== undefined && !multipart) headers['Content-Type'] = 'application/json';
  if (method !== 'GET') headers['Idempotency-Key'] = key;
  httpRequests++;
  const response = await fetch(new URL(path, origin), {
    method, headers, body: body === undefined || multipart ? body : JSON.stringify(body),
  });
  const bytes = Buffer.from(await response.arrayBuffer());
  httpBytes += bytes.length;
  assert.equal(response.status, status, method + ' ' + path + ' · ' + bytes.toString().slice(0, 220));
  return {
    headers: response.headers, bytes,
    data: bytes.length && response.headers.get('content-type')?.includes('application/json') ? JSON.parse(bytes) : undefined,
  };
}

async function login(username) {
  const result = await http('/api/auth/login', null, 200, 'POST', { usernameOrEmail: username, password: ready.password });
  assert(typeof result.data.token === 'string');
  return result.data.token;
}

const ids = JSON.parse(sql(`select json_object_agg(i.name||c.code,json_build_array(i.id,c.id))
 from evaluation_runtime.classes c join evaluation_runtime.institutions i on i.id=c.institution_id`));
const a = ids['Fictitious ACHEM'], b = ids['Fictitious BCHEM'], other = ids['Fictitious APHYS'];
assert(a && b && other);
const path = ([i, c], mode, id, action) =>
  `/api/institutions/${i}/classes/${c}/whiteboards/v1/${mode}${id === undefined ? '' : '/' + id}${action ? '/' + action : ''}`;
const target = (scope, board, role) => ({
  header: scope.join('/') + '/' + board + '/' + role,
  destination: `/institutions/${scope[0]}/classes/${scope[1]}/whiteboards/v1/${board}`,
});

function channel(token, scope, board, role) {
  const expected = target(scope, board, role);
  const socket = new WebSocket(origin.href.replace('http:', 'ws:') + 'ws/000/' + randomUUID() + '/websocket');
  const state = { connected: false, closed: null, frames: [], send, subscribe, draw, wait };
  open.add(socket);
  function send(command, fields = {}, body = '') {
    const text = command + '\n' + Object.entries(fields).map(([key, value]) => key + ':' + value + '\n').join('') + '\n' + body + '\0';
    const wire = JSON.stringify([text]);
    messages++; messageBytes += Buffer.byteLength(wire);
    socket.send(wire);
  }
  function subscribe(destination = '/topic' + expected.destination, id = 'board') {
    send('SUBSCRIBE', { destination, id, ack: 'auto' });
  }
  function draw(event, destination = '/app' + expected.destination + '/draw') {
    send('SEND', { destination, 'content-type': 'application/json', 'client-event-id': event.clientEventId }, JSON.stringify(event));
  }
  function wait(predicate, label) {
    return until(() => state.frames.find(predicate), label);
  }
  socket.addEventListener('close', event => { state.closed ??= event.code; open.delete(socket); });
  socket.addEventListener('error', () => { state.closed ??= 1006; });
  socket.addEventListener('message', ({ data }) => {
    messages++; messageBytes += Buffer.byteLength(data);
    if (data === 'o') {
      send('CONNECT', { 'accept-version': '1.2', 'heart-beat': '10000,10000', Authorization: 'Bearer ' + token,
        'board-scope': expected.header });
    } else if (data.startsWith('c')) {
      state.closed = JSON.parse(data.slice(1))[0];
    } else if (data.startsWith('a')) {
      for (const raw of JSON.parse(data.slice(1))) {
        if (raw.startsWith('CONNECTED')) state.connected = true;
        if (raw.startsWith('MESSAGE')) {
          const body = raw.slice(raw.indexOf('\n\n') + 2).split('\0')[0];
          state.frames.push(JSON.parse(body));
        }
      }
    }
  });
  state.init = async (subscribe = true) => {
    await until(() => state.connected, 'CONNECT ' + expected.header);
    if (!subscribe) return state;
    state.subscribe('/user/queue/whiteboard-errors', 'errors');
    state.subscribe();
    send('SEND', { destination: '/app' + expected.destination + '/ready' });
    await wait(f => f.ready, 'subscriptions registered');
    return state;
  };
  state.close = () => socket.close();
  return state;
}

const shape = (name, eventType = 'SHAPE', clientEventId = randomUUID()) => ({
  eventType, tool: 'RECTANGLE', shapeId: name, clientEventId, points: eventType === 'SHAPE' ? [{ x: 6, y: 8 }, { x: 31, y: 41 }] : [],
  color: '#224466', strokeWidth: 4,
});
const snapshot = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGOwMYoCAAF2AMnr8kO/AAAAAElFTkSuQmCC';
const finalFile = (valid = true) => {
  const form = new FormData();
  form.append('snapshot', new Blob([valid ? Buffer.from(snapshot, 'base64') : Buffer.from('falso')], { type: 'image/png' }), 'board.png');
  return form;
};
const dbNow = () => JSON.parse(sql(`select json_build_object(
  'boards',(select count(*) from board_runtime.boards),
  'participants',(select count(*) from board_runtime.admissions),
  'objects',(select count(*) from whiteboard_object_ownership),
  'receipts',(select count(*) from board_runtime.operations),
  'audit',(select count(*) from board_runtime.operations o join system_logs l on l.id=o.audit_id
    and l.correlation_id=o.operation_id and l.outcome='SUCCESS'),
  'quotas',(select json_agg(to_jsonb(q) order by institution_id) from evaluation_runtime.quotas q))`));

let error;
let postgres;
try {
  const teacherA = await login(ready.teacherA), teacherB = await login(ready.teacherB), student = await login(ready.student);
  const create = async (scope, token, name, key) => (await http(path(scope, 'teacher'), token, 200, 'POST', { name }, key)).data.id;
  const key = randomUUID();
  const a1 = await Promise.all([create(a, teacherA, 'Pizarra concurrente A', key), create(a, teacherA, 'Pizarra concurrente A', key)]);
  assert.equal(a1[0], a1[1]);
  const idA = a1[0], idB = await create(b, teacherB, 'Pizarra ficticia B');
  const classA2 = await create(other, teacherA, 'Otra clase de A');
  const detail = (s, id, token = teacherA) => http(path(s, 'teacher', id), token);
  const state = async (s, id, token = teacherA) => (await http(path(s, 'teacher', id, 'state'), token)).data;
  const joinBoard = (s, id, token = student, status = 200) => http(path(s, 'student', id, 'join'), token, status, 'POST', {});
  const joinBatch = await Promise.all([joinBoard(a, idA), joinBoard(a, idA)]);
  assert(joinBatch.every(row => row.data.joined && !row.data.canInteract));
  await joinBoard(b, idB);
  assert.equal((await detail(a, idA)).data.participants.length, 1);
  const otherClass = (await http(path(other, 'student') + '/active', student)).data.content.map(row => row.id);
  assert(otherClass.includes(classA2) && !otherClass.includes(idA) && !otherClass.includes(idB));
  for (const [scope, foreign, token] of [[a, idB, teacherA], [b, idA, teacherB], [other, idA, teacherA]]) {
    for (const suffix of ['', 'state', 'snapshot', 'participants']) {
      await http(path(scope, 'teacher', foreign, suffix), token, 403);
    }
    await http(path(scope, 'student', foreign), student, 403);
    await joinBoard(scope, foreign, student, 403);
  }
  await http('/api/whiteboards/teacher', teacherA, 403);
  await http('/api/whiteboards/student/active', student, 403);
  await http(path(a, 'student', idA, 'snapshot'), null, 401);
  step('HTTP bilateral y entre clases, join concurrente y corte legacy');

  sql(`insert into evaluation_runtime.memberships(institution_id,account_id,valid_from)
    select ${a[0]},id,statement_timestamp()-interval '1 minute' from user_accounts where username='runtime-teacher-b'
    on conflict do nothing;
    insert into evaluation_runtime.memberships(institution_id,account_id,valid_from)
    select ${b[0]},id,statement_timestamp()-interval '1 minute' from user_accounts where username='runtime-teacher-a'
    on conflict do nothing;
    insert into evaluation_runtime.roles(membership_id,institution_id,role,valid_from)
    select m.id,m.institution_id,case when m.institution_id=${a[0]} then 'DOCENTE' else 'ADMIN_INSTITUCION' end,
      statement_timestamp()-interval '1 minute' from evaluation_runtime.memberships m
    join user_accounts u on u.id=m.account_id where (u.username='runtime-teacher-b' and m.institution_id=${a[0]})
      or (u.username='runtime-teacher-a' and m.institution_id=${b[0]}) on conflict do nothing;
    insert into evaluation_runtime.teaching(institution_id,class_id,membership_id,faculty,valid_from,valid_until)
    select ${a[0]},${a[1]},m.id,'CO_DOCENTE',statement_timestamp()-interval '1 minute',statement_timestamp()+interval '1 day'
    from evaluation_runtime.memberships m join user_accounts u on u.id=m.account_id
    where u.username='runtime-teacher-b' and m.institution_id=${a[0]} on conflict do nothing;`);
  await detail(a, idA, teacherB);
  await http(path(b, 'teacher'), teacherA, 403);
  await http(path(b, 'teacher', idB, 'interaction'), teacherA, 403, 'PATCH', { interactionEnabled: true });
  const admin = channel(teacherA, b, idB, 'teacher');
  await until(() => admin.closed === 4003, 'ADMIN_INSTITUCION CONNECT negado');
  step('CO_DOCENTE efectivo y ADMIN_INSTITUCION sin facultad docente');

  for (const [scope, local, opposite, foreign] of [[a, idA, b, idB], [b, idB, a, idA]]) {
    const bad = channel(student, scope, foreign, 'student');
    await until(() => bad.closed === 4003, 'CONNECT de otro ámbito rechazado');
    const sub = await channel(student, scope, local, 'student').init(false);
    sub.subscribe('/topic' + target(opposite, foreign, 'student').destination);
    await until(() => sub.closed === 4003, 'SUBSCRIBE de otro ámbito rechazado');
    const send = await channel(student, scope, local, 'student').init(false);
    send.draw(shape('cruce'), '/app' + target(opposite, foreign, 'student').destination + '/draw');
    await until(() => send.closed === 4003, 'SEND de otro ámbito rechazado');
  }
  step('CONNECT, SUBSCRIBE y SEND rechazados en ambos sentidos');

  const t = await channel(teacherA, a, idA, 'teacher').init();
  const co = await channel(teacherB, a, idA, 'teacher').init();
  const pupil = await channel(student, a, idA, 'student').init();
  const duplicate = await channel(student, a, idA, 'student').init();
  const remote = await channel(student, b, idB, 'student').init();
  const blocked = shape('sin-permiso');
  pupil.draw(blocked);
  await pupil.wait(frame => frame.clientEventId === blocked.clientEventId && frame.accepted === false, 'dibujo sin permiso');
  await http(path(a, 'teacher', idA, 'interaction'), teacherB, 200, 'PATCH', { interactionEnabled: true });
  const teacherShape = shape('autor-docente');
  co.draw(teacherShape);
  await pupil.wait(frame => frame.clientEventId === teacherShape.clientEventId && frame.revision === 1, 'difusión al alumno');
  const studentShape = shape('autor-alumno');
  pupil.draw(studentShape);
  duplicate.draw(studentShape);
  await t.wait(frame => frame.clientEventId === studentShape.clientEventId && frame.revision === 2, 'dibujo concurrente');
  assert.equal((await state(a, idA)).revision, 2);
  const alien = shape('autor-docente', 'SHAPE_DELETE');
  pupil.draw(alien);
  await pupil.wait(frame => frame.clientEventId === alien.clientEventId && frame.accepted === false, 'borrado ajeno');
  const tombstone = shape('autor-alumno', 'SHAPE_DELETE');
  pupil.draw(tombstone);
  await t.wait(frame => frame.clientEventId === tombstone.clientEventId && frame.revision === 3, 'tombstone');
  const restored = shape('autor-alumno');
  duplicate.draw(restored);
  await t.wait(frame => frame.clientEventId === restored.clientEventId && frame.revision === 4, 'restauración del autor');
  await http(path(a, 'teacher', idA, 'pause'), teacherA, 200, 'POST', {});
  const paused = shape('en-pausa');
  pupil.draw(paused);
  await pupil.wait(frame => frame.clientEventId === paused.clientEventId && frame.accepted === false, 'dibujo durante pausa');
  await http(path(a, 'teacher', idA, 'resume'), teacherB, 200, 'POST', {});
  const participant = (await detail(a, idA)).data.participants[0];
  await http(path(a, 'teacher', idA, 'participants/' + participant.studentId + '/interaction'),
    teacherA, 200, 'PATCH', { interactionOverride: 'BLOCKED' });
  const individual = shape('bloqueo-individual');
  pupil.draw(individual);
  await pupil.wait(frame => frame.clientEventId === individual.clientEventId && frame.accepted === false, 'permiso individual');
  assert.equal((await state(a, idA)).revision, 4);
  assert.equal(remote.frames.filter(frame => frame.revision || frame.eventType).length, 0);
  step('websocket, interacción efectiva, dueño, restauración de tombstone y recibo de dibujo único');

  const before = dbNow();
  sql(`create function board_runtime.reject_test_audit() returns trigger language plpgsql as $$
    begin raise exception 'Fictitious audit failure'; end $$;
    create trigger board_test_audit before insert on system_logs for each row
      when (new.target_type='WhiteboardSession' and new.action='PAUSE') execute function board_runtime.reject_test_audit();`);
  const retry = randomUUID();
  try {
    await http(path(a, 'teacher', idA, 'pause'), teacherA, 503, 'POST', {}, retry);
    assert.deepEqual(dbNow(), before);
    assert.equal((await state(a, idA)).status, 'ACTIVE');
    assert.equal(t.frames.filter(frame => frame.eventType === 'SESSION_PAUSED').length, 1);
  } finally { sql('drop trigger board_test_audit on system_logs; drop function board_runtime.reject_test_audit()'); }
  await http(path(a, 'teacher', idA, 'pause'), teacherA, 200, 'POST', {}, retry);
  const limits = JSON.parse(sql(`select to_jsonb(q) from evaluation_runtime.quotas q where institution_id=${a[0]}`));
  sql(`update evaluation_runtime.quotas set max_rows=used_rows where institution_id=${a[0]}`);
  const constrained = dbNow();
  try {
    await http(path(a, 'teacher', idA, 'resume'), teacherA, 409, 'POST', {});
    assert.equal((await state(a, idA)).status, 'PAUSED');
    assert.deepEqual(dbNow(), constrained);
  } finally { sql(`update evaluation_runtime.quotas set max_rows=${limits.max_rows} where institution_id=${a[0]}`); }
  await http(path(a, 'teacher', idA, 'resume'), teacherA, 200, 'POST', {});
  step('rollback por auditoría y cuota, sin difusión del fallo y reintento');

  const closedA = await create(a, teacherA, 'Captura A'), closedB = await create(b, teacherB, 'Captura B');
  await joinBoard(a, closedA);
  await joinBoard(b, closedB);
  await http(path(a, 'teacher', closedA, 'close') + '?revision=1', teacherA, 409, 'POST', finalFile());
  await http(path(a, 'teacher', closedA, 'close') + '?revision=0', teacherA, 400, 'POST', finalFile(false));
  const closeKey = randomUUID();
  const close = (scope, board, token, key) => http(path(scope, 'teacher', board, 'close') + '?revision=0', token, 200, 'POST', finalFile(), key);
  const [first, second] = await Promise.all([close(a, closedA, teacherA, closeKey), close(a, closedA, teacherA, closeKey)]);
  assert(first.data.snapshotAvailable && second.data.snapshotAvailable);
  await close(b, closedB, teacherB);
  await joinBoard(a, closedA, student, 403);
  await http(path(a, 'teacher', closedA, 'resume'), teacherA, 403, 'POST', {});
  for (const [scope, home, foreign] of [[a, closedA, closedB], [b, closedB, closedA]]) {
    const image = await http(path(scope, 'student', home, 'snapshot'), student);
    assert.equal(image.headers.get('content-type'), 'image/png');
    assert.equal(image.headers.get('x-content-type-options'), 'nosniff');
    assert.match(image.headers.get('cache-control'), /no-store/);
    await http(path(scope, 'student', foreign, 'snapshot'), student, 403);
    assert((await http(path(scope, 'student') + '/history', student)).data.content.some(row => row.id === home));
  }
  step('captura validada, cierre por revisión y carreras del mismo UUID, historia bilateral');

  duplicate.close();
  await until(() => duplicate.closed !== null, 'desconexión de la segunda pestaña');
  const passive = await channel(student, a, idA, 'student').init(false);
  sql(`update evaluation_runtime.memberships set active=false where institution_id=${a[0]} and account_id=
    (select id from user_accounts where username='runtime-student')`);
  const afterRevoke = shape('después-de-revocar-a');
  t.draw(afterRevoke);
  await t.wait(frame => frame.clientEventId === afterRevoke.clientEventId && frame.revision === 5, 'cambio A posterior a revocación');
  await until(() => pupil.closed === 4003 && passive.closed === 4003, 'revocación sobre una conexión activa y otra pasiva');
  assert.equal(pupil.frames.some(frame => frame.clientEventId === afterRevoke.clientEventId), false);
  assert.equal(remote.closed, null);
  await http(path(a, 'student', closedA, 'snapshot'), student, 403);
  await http(path(b, 'student', closedB, 'snapshot'), student);
  assert.equal((await detail(a, idA)).data.participants.length, 0);
  step('revocación A corta salientes y conexión pasiva, mantiene B y oculta participantes');

  sql(`update evaluation_runtime.memberships set active=true where institution_id=${a[0]} and account_id=
    (select id from user_accounts where username='runtime-student')`);
  const restoredA = await channel(student, a, idA, 'student').init(false);
  sql("update user_accounts set credentials_version=credentials_version+1 where username='runtime-student'");
  await until(() => restoredA.closed === 4001 && remote.closed === 4001, 'revocación global de ambas conexiones');
  await http(path(b, 'student', closedB, 'snapshot'), student, 401);
  step('revocación global de la cuenta en A y B');

  for (const socket of open) socket.close();
  const fingerprint = () => JSON.parse(sql(`select json_build_object(
    'boards',(select md5(coalesce(string_agg(to_jsonb(x)::text,'' order by id),'')) from whiteboard_sessions x),
    'participants',(select md5(coalesce(string_agg(to_jsonb(x)::text,'' order by id),'')) from whiteboard_participants x),
    'ownership',(select md5(coalesce(string_agg(to_jsonb(x)::text,'' order by id),'')) from whiteboard_object_ownership x),
    'scope',(select md5(coalesce(string_agg(to_jsonb(x)::text,'' order by board_id),'')) from board_runtime.boards x),
    'admissions',(select md5(coalesce(string_agg(to_jsonb(x)::text,'' order by participant_id),'')) from board_runtime.admissions x),
    'operations',(select md5(coalesce(string_agg(to_jsonb(x)::text,'' order by operation_id),'')) from board_runtime.operations x),
    'audit',(select md5(coalesce(string_agg(to_jsonb(l)::text,'' order by l.id),'')) from board_runtime.operations o join system_logs l on l.id=o.audit_id),
    'quota',(select md5(coalesce(string_agg(to_jsonb(x)::text,'' order by institution_id),'')) from evaluation_runtime.quotas x),
    'storage',(select md5(coalesce(string_agg(to_jsonb(x)::text,'' order by scope,owner_id,resource),'')) from storage_usage x))`));
  const durable = fingerprint();
  const counts = dbNow();
  assert.equal(counts.audit, counts.receipts);
  writeFileSync(join(run, 'board-durable-before.json'), JSON.stringify({ durable, counts }, null, 2));
  writeFileSync(join(browser, 'restart'), '');
  await until(() => existsSync(join(run, 'restarted.pid')), 'reinicio', 100000);
  assert.deepEqual(fingerprint(), durable);
  const newStudent = await login(ready.student);
  const original = await http(path(b, 'student', closedB, 'snapshot'), newStudent);
  assert.equal((await state(a, idA)).revision, 5);
  writeFileSync(join(browser, 'recover'), '');
  await until(() => existsSync(join(run, 'recovered.pid')), 'restauración', 100000);
  database = 'chemicallab_t12_board_recovered_test';
  assert.deepEqual(fingerprint(), durable);
  assert.equal((await state(a, idA)).revision, 5);
  const recovered = await http(path(b, 'student', closedB, 'snapshot'), newStudent);
  assert.deepEqual(recovered.bytes, original.bytes);
  writeFileSync(join(run, 'board-durable-restored.json'), JSON.stringify({ durable: fingerprint(), counts: dbNow() }, null, 2));
  postgres = JSON.parse(sql(`select json_build_object('databaseBytes',pg_database_size(current_database()),
    'commits',xact_commit,'rollbacks',xact_rollback,'blocksRead',blks_read,'blocksHit',blks_hit)
    from pg_stat_database where datname=current_database()`));
  step('reinicio y restauración mantienen estado, bytes, cuotas, auditoría y recibos');
} catch (failure) {
  error = failure.stack;
  process.exitCode = 1;
  console.error(error);
} finally {
  for (const socket of open) socket.close();
  const end = process.resourceUsage();
  writeFileSync(join(run, 'board-http-stomp-results.json'), JSON.stringify({ error, results,
    traffic: { httpRequests, httpResponseBodyBytes: httpBytes, sockJsFrames: messages, sockJsPayloadBytes: messageBytes },
    resource: { processCpuMs: (end.userCPUTime - started.userCPUTime + end.systemCPUTime - started.systemCPUTime) / 1000,
      peakRssKiB: end.maxRSS, postgres } }, null, 2));
}
