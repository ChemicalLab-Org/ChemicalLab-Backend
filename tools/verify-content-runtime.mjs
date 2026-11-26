import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { setTimeout as pause } from 'node:timers/promises';
import { deflateSync } from 'node:zlib';

const [run, psql, port, mode] = process.argv.slice(2);
assert(run && psql && /^\d+$/.test(port) && ['fresh', 'upgrade'].includes(mode));
const ready = read('browser/ready.json');
assert.equal(new URL(ready.origin).hostname, '127.0.0.1');
const sqlArgs = ['-XAtq', '-v', 'ON_ERROR_STOP=1', '-d',
  `host=127.0.0.1 port=${port} dbname=chemicallab_t12_runtime_test user=t12_runtime_local application_name=content_verify`];
const log = [];
const output = join(run, 'content-http-verification.json');

function read(file) { return JSON.parse(readFileSync(join(run, file), 'utf8')); }
function sql(statement) {
  return execFileSync(psql, [...sqlArgs, '-c', statement], { encoding: 'utf8' }).trim();
}
function sqlRejected(statement, reason) {
  const result = spawnSync(psql, [...sqlArgs, '-c', statement], { encoding: 'utf8' });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, reason);
}
function checked(label) {
  log.push(label);
  console.log(label);
  writeFileSync(output, JSON.stringify({ mode, checks: log }, null, 2));
}

async function http(method, path, token, body, status = 200, binary = false) {
  const form = body instanceof FormData;
  const response = await fetch(ready.origin + '/api' + path, {
    method, headers: { ...(token ? { Authorization: 'Bearer ' + token } : {}),
      ...(body && !form ? { 'Content-Type': 'application/json' } : {}) },
    body: form ? body : body && JSON.stringify(body), signal: AbortSignal.timeout(45000),
  });
  if (response.status !== status) {
    assert.fail(`${method} ${path}: expected ${status}, got ${response.status} ${await response.text()}`);
  }
  if (status !== 200) return;
  if (binary) return { data: Buffer.from(await response.arrayBuffer()), headers: response.headers };
  return response.json();
}
const get = (path, token, status) => http('GET', path, token, undefined, status);
const post = (path, token, body, status) => http('POST', path, token, body, status);
const operation = fields => ({ operationId: randomUUID(), ...fields });
const transition = (version, operationId = randomUUID()) => ({ operationId, version });
const login = async username => (await post('/auth/login', null, { usernameOrEmail: username, password: ready.password })).token;
const library = scope => scope + '/library';
const revision = (scope, r) => `${library(scope)}/${r.contentId}/revisions/${r.revisionId}`;
const assigned = (scope, r) => `${scope}/assigned/${r.deliveryId}/contents/${r.contentId}/revisions/${r.revisionId}`;
const create = (scope, token, title) => post(library(scope), token, operation({
  text: { title, category: 'Química', explanation: 'Explicación ficticia', links: [{ title: 'Lectura', url: 'https://example.org/material' }] },
}));

function chunk(name, value) {
  const contents = Buffer.concat([Buffer.from(name), value]);
  let crc = 0xffffffff;
  for (const byte of contents) {
    crc ^= byte;
    for (let bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ ((crc & 1) ? 0xedb88320 : 0);
  }
  const size = Buffer.alloc(4);
  size.writeUInt32BE(value.length);
  const checksum = Buffer.alloc(4);
  checksum.writeUInt32BE(~crc >>> 0);
  return Buffer.concat([size, contents, checksum]);
}
function png(red) {
  const header = Buffer.from([0, 0, 0, 1, 0, 0, 0, 1, 8, 2, 0, 0, 0]);
  return Buffer.concat([Buffer.from('89504e470d0a1a0a', 'hex'), chunk('IHDR', header),
    chunk('IDAT', deflateSync(Buffer.from([0, red, 50, 90]))), chunk('IEND', Buffer.alloc(0))]);
}
function upload(path, token, data, version, key = randomUUID(), status = 200) {
  const body = new FormData();
  body.set('operationId', key);
  body.set('version', String(version));
  body.set('file', new File([data], 'ficticio.png', { type: 'image/png' }));
  return post(path + '/file', token, body, status);
}
function ledger(key) {
  assert.match(key, /^[a-f0-9-]{36}$/);
  return Number(sql(`select count(*) from content_runtime.operations where operation_id='${key}'`));
}
function budget() {
  return sql('select used_rows,used_bytes from evaluation_runtime.quotas where institution_id=1');
}
function contentSnapshot(database = 'chemicallab_t12_runtime_test') {
  assert(['chemicallab_t12_runtime_test', 'chemicallab_t12_content_recovered_test'].includes(database));
  const tables = ['resources','revisions','files','deliveries','recipients','operations','faculties'];
  return Object.fromEntries(tables.map(table => {
    const statement = `select count(*)||':'||encode(sha256(convert_to(coalesce(string_agg(row::text,E'\\n'
      order by row::text),''),'UTF8')),'hex') from content_runtime.${table} data
      cross join lateral (select to_jsonb(data) row) digest`;
    const result = execFileSync(psql, ['-XAtq', '-v', 'ON_ERROR_STOP=1', '-h', '127.0.0.1',
      '-p', port, '-U', 't12_runtime_local', '-d', database, '-c', statement], { encoding: 'utf8' });
    return [table, result.trim()];
  }));
}
async function waitFor(name) {
  const end = Date.now() + 110000;
  while (!existsSync(join(run, name))) {
    assert(Date.now() < end, name + ' did not appear');
    await pause(300);
  }
}
function evaluationSnapshot() {
  const tables = ['resources','revisions','deliveries','recipients','attempts','operations','memberships','roles',
    'teaching','enrollments','faculties','quotas'];
  return Object.fromEntries(tables.map(table => [table, sql(`select count(*)||':'||
    encode(sha256(convert_to(coalesce(string_agg(row::text,E'\\n' order by row::text),''),'UTF8')),'hex')
    from evaluation_runtime.${table} data cross join lateral (select to_jsonb(data) row) digest`)]));
}
async function verifyUpgrade(pupil, teacher, classA) {
  const { a, b } = read('browser/assignments.json');
  const base = `/institutions/1/classes/${classA}`;
  const first = (await get(base + '/evaluation-catalog/NEW/v1/own-deliveries', pupil)).items
    .find(row => row.deliveryId === a.delivery);
  assert(first);
  const history = `${base}/evaluation-catalog/NEW/v1/own-deliveries/${a.delivery}/attempts`;
  const attempt = (await get(history, pupil)).items[0];
  const correction = `${base}/evaluation-execution/NEW/v1/evaluations/${a.evaluation}`
    + `/deliveries/${a.delivery}/revisions/${first.revisionId}/own-attempts/${attempt.attemptId}/correction`;
  const grade = await get(correction, pupil);
  assert.equal(grade.finalScore, 20);
  const revisions = await get(base + '/evaluation-catalog/NEW/v1/revisions', teacher);
  const before = evaluationSnapshot();
  assert(before.operations.split(':')[0] > 0 && before.attempts.split(':')[0] > 0 && b.delivery !== a.delivery);
  writeFileSync(join(run, 'evaluation-before-upgrade.json'), JSON.stringify(before, null, 2));
  writeFileSync(join(run, 'browser/upgrade'), '');
  await waitFor('upgraded.pid');
  const after = evaluationSnapshot();
  writeFileSync(join(run, 'evaluation-after-upgrade.json'), JSON.stringify(after, null, 2));
  assert.deepEqual(after, before);
  assert.deepEqual(await get(correction, pupil), grade);
  assert.deepEqual(await get(base + '/evaluation-catalog/NEW/v1/revisions', teacher), revisions);
  await get(base + '/evaluation-catalog/NEW/v1/revisions', pupil, 403);
  checked('J poblado: huellas y conteos de evaluaciones, intentos, recibos, cuotas y permisos idénticos; nota y permisos conservados por HTTP');
}

assert.equal(sql("select current_database()||':'||host(inet_server_addr())"), 'chemicallab_t12_runtime_test:127.0.0.1');
const [ta, tb, pupil] = await Promise.all([login(ready.teacherA), login(ready.teacherB), login(ready.student)]);
const contexts = (await get('/institutions/evaluation-catalog/NEW/v1/classes', pupil)).items;
const classA = contexts.find(c => c.institutionId === 1 && c.className.includes('CHEM')).classId;
const classA2 = contexts.find(c => c.institutionId === 1 && c.className.includes('PHYS')).classId;
const classB = contexts.find(c => c.institutionId === 2).classId;
const root = (i, c) => `/institutions/${i}/classes/${c}/contents/NEW/v1`;
const [a, a2, b] = [root(1, classA), root(1, classA2), root(2, classB)];
if (mode === 'upgrade') await verifyUpgrade(pupil, ta, classA);
assert.deepEqual(await get('/institutions/content-runtime', ta), { enabled: true });
const page = await get('/institutions/content-catalog/NEW/v1/classes?limit=1', pupil);
assert.equal(page.items.length, 1);
assert.equal((await get('/institutions/content-catalog/NEW/v1/classes?after=' + page.nextAfter, pupil)).items.length, 2);
await get(root(1, classB) + '/assigned', pupil, 403);
await get(root(2, classA) + '/assigned', pupil, 403);
await get(library(b), ta, 403);
await get(library(a), tb, 403);
await get(library(a), pupil, 403);
await post(library(b), ta, operation({ text: { title: 'Ajeno', category: 'Q' } }), 403);
await get('/concepts/teacher', ta, 403);
await get('/concepts/student', pupil, 403);
await get('/concepts/1/materials', pupil, 403);
await get('/concepts/1/materials/1/download', pupil, 403);
await get('/concepts/1/materials/1/download', null, 401);
sqlRejected("update concept_contents set active=false where false", /institutional content contract/);
sqlRejected("delete from concept_materials where false", /institutional content contract/);
checked('Selector paginado, ámbitos cruzados, API anterior y escrituras legacy bloqueados');

const draft = await create(a, ta, 'Contenido verificación A');
const draftB = await create(b, tb, 'Contenido verificación B');
const pa = revision(a, draft);
const pb = revision(b, draftB);
const uploaded = await upload(pa, ta, png(10), draft.version);
const uploadedB = await upload(pb, tb, png(75), draftB.version);
const fileA = uploaded.file.id;
const binaryA = pa + '/files/' + fileA;
const original = await http('GET', binaryA + '?preview=true', ta, null, 200, true);
assert.equal(original.headers.get('content-type'), 'image/png');
assert.match(original.headers.get('content-disposition'), /^inline;/);
assert.match(original.headers.get('cache-control'), /no-store/);
assert.equal(original.headers.get('x-content-type-options'), 'nosniff');
assert.equal(original.headers.get('x-frame-options'), 'DENY');
assert.equal(Number(original.headers.get('content-length')), original.data.length);
await get(binaryA, null, 401);
await get(binaryA, pupil, 403);
await get(pb + '/files/' + fileA, tb, 403);
await get(pa + '/files/' + uploadedB.file.id, ta, 403);
const beforeRejection = budget();
const invalidKey = randomUUID();
await upload(pa, ta, Buffer.from('ficticio'), uploaded.version, invalidKey, 400);
assert.equal(ledger(invalidKey), 0);
assert.equal(budget(), beforeRejection);
assert.deepEqual((await http('GET', binaryA, ta, null, 200, true)).data, original.data);
checked('T06 validó PNG, controles de entrega y reemplazo rechazado sin cambiar archivo, recibo ni cuota');

const onlyOnce = transition(uploaded.version);
const published = await Promise.all([post(pa + '/publish', ta, onlyOnce), post(pa + '/publish', ta, onlyOnce)]);
assert.deepEqual(published[0], published[1]);
assert.equal(ledger(onlyOnce.operationId), 1);
sqlRejected(`update content_runtime.revisions set payload='{}' where id=${draft.revisionId}`, /pending command/);
await post(pa + '/publish', ta, transition(uploaded.version), 409);
await post(pa + '/publish', ta, transition(uploaded.version + 1, onlyOnce.operationId), 409);
const publishB = await post(pb + '/publish', tb, transition(uploadedB.version));
const deliveryBody = transition(published[0].version);
const delivered = await Promise.all([post(pa + '/assign', ta, deliveryBody), post(pa + '/assign', ta, deliveryBody)]);
assert.deepEqual(delivered[0], delivered[1]);
const deliverB = await post(pb + '/assign', tb, transition(publishB.version));
const studentA = assigned(a, delivered[0]);
const studentB = assigned(b, deliverB);
const detailA = await get(studentA, pupil);
assert.equal(detailA.materialCount, 2);
assert.equal(detailA.text.links[0].title, 'Lectura');
assert.deepEqual((await http('GET', studentA + '/files/' + fileA, pupil, null, 200, true)).data, original.data);
await get(studentA + '/files/' + uploadedB.file.id, pupil, 403);
await get(assigned(a, deliverB), pupil, 403);
await get(assigned(b, delivered[0]), pupil, 403);
await get(studentB, ta, 403);
assert((await get(a + '/assigned', pupil)).items.some(item => item.deliveryId === delivered[0].deliveryId));
assert(!(await get(a + '/assigned', pupil)).items.some(item => item.contentId === draftB.contentId));
checked('Publicación y asignación simultáneas recuperan un recibo; enlaces, conteo y binarios requieren la entrega exacta');

const next = await post(pa + '/successor', ta, transition(published[0].version));
assert.equal(next.file.id, fileA);
const successor = revision(a, next);
const firstReplacement = await upload(successor, ta, png(110), next.version);
const readyNext = await upload(successor, ta, png(150), firstReplacement.version);
assert.equal(sql(`select count(*) from content_runtime.files where id=${firstReplacement.file.id}`), '0');
assert.notEqual(readyNext.file.id, fileA);
assert.deepEqual((await http('GET', studentA + '/files/' + fileA, pupil, null, 200, true)).data, original.data);
const edited = await http('PUT', successor, ta, operation({ version: readyNext.version,
  text: { title: 'Revisión sucesora', category: 'Química', explanation: 'Texto posterior', links: [] } }));
assert.equal((await get(studentA, pupil)).text.title, 'Contenido verificación A');
assert.equal((await get(studentA, pupil)).materialCount, 2);
const counter = budget();
const auditFail = transition(edited.version);
sql(`create function public.content_test_fail() returns trigger language plpgsql as $$begin
  if new.action='CONTENT_PUBLISH' then raise exception 'controlled audit failure'; end if; return new; end$$;
  create trigger content_test_fail before insert on system_logs for each row execute function public.content_test_fail()`);
try {
  await post(successor + '/publish', ta, auditFail, 503);
  assert.equal(budget(), counter);
  assert.equal(ledger(auditFail.operationId), 0);
  assert.equal((await get(successor, ta)).status, 'DRAFT');
} finally {
  sql('drop trigger content_test_fail on system_logs; drop function public.content_test_fail()');
}
sql('update evaluation_runtime.quotas set max_rows=used_rows where institution_id=1');
try {
  await post(successor + '/publish', ta, auditFail, 503);
  assert.equal(budget(), counter);
  assert.equal(ledger(auditFail.operationId), 0);
} finally {
  sql('update evaluation_runtime.quotas set max_rows=10000 where institution_id=1');
}
const second = await post(successor + '/publish', ta, auditFail);
assert.equal(second.status, 'PUBLISHED');
assert.equal(ledger(auditFail.operationId), 1);
checked('Sucesor conserva revisión y archivo publicados; fallos de auditoría y cuota revierten negocio, consumo y recibo');

const authority = sql(`insert into evaluation_runtime.roles(membership_id,institution_id,role,valid_from)
  select m.id,1,'ADMIN_INSTITUCION',now()-interval '1 minute'
  from evaluation_runtime.memberships m join user_accounts u on u.id=m.account_id
  where m.institution_id=1 and u.username='runtime-teacher-a' returning membership_id`);
const delegate = sql(`insert into evaluation_runtime.memberships(institution_id,account_id,valid_from)
  select 1,id,now()-interval '1 minute' from user_accounts where username='runtime-teacher-b' returning id`);
sql(`insert into evaluation_runtime.roles(membership_id,institution_id,role,valid_from)
     values (${delegate},1,'DOCENTE',now()-interval '1 minute')`);
const delegated = await create(a, ta, 'Delegación A');
const delegatePath = revision(a, delegated);
function grant(faculty, id, interval) {
  sql(`insert into content_runtime.faculties(resource_id,institution_id,membership_id,faculty,granted_by_membership_id,valid_from,valid_until)
    values (${id},1,${delegate},'${faculty}',${authority},now()-interval '2 minutes',now()+interval '${interval}')`);
}
grant('PUBLISH', delegated.contentId, '1 hour');
grant('EDIT_DRAFT', delegated.contentId, '-1 minute');
const choices = await get('/institutions/content-catalog/NEW/v1/classes', tb);
assert(choices.items.some(c => c.classId === classA && c.teacher && !c.canCreate));
const delegateRows = (await get(library(a), tb)).items;
assert(delegateRows.some(row => row.contentId === delegated.contentId && row.canPublish && !row.canEdit && !row.canAssign));
await get(delegatePath, tb, 403);
await http('PUT', delegatePath, tb, operation({ version: delegated.version, text: { title: 'No', category: 'Q' } }), 403);
await upload(delegatePath, tb, png(12), delegated.version, randomUUID(), 403);
const delegatePublish = transition(delegated.version);
const publishedDelegate = await post(delegatePath + '/publish', tb, delegatePublish);
grant('ASSIGN', delegated.contentId, '1 hour');
await post(delegatePath + '/assign', tb, transition(publishedDelegate.version), 403);
sql(`insert into evaluation_runtime.teaching(institution_id,class_id,membership_id,faculty,valid_from,valid_until)
  values(1,${classA2},${delegate},'CO_DOCENTE',now()-interval '1 minute',now()+interval '1 hour')`);
const assignDelegate = transition(publishedDelegate.version);
await post(revision(a2, delegated) + '/assign', tb, assignDelegate);
await post(revision(a2, delegated) + '/successor', tb, transition(publishedDelegate.version), 403);
sql(`update content_runtime.faculties set revoked_at=now() where resource_id=${delegated.contentId} and faculty='PUBLISH'`);
await post(delegatePath + '/publish', tb, delegatePublish, 403);
sql(`update evaluation_runtime.teaching set ended_at=now() where class_id=${classA2} and membership_id=${delegate}`);
await post(revision(a2, delegated) + '/assign', tb, assignDelegate, 403);
checked('Facultades separadas, grant vencido y revocado, y docencia obligatoria para ASSIGN incluso al repetir');

writeFileSync(join(run, 'browser/restart'), '');
await waitFor('restarted.pid');
assert.deepEqual(await post(pa + '/assign', ta, deliveryBody), delivered[0]);
assert.deepEqual(await get(studentA, pupil), detailA);
assert.deepEqual((await http('GET', studentA + '/files/' + fileA, pupil, null, 200, true)).data, original.data);
sql(`update evaluation_runtime.enrollments set ended_at=now() where class_id=${classA}`);
await get(studentA + '/files/' + fileA, pupil, 403);
assert.equal((await get(studentB, pupil)).contentId, draftB.contentId);
checked('Reinicio preservó recibo, entrega y bytes; baja de episodio en A mantiene B');
if (mode === 'fresh') {
  const before = contentSnapshot();
  writeFileSync(join(run, 'browser/recover'), '');
  await waitFor('recovered.pid');
  const after = contentSnapshot('chemicallab_t12_content_recovered_test');
  assert.deepEqual(after, before);
  writeFileSync(join(run, 'recovery-content-tables.json'), JSON.stringify({ before, after }, null, 2));
  assert.deepEqual(await post(pa + '/assign', ta, deliveryBody), delivered[0]);
  await get(studentA + '/files/' + fileA, pupil, 403);
  const restored = await http('GET', studentB + '/files/' + uploadedB.file.id, pupil, null, 200, true);
  assert.equal(restored.headers.get('content-type'), 'image/png');
  checked('Respaldo y restauración en otra base: tablas, archivos, baja de episodio y recibo recuperados');
}
