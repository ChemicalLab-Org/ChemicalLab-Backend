import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { setTimeout as pause } from 'node:timers/promises';

const [run, pgBin, port] = process.argv.slice(2);
assert(run && pgBin && /^\d{4,5}$/.test(port));
const ready = JSON.parse(readFileSync(join(run, 'browser', 'ready.json'), 'utf8'));
const origin = new URL(ready.origin);
assert.equal(origin.hostname, '127.0.0.1');
const source = 'chemicallab_t12_runtime_test';
const historical = 'chemicallab_t12_board_history_' + randomUUID().slice(0, 8);
const dbFlags = ['-h', '127.0.0.1', '-p', port, '-U', 't12_runtime_local'];
function pg(program, args) {
  return execFileSync(join(pgBin, program + '.exe'), [...dbFlags, ...args], {
    encoding: 'utf8', windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'],
  }).trim();
}
function sql(query, database = source) {
  return pg('psql', ['-d', database, '-XqtA', '-v', 'ON_ERROR_STOP=1', '-c', query]);
}
assert.equal(sql("select current_database()||'/'||host(inet_server_addr())"), source + '/127.0.0.1');

function fingerprint() {
  const tables = sql(`select schemaname||'.'||tablename from pg_tables
    where schemaname in ('evaluation_runtime','content_runtime') order by 1`).split(/\r?\n/);
  for (const table of tables) assert.match(table, /^(evaluation|content)_runtime\.[a-z_]+$/);
  const members = tables.map(table => [
    table, sql(`select json_build_array(count(*),md5(coalesce(string_agg(to_jsonb(t)::text, '' order by to_jsonb(t)::text),''))) from ${table} t`),
  ]);
  const audit = sql(`select json_build_array(count(*),md5(coalesce(string_agg(to_jsonb(l)::text,'' order by l.id),'')))
    from system_logs l where l.id in (select audit_id from evaluation_runtime.operations
    union select audit_id from content_runtime.operations)`);
  return Object.fromEntries([...members, ['academic_audit', audit]].map(([key, value]) => [key, JSON.parse(value)]));
}

const before = fingerprint();
assert(before['evaluation_runtime.attempts'][0] > 0, 'Create and grade an attempt before extending J+K.');
assert(before['content_runtime.files'][0] > 0, 'Upload and publish a native content file before extending J+K.');
assert(before['content_runtime.operations'][0] > 0);
assert(before['evaluation_runtime.operations'][0] > 0);
writeFileSync(join(run, 'jk-before-boards.json'), JSON.stringify(before, null, 2));

async function api(path, token, payload) {
  const response = await fetch(new URL('/api' + path, origin), {
    method: payload ? 'POST' : 'GET',
    headers: token ? { Authorization: 'Bearer ' + token } : { 'Content-Type': 'application/json' },
    body: payload ? JSON.stringify(payload) : undefined,
  });
  assert.equal(response.status, 200, path);
  return response.headers.get('content-type')?.startsWith('image/')
    ? createHash('sha256').update(Buffer.from(await response.arrayBuffer())).digest('hex')
    : response.json();
}
const token = (await api('/auth/login', null, { usernameOrEmail: ready.student, password: ready.password })).token;
const { a } = JSON.parse(readFileSync(join(run, 'browser', 'assignments.json'), 'utf8'));
const [institution, clazz] = JSON.parse(sql(`select json_build_array(institution_id,class_id)
  from evaluation_runtime.deliveries where id=${Number(a.delivery)}`));
const scope = `/institutions/${institution}/classes/${clazz}`;
const own = scope + '/evaluation-catalog/NEW/v1/own-deliveries';
const evaluation = (await api(own, token)).items.find(item => item.deliveryId === a.delivery);
const attempt = (await api(own + '/' + a.delivery + '/attempts', token)).items[0];
const correction = scope + `/evaluation-execution/NEW/v1/evaluations/${a.evaluation}/deliveries/${a.delivery}`
  + `/revisions/${evaluation.revisionId}/own-attempts/${attempt.attemptId}/correction`;
const materials = scope + '/contents/NEW/v1/assigned';
const item = (await api(materials, token)).items[0];
const content = materials + `/${item.deliveryId}/contents/${item.contentId}/revisions/${item.revisionId}`;
const baseline = { correction: await api(correction, token), content: await api(content, token) };
assert.equal(baseline.correction.finalScore, 20);
const file = content + '/files/' + baseline.content.file.id;
baseline.image = await api(file, token);
assert.match(baseline.image, /^[a-f0-9]{64}$/);

// Prove that an ambiguous historical board in an independent copy prevents adoption.
const dump = resolve(run, historical + '.dump');
pg('pg_dump', ['-d', source, '-Fc', '-f', dump]);
pg('createdb', [historical]);
pg('pg_restore', ['-d', historical, '--exit-on-error', dump]);
sql(`insert into whiteboard_sessions(name,teacher_id,grade,section,status,interaction_enabled,state_revision,final_snapshot_data,
      final_snapshot_size,final_snapshot_content_type,final_snapshot_file_name)
    select 'Pizarra historica ficticia',min(id),'3','A','CLOSED',false,0,convert_to('snapshot original','UTF8'),
      octet_length('snapshot original'),'image/png','original.png' from teacher_profiles`, historical);
const oldBoard = sql("select to_jsonb(b)::text from whiteboard_sessions b where b.name='Pizarra historica ficticia'", historical);
const reject = spawnSync(join(pgBin, 'psql.exe'), [...dbFlags, '-d', historical, '-X', '-v', 'ON_ERROR_STOP=1',
  '-f', resolve('src/main/resources/db/runtime/board-v1.sql')], { encoding: 'utf8', windowsHide: true });
assert.notEqual(reject.status, 0);
assert.match(reject.stderr, /Historical boards require approved F08 mapping/);
assert.equal(sql("select to_jsonb(b)::text from whiteboard_sessions b where b.name='Pizarra historica ficticia'", historical), oldBoard);
assert.equal(sql("select to_regnamespace('board_runtime') is null", historical), 't');
writeFileSync(join(run, 'board-historical-rejection.log'), reject.stdout + reject.stderr);

writeFileSync(join(run, 'browser', 'upgrade'), '');
const end = Date.now() + 100000;
while (!existsSync(join(run, 'upgraded.pid')) && Date.now() < end) await pause(250);
assert(existsSync(join(run, 'upgraded.pid')), 'Upgrade did not finish.');
assert.equal(sql('select version from board_runtime.contract'), '1');
const after = fingerprint();
assert.deepEqual(after, before);
writeFileSync(join(run, 'jk-after-boards.json'), JSON.stringify(after, null, 2));
assert.deepEqual(await api(correction, token), baseline.correction);
assert.deepEqual(await api(content, token), baseline.content);
assert.equal(await api(file, token), baseline.image);
writeFileSync(join(run, 'jk-http-regression.json'), JSON.stringify({
  grade: baseline.correction.finalScore, evaluationId: a.evaluation, contentId: item.contentId, imageSha256: baseline.image,
}, null, 2));
console.log(`Verified ${Object.keys(after).length} J+K table and audit fingerprints and the historical refusal.`);
