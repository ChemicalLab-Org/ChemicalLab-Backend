import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { setTimeout as pause } from 'node:timers/promises';

const [run, psql, port] = process.argv.slice(2);
assert(run && psql && /^\d{4,5}$/.test(port));
const browser = join(run, 'browser');
const ready = JSON.parse(readFileSync(join(browser, 'ready.json'), 'utf8'));
const origin = new URL(ready.origin);
assert.equal(origin.hostname, '127.0.0.1');
let database = 'chemicallab_t12_runtime_test';
const base = '/api/agreements';
const api = '/api/institutions/evaluation-catalog/NEW/v1/classes';
const steps = [];
let requests = 0;
let error = null;

function sql(query) {
  return execFileSync(psql, ['-h', '127.0.0.1', '-p', port, '-U', 't12_runtime_local', '-d', database,
    '-XqtA', '-v', 'ON_ERROR_STOP=1', '-c', query], { encoding: 'utf8', windowsHide: true }).trim();
}

async function call(method, path, token, expected = 200, body) {
  const headers = {};
  if (token) headers.Authorization = `Bearer ${token}`;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (method === 'POST' && path.includes('/whiteboards/')) headers['Idempotency-Key'] = randomUUID();
  const res = await fetch(new URL(path, origin), { method, headers,
    body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(12000) });
  const payload = await res.text();
  requests++;
  assert.equal(res.status, expected, `${method} ${path} (${payload.slice(0, 250)})`);
  return payload ? JSON.parse(payload) : null;
}

const get = (path, token, expected) => call('GET', path, token, expected);
const post = (path, token, body, expected) => call('POST', path, token, expected, body);
const patch = (path, token, body, expected) => call('PATCH', path, token, expected, body);
const login = async (username, password = ready.password) =>
  (await post('/api/auth/login', null, { usernameOrEmail: username, password })).token;
const me = (token) => get('/api/auth/me', token);
const overview = (token) => get(`${base}/account`, token);
const submit = (token, id, subjectId, action, expected = 200, requestId = randomUUID()) =>
  post(`${base}/account/versions/${id}/decisions`, token, { subjectId, action, requestId }, expected);
const item = (state, code, institutionId = null) => state.items.find(row =>
  row.document.code === code && row.document.institutionId === institutionId);
const mark = (label) => { steps.push(label); console.log(label); };

async function waitFor(probe, label) {
  for (let attempts = 0; attempts < 480; attempts++) {
    if (await probe()) return;
    await pause(250);
  }
  throw new Error(`Tiempo de espera agotado: ${label}`);
}

async function restart(flag) {
  writeFileSync(join(browser, flag), '1');
  const marker = flag === 'restart' ? 'restarted.pid' : 'recovered.pid';
  await waitFor(() => existsSync(join(run, marker)), marker);
}

async function stompDenied(token, institution, clazz, reason) {
  const address = new URL(`/ws/000/${randomUUID()}/websocket`, origin);
  address.protocol = 'ws:';
  const socket = new WebSocket(address);
  try {
    const outcome = await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('Tiempo de espera de cierre STOMP agotado')), 10000);
      const settle = (value) => { clearTimeout(timer); resolve(value); };
      socket.addEventListener('error', () => settle({ code: 0, reason: 'TRANSPORT_ERROR' }));
      socket.addEventListener('close', ({ code, reason }) => settle({ code, reason }));
      socket.addEventListener('message', ({ data }) => {
        if (data === 'o') socket.send(JSON.stringify([`CONNECT\naccept-version:1.2\nAuthorization:Bearer ${token}\nboard-scope:${institution}/${clazz}/1/student\n\n\0`]));
        if (data.startsWith('c')) {
          const [code, reason] = JSON.parse(data.slice(1));
          settle({ code, reason });
        }
        if (data.includes('CONNECTED')) settle({ code: 0, reason: 'CONNECTED' });
      });
    });
    assert.deepEqual(outcome, { code: 4003, reason });
  } finally {
    if (socket.readyState < WebSocket.CLOSING) socket.close();
  }
}

try {
  assert.equal(sql('select current_database() || \'/\' || host(inet_server_addr())'), `${database}/127.0.0.1`);
  const [classA, classB] = sql("select id from evaluation_runtime.classes where code='CHEM' order by institution_id")
    .split(/\r?\n/).map(Number);
  const scopeA = `/api/institutions/${ready.a}/classes/${classA}/evaluation-catalog/NEW/v1/own-deliveries`;
  const scopeB = `/api/institutions/${ready.b}/classes/${classB}/evaluation-catalog/NEW/v1/own-deliveries`;
  const boardHistory = `/api/institutions/${ready.a}/classes/${classA}/whiteboards/v1/student/history`;
  const [{ id: termsId }, { id: noticeId }] = await get(`${base}/public`).then(docs =>
    ['TERMS', 'NOTICE'].map(kind => docs.find(doc => doc.kind === kind)));
  const text = await get(`${base}/public/${termsId}`);
  assert.equal(text.document.sha256, createHash('sha256').update(text.body, 'utf8').digest('hex'));
  await get(`${base}/account`, null, 401);
  const student = await login(ready.student);
  const teacherA = await login(ready.teacherA);
  const teacherB = await login(ready.teacherB);
  const [studentId, teacherAId, teacherBId] = sql(`select id from user_accounts where username in ('${ready.student}', '${ready.teacherA}', '${ready.teacherB}') order by username`)
    .split(/\r?\n/).map(Number);
  assert.equal((await get(api, student, 403)).code, 'AGREEMENTS_REQUIRED');
  await get(boardHistory, student);
  await stompDenied(student, ready.a, classA, 'AGREEMENTS_REQUIRED');
  assert.equal((await overview(student)).globalBlocked, true);
  await me(student);
  await submit(student, noticeId, studentId, 'GRANT', 409);
  await submit(student, noticeId, studentId, 'ACKNOWLEDGE');
  assert.equal((await get(api, student, 403)).code, 'AGREEMENTS_REQUIRED');
  mark('aviso público, hash y constancia separada de la condición obligatoria en HTTP y STOMP');

  const platformTemporary = await login(ready.platform, ready.platformTemporary);
  assert.equal((await get(api, platformTemporary, 403)).code, 'PASSWORD_CHANGE_REQUIRED');
  await overview(platformTemporary);
  const platformResponse = await patch('/api/auth/change-temporary-password', platformTemporary, {
    currentPassword: ready.platformTemporary, newPassword: 'Fictitious-Agreement-HTTP-2026!',
    confirmPassword: 'Fictitious-Agreement-HTTP-2026!',
  });
  const platform = platformResponse.token;
  await get('/api/auth/me', platformTemporary, 401);
  const platformId = Number(sql("select account_id from administration_runtime.platform_authority"));
  assert.equal((await get(api, platform, 403)).code, 'AGREEMENTS_REQUIRED');
  await submit(platform, termsId, platformId, 'ACCEPT');
  mark('contraseña temporal, login, comprobación de sesión e invalidación del token anterior');

  const key = randomUUID();
  const [initial, repeated] = await Promise.all([student, student].map(token => submit(token, termsId, studentId, 'ACCEPT', 200, key)));
  assert.deepEqual(initial, repeated);
  await submit(student, noticeId, studentId, 'ACKNOWLEDGE', 409, key);
  assert.equal(sql(`select count(*) from agreement_runtime.decisions where request_id='${key}'`), '1');
  await get(api, student);
  const studentScope = await overview(student);
  const contractA = item(studentScope, 'acuerdo-demo', ready.a).document.id;
  const contractB = item(studentScope, 'acuerdo-demo', ready.b).document.id;
  const consent = item(studentScope, 'finalidad-demo', ready.a).document.id;
  await get(`${base}/public/${contractA}`, null, 404);
  await get(`${base}/account/versions/${contractA}`, teacherB, 404);
  await get(`${base}/account/versions/${contractA}`, student);
  await submit(student, contractA, null, 'ACCEPT', 403);
  await submit(teacherA, contractB, null, 'ACCEPT', 404);
  await submit(teacherA, termsId, teacherAId, 'ACCEPT');
  await submit(teacherB, termsId, teacherBId, 'ACCEPT');
  await submit(teacherA, contractA, null, 'ACCEPT');
  await get(scopeA, student);
  await get(boardHistory, student);
  assert.equal((await get(scopeB, student, 403)).code, 'AGREEMENTS_REQUIRED');
  await submit(teacherB, contractB, null, 'ACCEPT');
  await get(scopeB, student);
  mark('reintentos concurrentes y una cuenta compartida con contratos independientes por colegio');

  const platformUrl = '/api/platform/institutions/v1';
  const { id: third } = await post(platformUrl, platform, { name: 'Fictitious C' });
  const state = `${platformUrl}/${third}/state`;
  await patch(state, platform, { active: true }, 409);
  const first = await post(`${platformUrl}/${third}/first-administrator`, platform, {
    username: 'FixtureDirector', displayName: 'Dirección de ensayo',
  });
  const director = await login(first.username, first.temporaryPassword);
  const docC = Number(sql(`with s as (insert into agreement_runtime.series(code,kind,institution_id,authority,required)
    values('acuerdo-demo','INSTITUTIONAL',${third},'INSTITUTION',true) returning id)
    insert into agreement_runtime.versions(series_id,version,renewal,title,body,change_summary)
    select id,1,1,'Acuerdo piloto','Texto ficticio.','Primera entrega.' from s returning id`));
  const review = `${base}/review/directors/${third}/${first.membershipId}`;
  await submit(director, docC, null, 'ACCEPT', 403);
  const verified = { documentId: docC, result: 'VERIFIED', evidenceReference: 'DEMO-FACULTAD',
    validUntil: new Date(Date.now() + 86400000).toISOString(), requestId: randomUUID() };
  await post(review, teacherA, verified, 403);
  const authority = await post(review, platform, verified);
  assert.deepEqual(await post(review, platform, verified), authority);
  assert.equal((await get(`${review}?document=${docC}`, platform))[0].id, authority.id);
  await patch(state, platform, { active: true }, 409);
  await submit(director, docC, null, 'ACCEPT');
  await patch(state, platform, { active: true }, 204);
  mark('alta de colegio inactivo, revisión con referencia y activación tras firma del director');

  const schoolMember = Number(sql(`select id from evaluation_runtime.memberships where account_id=${studentId} and institution_id=${ready.a}`));
  const family = `${base}/review/families/${ready.a}/${schoolMember}`;
  const pending = { result: 'PENDING', evidenceReference: 'DEMO-PENDING', requestId: randomUUID() };
  await post(family, teacherA, pending, 403);
  sql(`insert into evaluation_runtime.roles(membership_id,institution_id,role,valid_from)
    select id,institution_id,'ADMIN_INSTITUCION',clock_timestamp() from evaluation_runtime.memberships
    where account_id=${teacherAId} and institution_id=${ready.a}`);
  await post(family, teacherA, pending);
  await post(`${base}/review/families/${ready.b}/${schoolMember}`, teacherA, pending, 403);
  assert.equal((await get(scopeA, student, 403)).code, 'ACADEMIC_AUTHORIZATION_REQUIRED');
  await get(boardHistory, student);
  await stompDenied(student, ready.a, classA, 'ACADEMIC_AUTHORIZATION_REQUIRED');
  await get(scopeB, student);
  const approved = { result: 'VERIFIED', basis: 'PARENT_OR_GUARDIAN', evidenceReference: 'DEMO-VERIFIED',
    validUntil: new Date(Date.now() + 3600000).toISOString(), requestId: randomUUID() };
  await post(family, teacherA, { ...approved, basis: null }, 400);
  const check = await post(family, teacherA, approved);
  assert.deepEqual(await post(family, teacherA, approved), check);
  await get(scopeA, student);
  await post(family, platform, { result: 'WITHDRAWN', evidenceReference: 'DEMO-DIRECT', requestId: randomUUID() });
  assert.equal((await get(`${base}/account/family-status`, student))[0].status, 'WITHDRAWN');
  assert.equal((await get(scopeA, student, 403)).code, 'ACADEMIC_AUTHORIZATION_REQUIRED');
  await get(boardHistory, student);
  await get(scopeB, student);
  await post(family, platform, { result: 'NOT_REQUIRED', evidenceReference: 'DEMO-LEGAL', requestId: randomUUID() });
  await get(scopeA, student);
  mark('acreditación familiar, rechazo de actores sin facultad, retirada y alcance académico por colegio');

  await submit(student, consent, studentId, 'GRANT', 403);
  await submit(teacherA, consent, studentId, 'DECLINE');
  await get(scopeA, student);
  await submit(teacherA, consent, studentId, 'GRANT');
  await submit(teacherA, consent, studentId, 'WITHDRAW');
  assert.equal(item(await overview(student), 'finalidad-demo', ready.a).currentAction, 'WITHDRAW');
  await get(scopeA, student);
  mark('rechazo y retirada voluntarios sin bloquear la función académica');

  const boards = `/api/institutions/${ready.a}/classes/${classA}/whiteboards/v1`;
  const board = await post(`${boards}/teacher`, teacherA, { name: 'Pizarra ficticia de acuerdos' });
  await post(`${boards}/student/${board.id}/join`, student);
  assert.equal(sql(`select count(*) from board_runtime.boards where board_id=${board.id} and institution_id=${ready.a}`), '1');
  mark('docente crea una pizarra y la cuenta compartida participa con su pertenencia autorizada');

  const publish = (version, renewal) => Number(sql(`insert into agreement_runtime.versions(series_id,version,renewal,title,body,change_summary)
    select id,${version},${renewal},'Condiciones de ensayo','Texto distinto.','Cambio de ensayo.'
    from agreement_runtime.series where code='terminos-demo' returning id`));
  publish(2, 1);
  await get(api, student);
  const renewed = publish(3, 2);
  assert.deepEqual(await submit(student, termsId, studentId, 'ACCEPT', 200, key), initial);
  await submit(student, termsId, studentId, 'ACCEPT', 409);
  assert.equal((await get(api, student, 403)).code, 'AGREEMENTS_REQUIRED');
  await submit(student, renewed, studentId, 'ACCEPT');
  await get(scopeA, student);
  assert.throws(() => sql(`update agreement_runtime.versions set title='alterado' where id=${termsId}`));
  assert.throws(() => sql('delete from agreement_runtime.decisions'));
  const history = await get(`${base}/account/history`, student);
  const families = await get(`${base}/account/family-status`, student);
  await restart('restart');
  assert.deepEqual(await get(`${base}/account/history`, student), history);
  await restart('recover');
  database = 'chemicallab_t12_administration_recovered_test';
  assert.deepEqual(await get(`${base}/account/history`, student), history);
  assert.deepEqual(await get(`${base}/account/family-status`, student), families);
  await get(scopeA, student);
  await get(scopeB, student);
  assert.equal(sql(`select count(*) from board_runtime.boards where board_id=${board.id} and institution_id=${ready.a}`), '1');
  await post('/api/auth/logout', student, undefined, 204);
  await get(`${base}/account`, student, 401);
  mark('renovación selectiva, rechazo de versión obsoleta, inmutabilidad, reinicio, restauración y cierre de sesión');
} catch (failure) {
  error = String(failure);
  console.error(failure);
  process.exitCode = 1;
} finally {
  writeFileSync(join(run, 'agreement-http.json'), JSON.stringify({ sha: readFileSync(join(run, 'backend-sha.txt'), 'utf8').trim(),
    steps, requests, error }, null, 2));
}
