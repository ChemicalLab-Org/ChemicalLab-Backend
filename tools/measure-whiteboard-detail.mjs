import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { crc32, deflateSync } from 'node:zlib';

const [run, psql, port, output, option] = process.argv.slice(2);
assert(run && psql && /^\d{4,5}$/.test(port) && output,
  'Pass the disposable -Boards run directory, psql, database port and output JSON.');
assert(option === undefined || option === '--read-prepared');
const prepared = option === '--read-prepared';
const ready = JSON.parse(readFileSync(join(run, 'browser', 'ready.json'), 'utf8'));
const origin = new URL(ready.origin);
assert.equal(origin.hostname, '127.0.0.1');
assert.equal(ready.mode, 'runtime');
const database = 'chemicallab_t12_runtime_test';
const log = join(run, 'postgres.log');
const rosterSize = 20;
const perWindow = 8;
const repetitions = 5;

function sql(query) {
  return execFileSync(psql, ['-h', '127.0.0.1', '-p', port, '-U', 't12_runtime_local', '-d', database, '-XqtA',
    '-v', 'ON_ERROR_STOP=1', '-c', query], { encoding: 'utf8', windowsHide: true }).trim();
}

assert.equal(sql("select current_database()||'/'||host(inet_server_addr())"), database + '/127.0.0.1');
assert.equal(sql('select version from board_runtime.contract'), '1');
assert.equal(sql("select count(*) from user_accounts where username like 'green-roster-%'"),
  prepared ? String(rosterSize) : '0', 'Start a fresh disposable run for each variant.');
const ids = JSON.parse(sql(`select json_build_array(i.id,c.id) from evaluation_runtime.institutions i
  join evaluation_runtime.classes c on c.institution_id=i.id where i.name='Fictitious A' and c.code='CHEM'`));

// Seed only identities and class rights. Create boards, admissions and the snapshot through the normal API.
if (!prepared) sql(`
  insert into user_accounts(username,password,role,active,temporary_password,credentials_version,created_at)
  select 'green-roster-'||n,u.password,'ESTUDIANTE',true,false,0,statement_timestamp()
  from generate_series(1,${rosterSize}) n cross join user_accounts u where u.username='runtime-student';
  insert into student_profiles(user_id,teacher_id,student_code,names,last_names,grade,section)
  select u.id,t.id,'GREEN'||u.id,'Alumno ficticio',split_part(u.username,'-',3),'3','A'
  from user_accounts u cross join teacher_profiles t join user_accounts a on a.id=t.user_id
  where u.username like 'green-roster-%' and a.username='runtime-teacher-a';
  insert into evaluation_runtime.memberships(institution_id,account_id,valid_from)
  select ${ids[0]},u.id,statement_timestamp()-interval '1 minute' from user_accounts u where u.username like 'green-roster-%';
  insert into evaluation_runtime.roles(membership_id,institution_id,role,valid_from)
  select m.id,m.institution_id,'ESTUDIANTE',statement_timestamp()-interval '1 minute'
  from evaluation_runtime.memberships m join user_accounts u on u.id=m.account_id where u.username like 'green-roster-%';
  insert into evaluation_runtime.enrollments(institution_id,class_id,membership_id,account_id,valid_from,valid_until)
  select m.institution_id,${ids[1]},m.id,m.account_id,statement_timestamp()-interval '1 minute',statement_timestamp()+interval '1 day'
  from evaluation_runtime.memberships m join user_accounts u on u.id=m.account_id where u.username like 'green-roster-%';
`);

async function http(path, token, method = 'GET', body) {
  const headers = { Connection: 'close' };
  if (token) headers.Authorization = 'Bearer ' + token;
  if (method !== 'GET') headers['Idempotency-Key'] = randomUUID();
  if (body && !(body instanceof FormData)) {
    headers['Content-Type'] = 'application/json';
    body = JSON.stringify(body);
  }
  const start = performance.now();
  const response = await fetch(new URL(path, origin), { method, headers, body, signal: AbortSignal.timeout(30000) });
  const bytes = Buffer.from(await response.arrayBuffer());
  const ms = performance.now() - start;
  assert.equal(response.status, 200, method + ' ' + path + ' · ' + response.status);
  return { bytes, ms };
}

const json = result => JSON.parse(result.bytes);
const api = `/api/institutions/${ids[0]}/classes/${ids[1]}/whiteboards/v1`;
const login = async usernameOrEmail => json(await http('/api/auth/login', null, 'POST',
  { usernameOrEmail, password: ready.password })).token;
const teacher = await login(ready.teacherA);

function syntheticPng(width, height) {
  const chunk = (type, data) => {
    const payload = Buffer.concat([Buffer.from(type), data]);
    const buffer = Buffer.alloc(data.length + 12);
    buffer.writeUInt32BE(data.length);
    payload.copy(buffer, 4);
    buffer.writeUInt32BE(crc32(payload), data.length + 8);
    return buffer;
  };
  const header = Buffer.alloc(13);
  header.writeUInt32BE(width, 0);
  header.writeUInt32BE(height, 4);
  header[8] = 8;
  header[9] = 2;
  const stride = 1 + width * 3;
  const pixels = Buffer.alloc(stride * height);
  let value = 271828;
  for (let row = 0; row < height; row++) {
    for (let i = 1; i < stride; i++) {
      value ^= value << 13;
      value ^= value >>> 17;
      value ^= value << 5;
      pixels[row * stride + i] = value & 255;
    }
  }
  return Buffer.concat([Buffer.from('89504e470d0a1a0a', 'hex'), chunk('IHDR', header),
    chunk('IDAT', deflateSync(pixels)), chunk('IEND', Buffer.alloc(0))]);
}

const names = { empty: 'Medición sin participantes', full: 'Medición participantes' };
const boardId = async name => prepared
  ? Number(sql(`select id from whiteboard_sessions
      where name=convert_from(decode('${Buffer.from(name).toString('hex')}','hex'),'UTF8')`))
  : json(await http(api + '/teacher', teacher, 'POST', { name })).id;
const empty = await boardId(names.empty);
const full = await boardId(names.full);
assert(empty > 0 && full > 0);
if (!prepared) {
  for (let i = 1; i <= rosterSize; i++) {
    const token = await login('green-roster-' + i);
    await http(`${api}/student/${full}/join`, token, 'POST', {});
  }
  const form = new FormData();
  form.append('snapshot', new Blob([syntheticPng(360, 360)], { type: 'image/png' }), 'fictitious.png');
  await http(`${api}/teacher/${full}/close?revision=0`, teacher, 'POST', form);
}
const download = await http(`${api}/teacher/${full}/snapshot`, teacher);
const snapshotSha256 = createHash('sha256').update(download.bytes).digest('hex');
const snapshotBytes = download.bytes.length;
assert.equal(snapshotSha256, sql(`select encode(digest(final_snapshot_data,'sha256'),'hex')
  from whiteboard_sessions where id=${full}`), 'Stored snapshot must download byte for byte');

const bare = `${api}/teacher/${empty}`;
const closed = `${api}/teacher/${full}`;
const reference = json(await http(closed, teacher));
assert.equal(reference.session.participantCount, rosterSize);
assert.equal(reference.session.snapshotAvailable, true);
assert.equal(reference.session.status, 'CLOSED');
assert.equal(json(await http(bare, teacher)).session.snapshotAvailable, false);
assert.deepEqual(json(await http(closed + '/participants', teacher)), reference.participants);

const cases = [
  { name: 'empty-detail', path: bare },
  { name: 'closed-detail', path: closed },
  { name: 'closed-roster', path: closed + '/participants' },
];
for (const item of cases) {
  item.expected = (await http(item.path, teacher)).bytes;
  for (let i = 0; i < 5; i++) await http(item.path, teacher);
}
// Respect the default per-account REST allowance (five per second, burst of 30).
// Refill and instrumentation waits occur outside the measured window.
await delay(6500);

function processes() {
  if (process.platform !== 'win32') return {};
  const dbPids = sql("select pid from pg_stat_activity where application_name='PostgreSQL JDBC Driver'").split(/\s+/)
    .filter(Boolean).map(Number);
  const ids = [Number(ready.pid), ...dbPids];
  assert(ids.every(Number.isSafeInteger));
  const data = execFileSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command',
    `ConvertTo-Json -Compress -InputObject @(Get-Process -Id ${ids.join(',')} -ErrorAction SilentlyContinue | ForEach-Object {
      @{id=$_.Id; cpuMs=$_.TotalProcessorTime.TotalMilliseconds; rssBytes=$_.WorkingSet64}
    })`], { encoding: 'utf8', windowsHide: true });
  return Object.fromEntries(JSON.parse(data).map(row => [row.id, row]));
}

function processDelta(before, after, ids) {
  const matching = ids.filter(id => before[id] && after[id]);
  if (!matching.length) return null;
  return {
    cpuMs: matching.reduce((sum, id) => sum + after[id].cpuMs - before[id].cpuMs, 0),
    rssStartBytes: matching.reduce((sum, id) => sum + before[id].rssBytes, 0),
    rssEndBytes: matching.reduce((sum, id) => sum + after[id].rssBytes, 0),
    processes: matching.length,
  };
}

const windows = [];
for (let pass = 0; pass < repetitions; pass++) {
  for (let offset = 0; offset < cases.length; offset++) {
    const item = cases[(pass + offset) % cases.length];
    if (windows.length) await delay(1700);
    const before = processes();
    const logStart = readFileSync(log).length;
    const rows = [];
    const start = performance.now();
    for (let i = 0; i < perWindow; i++) rows.push(await http(item.path, teacher));
    const wallMs = performance.now() - start;
    const logWindow = readFileSync(log).subarray(logStart).toString();
    const after = processes();
    for (const row of rows) assert.deepEqual(row.bytes, item.expected, item.name + ' response changed');
    const statements = [...logWindow.matchAll(/duration: ([\d.]+) ms\s+(?:execute [^:]*|statement): ([^\r\n]*)/g)];
    assert(statements.length > 0, 'Expected top-level SQL executions');
    const sqlCount = pattern => statements.filter(row => pattern.test(row[2])).length;
    const snapshotSelections = sqlCount(/^select .*\.final_snapshot_data\s*,/i);
    windows.push({
      name: item.name, pass: pass + 1, httpRequests: rows.length, messages: 0,
      httpBodyBytes: rows.reduce((sum, row) => sum + row.bytes.length, 0),
      httpMs: rows.map(row => row.ms), wallMs, sqlExecutions: statements.length,
      sqlExecutionMs: statements.reduce((sum, row) => sum + Number(row[1]), 0),
      studentProfileSelects: sqlCount(/^select .* from student_profiles\b/i),
      snapshotSelections, logicalSnapshotBytesSelected: item.name === 'empty-detail' ? 0 : snapshotSelections * snapshotBytes,
      java: processDelta(before, after, [ready.pid]),
      postgresClientBackends: processDelta(before, after, Object.keys(before).filter(id => Number(id) !== ready.pid)),
    });
  }
}

writeFileSync(output, JSON.stringify({ node: process.version, platform: process.platform, perWindow, repetitions, rosterSize,
  snapshotBytes, snapshotSha256, windows }, null, 2) + '\n');
