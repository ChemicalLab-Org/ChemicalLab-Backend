import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { setTimeout as pause } from 'node:timers/promises';

const [run, psql, port, mode] = process.argv.slice(2);
assert(run && psql && /^\d{4,5}$/.test(port));
const browser = join(run, 'browser');
const ready = JSON.parse(readFileSync(join(browser, 'ready.json'), 'utf8'));
const origin = new URL(ready.origin);
assert.equal(origin.hostname, '127.0.0.1');
assert.equal(ready.mode, 'runtime');
const initialDatabase = 'chemicallab_t12_runtime_test';
let database = initialDatabase;
const sockets = new Set();
const steps = [];
let requests = 0, bytes = 0;
const platformUrl = '/api/platform/institutions/v1';
const now = Date.now();
const from = new Date(now - 3600000).toISOString();
const until = new Date(now + 86400000).toISOString();

function sql(query) {
  return execFileSync(psql, ['-h', '127.0.0.1', '-p', port, '-U', 't12_runtime_local', '-d', database,
    '-XqtA', '-v', 'ON_ERROR_STOP=1', '-c', query], { encoding: 'utf8', windowsHide: true }).trim();
}

assert.equal(sql("select current_database()||'/'||host(inet_server_addr())"), initialDatabase + '/127.0.0.1');

async function call(method, path, token, status, body) {
  const headers = {};
  if (token) headers.Authorization = 'Bearer ' + token;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (method !== 'GET' && path.includes('/whiteboards/')) headers['Idempotency-Key'] = randomUUID();
  const result = await fetch(new URL(path, origin), { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  const payload = await result.text();
  requests++;
  bytes += Buffer.byteLength(payload);
  assert.equal(result.status, status, method + ' ' + path + (result.ok ? '' : ' ' + payload.slice(0, 200)));
  return payload ? JSON.parse(payload) : null;
}

const get = (path, token, status = 200) => call('GET', path, token, status);
const post = (path, token, body, status = 200) => call('POST', path, token, status, body);
const patch = (path, token, body, status = 204) => call('PATCH', path, token, status, body);
const authenticate = async (username, password) =>
  (await post('/api/auth/login', null, { usernameOrEmail: username, password })).token;

async function setupPassword(username, temporary) {
  const token = await authenticate(username, temporary);
  const password = 'Fictitious-Administration-Changed-2026!';
  const replacement = await patch('/api/auth/change-temporary-password', token,
    { currentPassword: temporary, newPassword: password, confirmPassword: password }, 200);
  return replacement.token;
}

const institution = id => '/api/institutions/' + id + '/administration/v1';
const boardPath = (scope, kind, suffix = '') => '/api/institutions/' + scope[0] + '/classes/' + scope[1] + '/whiteboards/v1/' + kind + suffix;

async function waitFor(predicate, label, timeout = 20000) {
  const end = Date.now() + timeout;
  while (Date.now() < end) {
    if (await predicate()) return;
    await pause(80);
  }
  throw new Error('Timeout: ' + label);
}

async function observe(token, scope, board) {
  const socket = new WebSocket(origin.href.replace('http:', 'ws:') + 'ws/000/' + randomUUID() + '/websocket');
  const state = { closed: 0, ready: false, drawing: false };
  sockets.add(socket);
  const base = '/institutions/' + scope[0] + '/classes/' + scope[1] + '/whiteboards/v1/' + board;
  const send = (command, fields, body = '') => socket.send(JSON.stringify([command + '\n' +
    Object.entries(fields).map(([key, value]) => key + ':' + value).join('\n') + '\n\n' + body + '\0']));
  state.draw = () => {
    const clientEventId = randomUUID();
    send('SEND', { destination: '/app' + base + '/draw', 'client-event-id': clientEventId,
      'content-type': 'application/json' },
    JSON.stringify({ eventType: 'SHAPE', tool: 'RECTANGLE', shapeId: 'administrative-revocation',
      clientEventId, points: [{ x: 6, y: 8 }, { x: 31, y: 41 }], color: '#224466', strokeWidth: 4 }));
  };
  socket.addEventListener('close', ({ code }) => { state.closed ||= code; sockets.delete(socket); });
  socket.addEventListener('message', ({ data }) => {
    if (data === 'o') {
      send('CONNECT', { 'accept-version': '1.2', 'heart-beat': '10000,10000', Authorization: 'Bearer ' + token,
        'board-scope': scope.join('/') + '/' + board + '/student' });
    } else if (data.startsWith('c')) {
      state.closed = JSON.parse(data.slice(1))[0];
    } else if (data.startsWith('a')) {
      for (const frame of JSON.parse(data.slice(1))) {
        if (frame.startsWith('CONNECTED')) {
          send('SUBSCRIBE', { destination: '/user/queue/whiteboard-errors', id: 'errors', ack: 'auto' });
          send('SUBSCRIBE', { destination: '/topic' + base, id: 'board', ack: 'auto' });
          send('SEND', { destination: '/app' + base + '/ready' });
        }
        if (frame.startsWith('MESSAGE') && frame.includes('"ready":true')) state.ready = true;
        if (frame.startsWith('MESSAGE') && frame.includes('administrative-revocation')) state.drawing = true;
      }
    }
  });
  await waitFor(() => state.ready, 'suscripción SockJS/STOMP');
  return state;
}

function durable() {
  const names = sql(`select schemaname||'.'||tablename from pg_tables
    where schemaname in ('evaluation_runtime','content_runtime','board_runtime','administration_runtime')
       or schemaname='public' and tablename in ('whiteboard_sessions','whiteboard_participants',
            'whiteboard_object_ownership','system_logs','storage_used')
    order by 1`).split(/\r?\n/);
  return Object.fromEntries(names.map(name => [
    name, JSON.parse(sql(`select json_build_array(count(*),md5(coalesce(string_agg(md5(to_jsonb(t)::text),
                    ',' order by md5(to_jsonb(t)::text)),''))) from ${name} t`)),
  ]));
}

if (mode === 'before') {
  const snapshot = durable();
  writeFileSync(join(run, 'jkl-before.json'), JSON.stringify(snapshot, null, 2));
  for (const table of ['evaluation_runtime.attempts','content_runtime.files','board_runtime.operations']) {
    assert(snapshot[table][0] > 0, 'Populate ' + table + ' before recording the baseline.');
  }
  process.exit(0);
}

try {
  assert.equal(sql('select version from administration_runtime.contract'), '1');
  const baseline = JSON.parse(readFileSync(join(run, 'jkl-before.json'), 'utf8'));
  const after = durable();
  for (const [table, signature] of Object.entries(baseline)) assert.deepEqual(after[table], signature, table);
  writeFileSync(join(run, 'jkl-after.json'), JSON.stringify(after, null, 2));
  steps.push('ampliación J+K+L conservó las firmas de tablas pobladas');

  const platform = await setupPassword(ready.platform, ready.platformTemporary);
  const scopes = JSON.parse(sql(`select json_object_agg(i.name,json_build_array(i.id,c.id))
    from evaluation_runtime.classes c join evaluation_runtime.institutions i on i.id=c.institution_id where c.code='CHEM'`));
  const a = scopes['Fictitious A'], b = scopes['Fictitious B'];
  const admins = [];
  for (const [key, scope] of [['A', a], ['B', b]]) {
    const username = 'UpgradeAdmin' + key;
    const created = await post(platformUrl + '/' + scope[0] + '/first-administrator', platform,
      { username, displayName: 'Dirección ' + key });
    admins.push({ token: await setupPassword(username, created.temporaryPassword), membership: created.membershipId });
  }
  const [adminA, adminB] = admins;
  const student = await authenticate(ready.student, ready.password);
  const teachers = await Promise.all([ready.teacherA, ready.teacherB].map(username => authenticate(username, ready.password)));
  const memberships = JSON.parse(sql(`select json_object_agg(m.institution_id,m.id)
    from evaluation_runtime.memberships m join user_accounts u on u.id=m.account_id where u.username='runtime-student'`));

  await get(institution(a[0]) + '/summary', platform, 403);
  await get(platformUrl, adminA.token, 403);
  await get(institution(b[0]) + '/summary', adminA.token, 403);
  await get(institution(a[0]) + '/records/MEMBERS', adminB.token, 403);
  for (const path of ['/api/admin/users','/api/admin/summary','/api/admin/activity','/api/admin/academic-supervision/summary',
    '/api/admin/usage-metrics/panel','/api/admin/student-usage-records','/api/admin/logs/summary','/api/users/teachers']) {
    await get(path, platform, 403);
  }
  await post('/api/usage-metrics/events', student, { module: 'DASHBOARD', eventType: 'MODULE_ACCESS' }, 403);
  await patch('/api/admin/users/1/password/reset', adminA.token, {}, 403);
  await post(institution(a[0]) + '/members', adminA.token, { username: ready.student, displayName: 'Duplicado' }, 409);
  await post(institution(a[0]) + '/classes', adminA.token, { name: 'Ajena', code: 'XX', periodId:
    Number(sql('select period_id from evaluation_runtime.classes where id=' + b[1])) }, 403);
  await post(boardPath(a, 'teacher'), adminA.token, { name: 'Solo colegio' }, 403);
  await call('PUT', institution(a[0]) + '/members/' + adminA.membership + '/roles/DOCENTE', adminA.token, 200);
  await post(boardPath(a, 'teacher'), adminA.token, { name: 'Sin asignación' }, 403);
  for (const [library, schema] of [['EVALUATIONS', 'evaluation_runtime'], ['CONTENT', 'content_runtime']]) {
    const facultyPath = institution(a[0]) + '/library/' + library + '/faculties';
    const resource = scope => Number(sql('select min(id) from ' + schema + '.resources where institution_id=' + scope[0]));
    await post(facultyPath, adminA.token, { resourceId: resource(b), membershipId: adminA.membership, faculty: 'ASSIGN', until }, 403);
    const edit = await post(facultyPath, adminA.token, { resourceId: resource(a), membershipId: adminA.membership, faculty: 'EDIT_DRAFT', until });
    const publish = await post(facultyPath, adminA.token, { resourceId: resource(a), membershipId: adminA.membership, faculty: 'PUBLISH', until });
    await call('DELETE', facultyPath + '/' + publish.id, adminA.token, 204);
    const faculties = await get(institution(a[0]) + '/records/FACULTIES?library=' + library, adminA.token);
    assert.equal(faculties.items.find(item => item.id === edit.id).revoked_at, null);
    assert(faculties.items.find(item => item.id === publish.id).revoked_at);
  }
  steps.push('separación plataforma / colegio, alta sin vínculo externo, permisos compuestos y rutas globales bloqueadas');
  steps.push('facultades de biblioteca independientes y revocables en ambos módulos');

  const boards = [];
  for (const [index, scope] of [a, b].entries()) {
    const admin = admins[index].token;
    const teacher = teachers[index];
    const board = await post(boardPath(scope, 'teacher'), teacher, { name: 'Administración ' + index });
    boards.push(board.id);
    await post(boardPath(scope, 'student', '/' + board.id + '/join'), student);
    const records = await get(institution(scope[0]) + '/records/MEMBERS?limit=1', admin);
    assert.equal(records.items.length, 1);
    assert(records.nextAfter);
    assert(!('account_id' in records.items[0]));
    const activity = await get(institution(scope[0]) + '/activity?limit=1&member=' + memberships[scope[0]], admin);
    assert.equal(activity.items[0].target_id, board.id);
    assert.equal(activity.items[0].actor_membership_id, memberships[scope[0]]);
    const other = scope === a ? b : a;
    await get(institution(scope[0]) + '/audit?member=' + memberships[other[0]], admin, 403);
    const summary = await get(institution(scope[0]) + '/summary', admin);
    assert.equal(summary.boards, Number(sql('select count(*) from board_runtime.boards where institution_id=' + scope[0])));
  }
  steps.push('listados y páginas, conteos y actividad separada de la cuenta compartida');

  const originallyA = await observe(student, a, boards[0]);
  const passiveA = await observe(student, a, boards[0]);
  const passiveB = await observe(student, b, boards[1]);
  await patch(boardPath(a, 'teacher', '/' + boards[0] + '/interaction'), teachers[0], { interactionEnabled: true }, 200);
  originallyA.draw();
  await waitFor(() => originallyA.drawing && passiveA.drawing, 'actividad confirmada antes de suspender');
  await get(boardPath(a, 'student', '/' + boards[0] + '/state'), student);
  await patch(institution(a[0]) + '/members/' + memberships[a[0]] + '/state/SUSPEND', adminA.token, {});
  await waitFor(() => originallyA.closed && passiveA.closed, 'revocación institucional de los sockets de A');
  assert.equal(originallyA.closed, 4003);
  assert.equal(passiveA.closed, 4003);
  assert.equal(passiveB.closed, 0);
  await get(boardPath(a, 'student', '/' + boards[0] + '/state'), student, 403);
  await get(boardPath(b, 'student', '/' + boards[1] + '/state'), student);
  await get('/api/auth/me', student);
  await patch(institution(a[0]) + '/members/' + memberships[a[0]] + '/state/RESUME', adminA.token, {});
  await get(boardPath(a, 'student', '/' + boards[0] + '/state'), student);
  steps.push('revocación HTTP y de dos sockets de A con B y T02 vigentes');

  // Fault injection in this disposable database. Reject the required T08 row before the commit.
  sql(`create function public.reject_pilot_audit() returns trigger language plpgsql as $$ begin
      if NEW.action='PERIOD_CREATED' then raise exception 'pilot audit failure'; end if; return NEW; end $$;
      create trigger pilot_audit_failure before insert on system_logs for each row execute function public.reject_pilot_audit()`);
  try {
    const before = Number(sql('select count(*) from evaluation_runtime.periods where institution_id=' + a[0]));
    await post(institution(a[0]) + '/periods', adminA.token, { name: 'Rollback audit', from, until }, 503);
    assert.equal(Number(sql('select count(*) from evaluation_runtime.periods where institution_id=' + a[0])), before);
  } finally {
    sql('drop trigger pilot_audit_failure on system_logs; drop function public.reject_pilot_audit()');
  }
  const prior = await post(institution(a[0]) + '/periods', adminA.token,
    { name: 'Commit audit', from, until, actorId: 999999, institutionId: b[0] });
  const current = await get(institution(a[0]) + '/audit?limit=1', adminA.token);
  assert.equal(current.items[0].target_id, prior.id);
  assert.equal(current.items[0].actor_membership_id, adminA.membership);
  assert.equal(sql(`select count(*) from administration_runtime.changes c join system_logs l on l.id=c.audit_id
    where l.outcome='SUCCESS' and l.actor_user_id=c.actor_id and l.action=c.action`),
    sql('select count(*) from administration_runtime.changes'));
  steps.push('fallo inducido T08 revirtió el cambio y la evidencia utiliza actor y ámbito del servidor');

  const backup = durable();
  const signal = async (command, destination) => {
    writeFileSync(join(browser, command), '');
    await waitFor(() => existsSync(join(run, destination)), command, 120000);
  };
  await signal('restart', 'restarted.pid');
  assert.deepEqual(durable(), backup);
  await get(institution(a[0]) + '/summary', adminA.token);
  await signal('recover', 'recovered.pid');
  database = 'chemicallab_t12_administration_recovered_test';
  assert.deepEqual(durable(), backup);
  await get(institution(b[0]) + '/audit', adminB.token);
  await get(boardPath(b, 'student', '/' + boards[1] + '/state'), student);
  writeFileSync(join(run, 'administration-recovered.json'), JSON.stringify(backup, null, 2));
  steps.push('reinicio y restauración conservaron estados, recibos, cuotas y auditoría');
} finally {
  for (const socket of sockets) socket.close();
  writeFileSync(join(run, 'administration-http-stomp.json'), JSON.stringify({ steps, requests, responseBytes: bytes }, null, 2));
  for (const step of steps) console.log(step);
}
