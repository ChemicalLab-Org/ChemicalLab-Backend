import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';

const [, psql, dbPort, httpPort, output, size = '3', rounds = '8'] = process.argv.slice(2);
const students = Number(size), steps = Number(rounds);
assert(/^\d{4,5}$/.test(dbPort) && /^\d{4,5}$/.test(httpPort));
assert(Number.isInteger(students) && students >= 3 && students <= 30);
assert(Number.isInteger(steps) && steps >= 4 && steps <= 45);
const origin = `http://127.0.0.1:${httpPort}`;
const channels = new Set();
const changes = [];
const answerKeys = [];
const outcomes = [];
const warmup = [];
let setupCalls = 0;
let failedSetupRequest;
const checks = [];
const planned = steps * 8 + 2;
const timeoutMs = 2000;
let phase = 'setup';
let started, ended, failure;

function sql(query) {
  return execFileSync(psql, ['-h', '127.0.0.1', '-p', dbPort, '-U', 't12_runtime_local',
    '-d', 'chemicallab_t12_runtime_test', '-XqtA', '-v', 'ON_ERROR_STOP=1', '-c', query],
  { encoding: 'utf8', windowsHide: true }).trim();
}

function command(fields = {}) { return { operationId: randomUUID(), ...fields }; }

async function measured(kind, work) {
  const began = performance.now();
  const record = { kind, at: Date.now() };
  (phase === 'warmup' ? warmup : outcomes).push(record);
  try {
    const result = await work(record);
    record.outcome = 'ok';
    return result;
  } catch (error) {
    record.outcome = error.name === 'TimeoutError' ? 'timedOut' : 'failed';
    record.reason = error.name === 'AssertionError' ? error.message : error.name;
    return undefined;
  } finally {
    record.ms = performance.now() - began;
  }
}

async function request(path, token, body, expected = 200, record) {
  const method = body === undefined ? 'GET' : 'POST';
  if (phase === 'setup') setupCalls++;
  try {
    const response = await fetch(`${origin}/api${path}`, {
      method, signal: AbortSignal.timeout(timeoutMs),
      headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...(method === 'POST' ? { 'Content-Type': 'application/json', 'Idempotency-Key': randomUUID() } : {}) },
      body: method === 'POST' ? JSON.stringify(body) : undefined,
    });
    const data = await response.text();
    if (record) record.http = response.status;
    assert.equal(response.status, expected, `${method} ${path}, expected ${expected}, received ${response.status}`);
    return data ? JSON.parse(data) : null;
  } catch (error) {
    if (phase === 'setup') failedSetupRequest = { method, path, error: error.name };
    throw error;
  }
}

const login = async name => (await request('/auth/login', null, {
  usernameOrEmail: name, password: 'FictitiousRuntime-2026!',
})).token;

async function until(predicate, name, timeout = timeoutMs) {
  const deadline = performance.now() + timeout;
  while (!predicate()) {
    if (performance.now() >= deadline) throw new DOMException(name, 'TimeoutError');
    await delay(10);
  }
}

function connect(token, ids, board, role) {
  const target = `/institutions/${ids[0]}/classes/${ids[1]}/whiteboards/v1/${board}`;
  const socket = new WebSocket(`${origin.replace('http:', 'ws:')}/ws/000/${randomUUID()}/websocket`);
  const state = { ready: false, closed: null, frames: new Map(), close: () => socket.close() };
  channels.add(state);
  function send(kind, headers, body = '') {
    const fields = Object.entries(headers).map(([name, value]) => `${name}:${value}`).join('\n');
    socket.send(JSON.stringify([`${kind}\n${fields}\n\n${body}\0`]));
  }
  state.draw = id => send('SEND', { destination: `/app${target}/draw`, 'content-type': 'application/json',
    'client-event-id': id }, JSON.stringify({ eventType: 'SHAPE', tool: 'RECTANGLE', shapeId: `load-${id}`,
    clientEventId: id, points: [{ x: 6, y: 8 }, { x: 31, y: 41 }], color: '#224466', strokeWidth: 4 }));
  socket.addEventListener('close', ({ code }) => { state.closed ??= code; });
  socket.addEventListener('error', () => { state.closed ??= 1006; });
  socket.addEventListener('message', ({ data }) => {
    if (data === 'o') send('CONNECT', { 'accept-version': '1.2', 'heart-beat': '10000,10000',
      Authorization: `Bearer ${token}`, 'board-scope': [...ids, board, role].join('/') });
    if (data.startsWith('c')) state.closed = JSON.parse(data.slice(1))[0];
    if (!data.startsWith('a')) return;
    for (const frame of JSON.parse(data.slice(1))) {
      if (frame.startsWith('CONNECTED')) {
        send('SUBSCRIBE', { destination: '/user/queue/whiteboard-errors', id: 'errors', ack: 'auto' });
        send('SUBSCRIBE', { destination: `/topic${target}`, id: 'board', ack: 'auto' });
        send('SEND', { destination: `/app${target}/ready` });
        // Send STOMP heartbeats while the session is active. SockJS wraps each frame.
        const heartbeat = setInterval(() => {
          if (socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify(['\n']));
        }, 10000);
        socket.addEventListener('close', () => clearInterval(heartbeat), { once: true });
      }
      if (frame.startsWith('MESSAGE')) {
        const body = JSON.parse(frame.slice(frame.indexOf('\n\n') + 2).split('\0')[0]);
        if (body.ready) state.ready = true;
        if (body.clientEventId) state.frames.set(body.clientEventId, body);
      }
    }
  });
  return state;
}

const scope = ids => `/institutions/${ids[0]}/classes/${ids[1]}`;
const boardPath = ids => `${scope(ids)}/whiteboards/v1`;
const content = ids => `${scope(ids)}/contents/NEW/v1`;

async function classroom(ids, teacher, names) {
  const board = (await request(`${boardPath(ids)}/teacher`, teacher, { name: 'Aula ficticia de carga' })).id;
  const tutor = connect(teacher, ids, board, 'teacher');
  await until(() => tutor.ready, 'teacher ready');
  const pupils = [];
  for (const name of names) {
    const token = await login(name);
    await request(`${boardPath(ids)}/student/${board}/join`, token, {});
    const channel = connect(token, ids, board, 'student');
    await until(() => channel.ready, 'student ready');
    pupils.push({ name, token, channel });
  }
  return { ids, teacher, board, tutor, pupils };
}

async function makeAttempt(room, pupil, enrollmentId) {
  const root = `${scope(room.ids)}/evaluation-authoring/NEW/v1/evaluations`;
  const payload = { resource: { title: 'Evaluación ficticia', max_attempts: 2, question_display_mode: 'ALL_AT_ONCE' },
    questions: [{ id: 1, question_text: 'Elige A', question_type: 'MULTIPLE_CHOICE', required: true, points: 1 }],
    options: [{ id: 1, question_id: 1, option_text: 'A', correct: true },
      { id: 2, question_id: 1, option_text: 'B', correct: false }] };
  const { evaluationId, revisionId } = await request(root, room.teacher, command({ payload }));
  const revision = `${root}/${evaluationId}/revisions/${revisionId}`;
  const { version } = await request(`${revision}/publish`, room.teacher, command({ expectedVersion: 1 }));
  const { deliveryId } = await request(`${revision.replace('evaluation-authoring', 'evaluation-assignments')}/deliveries`,
    room.teacher, command({ expectedRevisionVersion: version, enrollmentIds: [enrollmentId],
      startsAt: new Date(Date.now() - 10000).toISOString(), dueAt: new Date(Date.now() + 600000).toISOString() }));
  const execution = `${scope(room.ids)}/evaluation-execution/NEW/v1/evaluations/${evaluationId}/deliveries/${deliveryId}/revisions/${revisionId}`;
  const { attemptId, attemptNumber } = await request(`${execution}/own-attempts`, pupil.token, command());
  assert.equal(attemptNumber, 1);
  return { path: `${execution}/own-attempts/${attemptId}/answers`, revisionId };
}

async function makeContent(room) {
  const library = `${content(room.ids)}/library`;
  const draft = await request(library, room.teacher, command({ text: {
    title: 'Material ficticio', category: 'Química', explanation: 'Contenido sintético de carga',
    links: [{ title: 'Lectura', url: 'https://example.invalid/material' }],
  } }));
  const revision = `${library}/${draft.contentId}/revisions/${draft.revisionId}`;
  const published = await request(`${revision}/publish`, room.teacher, command({ version: draft.version }));
  const delivery = await request(`${revision}/assign`, room.teacher, command({ version: published.version }));
  return `${content(room.ids)}/assigned/${delivery.deliveryId}/contents/${delivery.contentId}/revisions/${delivery.revisionId}`;
}

function draw(room, excluded, foreign) {
  return measured('stomp-draw', async record => {
    const id = randomUUID();
    const expected = [room.tutor, ...room.pupils.filter(pupil => pupil !== excluded).map(pupil => pupil.channel)];
    record.institution = room.ids[0];
    record.expectedRecipients = expected.length;
    record.event = id;
    room.tutor.draw(id);
    try {
      await until(() => {
        const response = room.tutor.frames.get(id);
        if (response?.code && !Number.isSafeInteger(response.revision)) throw new Error(`draw rejected: ${response.code}`);
        assert(expected.every(channel => channel.closed === null), 'an eligible recipient disconnected');
        return expected.every(channel => Number.isSafeInteger(channel.frames.get(id)?.revision));
      }, 'fan-out acknowledgement');
    } finally {
      record.delivered = expected.filter(channel => Number.isSafeInteger(channel.frames.get(id)?.revision)).length;
      record.closed = expected.map(channel => channel.closed);
      record.replyCode = room.tutor.frames.get(id)?.code;
    }
    const revisions = expected.map(channel => channel.frames.get(id).revision);
    assert(revisions.every(revision => revision === revisions[0]));
    assert(!foreign.tutor.frames.has(id) && foreign.pupils.every(pupil => !pupil.channel.frames.has(id)));
    assert(!excluded?.channel.frames.has(id));
    record.revision = revisions[0];
    record.recipients = expected.length;
    changes.push(`'${id}'`);
  });
}

try {
  assert.equal(sql("select current_database()||'/'||host(inet_server_addr())"), 'chemicallab_t12_runtime_test/127.0.0.1');
  const ids = JSON.parse(sql(`select json_object_agg(i.name,json_build_array(i.id,c.id))
    from evaluation_runtime.classes c join evaluation_runtime.institutions i on i.id=c.institution_id where c.code='CHEM'`));
  const a = ids['Fictitious A'], b = ids['Fictitious B'];
  assert(a.every(Number.isSafeInteger) && b.every(Number.isSafeInteger));
  assert.deepEqual(await request('/agreements/public'), []);
  const roster = JSON.parse(sql(`select json_object_agg(u.username,e.id) from user_accounts u
    join evaluation_runtime.enrollments e on e.account_id=u.id where e.class_id=${a[1]} and u.username like 'green-roster-%'`));
  const names = Object.keys(roster).sort((left, right) => left.localeCompare(right, undefined, { numeric: true }));
  assert(names.length >= students, 'Prepare at least the requested synthetic roster');
  const roomA = await classroom(a, await login('runtime-teacher-a'), names.slice(0, students));
  const roomB = await classroom(b, await login('runtime-teacher-b'), ['runtime-student']);
  const revoked = roomA.pupils.at(-1), working = roomA.pupils[0], studentB = roomB.pupils[0];
  const enrollmentB = Number(sql(`select e.id from evaluation_runtime.enrollments e join user_accounts u on u.id=e.account_id
    where e.class_id=${b[1]} and u.username='runtime-student'`));
  const attemptA = await makeAttempt(roomA, working, roster[working.name]);
  const attemptB = await makeAttempt(roomB, studentB, enrollmentB);
  const materialA = await makeContent(roomA), materialB = await makeContent(roomB);
  await request(materialA, working.token);
  await request(materialB, studentB.token);
  await request(materialB, working.token, undefined, 403);
  await request(materialA, roomB.teacher, undefined, 403);
  assert.equal((await request('/subscriptions/v1/institutions', roomB.teacher)).enabled, false);
  await request(`/institutions/${b[0]}/subscription/v1`, roomB.teacher, undefined, 404);
  checks.push('bilateral content denial; agreements and subscriptions disabled');

  function operations(step, reader, excluded) {
    const http = (kind, path, token) => () => measured(kind, record => request(path, token, undefined, 200, record));
    const save = (attempt, pupil) => () => measured('answer-save', async record => {
      const body = command({ answer: { revisionId: attempt.revisionId, questionId: 1, selectedOptionId: 1 + step % 2 } });
      const receipt = await request(attempt.path, pupil.token, body, 200, record);
      assert.equal(receipt.operationId, body.operationId);
      answerKeys.push(`'${body.operationId}'`);
    });
    return [
      [http('board-detail', `${boardPath(a)}/teacher/${roomA.board}`, roomA.teacher),
        http('board-state', `${boardPath(a)}/student/${roomA.board}/state`, reader.token)],
      [http('assigned-content', materialA, reader.token), http('assigned-content', materialB, studentB.token)],
      [save(attemptA, working), save(attemptB, studentB)],
      [() => draw(roomA, excluded, roomB), () => draw(roomB, null, roomA)],
    ];
  }

  phase = 'warmup';
  for (const pair of operations(0, working, null)) {
    // Keep JIT/first-route setup out of the paced window. These are new writes with their own receipts.
    await Promise.all(pair.map(work => work()));
    if (warmup.some(row => row.outcome !== 'ok')) throw new Error('A warmup operation failed');
  }
  await delay(1000);

  phase = 'load';
  started = Date.now();
  const start = performance.now();
  for (let step = 0; step < steps; step++) {
    await delay(Math.max(0, start + step * 2000 - performance.now()));
    const afterRevocation = step >= Math.floor(steps / 2);
    if (step === Math.floor(steps / 2)) {
      await measured('logout', record => request('/auth/logout', revoked.token, {}, 204, record));
      await measured('revoked-read', record => request(materialA, revoked.token, undefined, 401, record));
      if (outcomes.slice(-2).some(row => row.outcome !== 'ok')) break;
    }
    const eligible = afterRevocation ? roomA.pupils.slice(0, -1) : roomA.pupils;
    const reader = eligible[step % eligible.length];
    const cursor = outcomes.length;
    await Promise.all(operations(step, reader, afterRevocation ? revoked : null).map(async (pair, index) => {
      if (index) await delay(index * 300);
      await Promise.all(pair.map(work => work()));
    }));
    if (outcomes.slice(cursor).some(row => row.outcome !== 'ok' || row.ms > 750)) break;
  }
  const failed = outcomes.some(row => row.outcome !== 'ok' || row.ms > 750);
  if (!failed && outcomes.length === planned) await delay(Math.max(0, start + steps * 2000 - performance.now()));
  ended = Date.now();
  if (failed) throw new Error('The measured window exceeded its error or latency budget');
  assert.equal(outcomes.length, planned);
  phase = 'verify';
  await until(() => revoked.channel.closed === 4001, 'revoked socket closed', 11000);
  assert.equal(roomB.pupils[0].channel.closed, null);
  assert.equal(Number(sql(`select count(*) from board_runtime.operations o join system_logs l on l.id=o.audit_id
    where o.operation_id in (${changes.join(',')}) and l.correlation_id=o.operation_id and l.outcome='SUCCESS'`)), (steps + 1) * 2);
  assert.equal(Number(sql(`select count(*) from evaluation_runtime.operations o join system_logs l on l.id=o.audit_id
    where o.operation_id in (${answerKeys.join(',')}) and l.correlation_id=o.operation_id and l.outcome='SUCCESS'`)), (steps + 1) * 2);
  const stateA = await request(`${boardPath(a)}/teacher/${roomA.board}/state`, roomA.teacher);
  const stateB = await request(`${boardPath(b)}/teacher/${roomB.board}/state`, roomB.teacher);
  assert.equal(stateA.revision, steps + 1);
  assert.equal(stateB.revision, steps + 1);
  checks.push('session revoked; outbound denied; B stays connected; persisted revisions, receipts and audit match');
} catch (error) {
  failure = { phase, message: error.message };
  process.exitCode = 1;
} finally {
  for (const channel of channels) channel.close();
  const counts = Object.fromEntries(['ok', 'failed', 'timedOut'].map(outcome => [outcome,
    outcomes.filter(row => row.outcome === outcome).length]));
  const summary = { studentsA: students, studentsB: 1, teachers: 2, planned, sent: outcomes.length,
    unsent: planned - outcomes.length, ...counts, started, ended, checks, failure, setupCalls, failedSetupRequest,
    warmup, requests: outcomes };
  writeFileSync(join(output, 'mixed.json'), JSON.stringify(summary, null, 2) + '\n');
  console.log(JSON.stringify({ ...summary, warmup: undefined, requests: undefined }));
}
