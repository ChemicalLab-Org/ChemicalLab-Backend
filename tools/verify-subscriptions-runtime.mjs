import assert from 'node:assert/strict';
import { createHmac, randomUUID } from 'node:crypto';
import { execFile, execFileSync } from 'node:child_process';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { join } from 'node:path';
import { promisify } from 'node:util';
import { setTimeout as pause } from 'node:timers/promises';

const [run, psql, port, mockPort = '18094'] = process.argv.slice(2);
assert(run && psql && /^\d{4,5}$/.test(port) && /^\d{4,5}$/.test(mockPort));
const ready = JSON.parse(readFileSync(join(run, 'browser/ready.json'), 'utf8'));
const origin = new URL(ready.origin);
assert.equal(origin.hostname, '127.0.0.1');
let database = 'chemicallab_t12_runtime_test';
const recorded = [];
let providerCalls = 0;
let callbacks = 0;
let posts = 0;
let requests = 0;
let remote;
let lose = true;
const invoices = new Map();
const payments = new Map();
const setup = (school) => `/api/platform/institutions/${school}/subscription/v1`;
const billing = (school) => `/api/institutions/${school}/subscription/v1`;

const server = createServer(async (req, res) => {
  providerCalls++;
  const url = new URL(req.url, 'http://127.0.0.1');
  let result;
  if (req.method === 'POST' && url.pathname === '/preapproval') {
    posts++;
    const chunks = [];
    for await (const chunk of req) chunks.push(chunk);
    const command = JSON.parse(Buffer.concat(chunks));
    assert.deepEqual(command.auto_recurring, { currency_id: 'PEN', frequency_type: 'months', frequency: 1, transaction_amount: 99 });
    assert.equal(command.status, 'pending');
    assert.equal(command.payer_email, 'subscription-buyer@example.invalid');
    remote = { ...command, id: 'subtest1', collector_id: 1101, application_id: 1102, payer_id: 1103,
      init_point: `http://127.0.0.1:${mockPort}/checkout/subtest1`, last_modified: new Date().toISOString() };
    if (lose) {
      lose = false;
      req.socket.destroy();
      return;
    }
    result = remote;
  } else if (url.pathname === '/preapproval/search') {
    result = { results: remote && url.searchParams.get('q') === remote.reason ? [remote] : [] };
  } else if (remote && url.pathname === '/preapproval/' + remote.id) {
    if (req.method === 'PUT') {
      const chunks = [];
      for await (const chunk of req) chunks.push(chunk);
      assert.deepEqual(JSON.parse(Buffer.concat(chunks)), { status: 'canceled' });
      changeContract('canceled');
    }
    result = remote;
  } else if (url.pathname === '/authorized_payments/search') {
    const preapproval = url.searchParams.get('preapproval_id');
    const payment = url.searchParams.get('payment_id');
    const offset = Number(url.searchParams.get('offset'));
    result = { results: [...invoices.values()].filter(i => i.preapproval_id === preapproval || String(i.payment?.id) === payment)
      .slice(offset, offset + 20) };
  } else if (url.pathname.startsWith('/authorized_payments/')) {
    result = invoices.get(url.pathname.split('/').at(-1));
  } else if (url.pathname.startsWith('/v1/payments/')) {
    result = payments.get(url.pathname.split('/').at(-1));
  }
  res.writeHead(result ? 200 : 404, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(result ?? {}));
});

function changeContract(status) {
  remote = { ...remote, status, last_modified: new Date().toISOString(), next_payment_date: '2090-04-01T14:00:00Z' };
}

function sqlArgs(query) {
  return ['-h','127.0.0.1','-p',port,'-U','t12_runtime_local','-d',database,'-XqtA','-v','ON_ERROR_STOP=1','-c',query];
}
function sql(query) { return execFileSync(psql, sqlArgs(query), { encoding: 'utf8', windowsHide: true }).trim(); }
const sqlAsync = (query) => promisify(execFile)(psql, sqlArgs(query), { windowsHide: true });

async function call(method, path, token, expected, body, extra = {}) {
  const headers = { ...extra };
  if (token) headers.Authorization = 'Bearer ' + token;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const response = await fetch(new URL(path, origin), { method, headers, signal: AbortSignal.timeout(20000),
    body: body === undefined ? undefined : JSON.stringify(body) });
  const result = await response.text();
  requests++;
  assert.equal(response.status, expected, method + ' ' + path + ' ' + result.slice(0, 200));
  return result ? JSON.parse(result) : null;
}
const get = (path, token, code = 200) => call('GET', path, token, code);
const post = (path, token, body, code = 200) => call('POST', path, token, code, body);
const login = async (username, password = ready.password) =>
  (await post('/api/auth/login', null, { usernameOrEmail: username, password })).token;

async function event(type, id, { request = randomUUID(), invalid = false, live = false, seller = 1101 } = {}) {
  const ts = String(Date.now());
  const signature = createHmac('sha256', 'fictitious-local-signature-2026')
    .update(`id:${id};request-id:${request};ts:${ts};`).digest('hex');
  callbacks++;
  await call('POST', `/api/subscriptions/v1/mercado-pago/webhook?data.id=${id}`, null,
    invalid || live || seller !== 1101 ? 401 : 200,
    { type, data: { id }, user_id: seller, live_mode: live },
    { 'x-signature': `ts=${ts},v1=${invalid ? '0'.repeat(64) : signature}`, 'x-request-id': request });
  return request;
}

async function waitFor(probe, label) {
  for (let i = 0; i < 120; i++) {
    if (await probe()) return;
    await pause(350);
  }
  throw new Error('Tiempo agotado en ' + label);
}

function mark(label) { recorded.push(label); console.log(label); }

function makeInvoice(id, paymentId, status, due = Date.now() - 3600000) {
  const debit = new Date(due).toISOString();
  const version = new Date().toISOString();
  invoices.set(id, { id, preapproval_id: remote.id, external_reference: remote.external_reference, currency_id: 'PEN',
    transaction_amount: 99, debit_date: debit, last_modified: version, payment: { id: paymentId } });
  if (paymentId) payments.set(paymentId, { id: paymentId, collector_id: 1101, payer: { id: 1103 },
    live_mode: false, status, currency_id: 'PEN', transaction_amount: 99, date_approved: version, date_last_updated: version });
}

try {
  await new Promise((resolve, reject) => server.listen(Number(mockPort), '127.0.0.1', resolve).once('error', reject));
  const temp = await login(ready.platform, ready.platformTemporary);
  const password = 'Subscription-Fixture-Changed-2026!';
  const platform = (await call('PATCH','/api/auth/change-temporary-password', temp, 200,
    { currentPassword: ready.platformTemporary, newPassword: password, confirmPassword: password })).token;
  const teacherA = await login(ready.teacherA);
  const teacherB = await login(ready.teacherB);
  const student = await login(ready.student);
  const actorId = Number(sql("SELECT id FROM user_accounts WHERE username='runtime-teacher-a'"));
  const actorB = Number(sql("SELECT id FROM user_accounts WHERE username='runtime-teacher-b'"));

  assert.equal((await get('/api/agreements/account', teacherA)).enabled, false);
  await post(setup(1) + '/verify', teacherA, { evidenceReference: 'FICT-SCHOOL-A' }, 403);
  await post(setup(1) + '/verify', platform, { evidenceReference: 'FICT-SCHOOL-A' }, 204);
  await post(setup(1) + '/verify', platform, { evidenceReference: 'FICT-SCHOOL-A' }, 409);
  await post(setup(2) + '/verify', platform, { evidenceReference: 'FICT-SCHOOL-B' }, 204);
  await post(setup(1) + '/authority', platform, { accountId: actorB, evidenceReference: 'FICT-WRONG',
    validUntil: '2090-01-01T00:00:00Z' }, 409);
  const representation = { accountId: actorId, evidenceReference: 'FICT-AUTH-A', validUntil: '2090-01-01T00:00:00Z' };
  await post(setup(1) + '/authority', platform, representation, 204);
  await post(setup(2) + '/authority', platform, { ...representation, accountId: actorB, evidenceReference: 'FICT-AUTH-B' }, 204);
  await get(billing(2), teacherA, 404);
  await get(billing(1), student, 404);
  await post(billing(1) + '/checkout', student, { confirmed: true }, 403);
  const trial = await get(billing(1), teacherA);
  assert.equal(trial.status, 'TRIAL');
  assert.equal(trial.activeUsers, 2);
  assert.equal((await get(billing(2), teacherB)).activeUsers, 2);
  assert.equal(trial.plan.storageBytes, 2_000_000_000);
  await post(billing(1) + '/checkout', teacherA, { confirmed: true }, 409);
  assert.equal(providerCalls, 0);
  sql("UPDATE evaluation_runtime.institutions SET active=false WHERE id=1");
  sql("UPDATE evaluation_runtime.institutions SET active=true WHERE id=1");
  assert.equal((await get(billing(1), teacherA)).trialStart, trial.trialStart);
  assert.equal(sql("SELECT ('2024-01-31 13:00:00'::timestamp + interval '1 month')::date"), '2024-02-29');
  mark('Prueba única, dos colegios y cuenta compartida. Cero consultas al proveedor durante la prueba.');

  // Advance only the disposable fixture. The immutable production trigger is restored in the same transaction.
  sql(`BEGIN; ALTER TABLE subscription_runtime.school DISABLE TRIGGER subscription_trial_guard;
       UPDATE subscription_runtime.school SET trial_start=clock_timestamp()-interval '2 months',
       trial_end=clock_timestamp()-interval '1 month' WHERE institution_id=1;
       ALTER TABLE subscription_runtime.school ENABLE TRIGGER subscription_trial_guard; COMMIT;`);
  await post(billing(1) + '/checkout', teacherA, { confirmed: true, amount: 1, currency: 'USD', institutionId: 2 }, 400);
  const clazz = Number(sql('SELECT min(id) FROM evaluation_runtime.classes WHERE institution_id=1'));
  const board = `/api/institutions/1/classes/${clazz}/whiteboards/v1/teacher`;
  await call('POST', board, teacherA, 402, { name: 'Ficticia' }, { 'Idempotency-Key': randomUUID() });
  await get(board, teacherA);
  assert.equal((await get(billing(2), teacherB)).status, 'TRIAL');

  const outcomes = await Promise.all([1,2,3].map(() => post(billing(1) + '/checkout', teacherA, { confirmed: true })));
  assert(outcomes.every(value => value.status === 'VERIFYING'));
  assert.equal(posts, 1);
  assert.equal(sql('SELECT count(*) FROM subscription_runtime.contract'), '1');
  sql('UPDATE subscription_runtime.contract SET reconcile_at=clock_timestamp()');
  await waitFor(async () => (await get(billing(1), teacherA)).status === 'CHECKOUT_PENDING', 'checkout reinstated');
  assert.equal(posts, 1);
  const countBefore = providerCalls;
  for (let i = 0; i < 4; i++) await get(billing(1), teacherA);
  assert.equal(providerCalls, countBefore);
  mark('Respuesta perdida recuperada, una sola creación con solicitudes concurrentes y cero consultas al proveedor en lecturas.');

  changeContract('authorized');
  await event('subscription_preapproval', remote.id, { invalid: true });
  await event('subscription_preapproval', remote.id, { live: true });
  await event('subscription_preapproval', remote.id, { seller: 9999 });
  assert.equal(sql('SELECT count(*) FROM subscription_runtime.inbox'), '0');
  const first = await event('subscription_preapproval', remote.id);
  await event('subscription_preapproval', remote.id, { request: first });
  assert.equal(sql('SELECT count(*) FROM subscription_runtime.inbox'), '1');
  await waitFor(async () => (await get(billing(1), teacherA)).status === 'AUTHORIZED_UNPAID', 'authorized');
  makeInvoice('2101', '3101', 'pending');
  await event('subscription_authorized_payment', '2101');
  await waitFor(() => sql("SELECT status FROM subscription_runtime.invoice WHERE id='2101'") === 'PENDING', 'pending invoice');
  makeInvoice('2101', '3101', 'rejected');
  await event('payment', '3101');
  await waitFor(() => sql("SELECT status FROM subscription_runtime.invoice WHERE id='2101'") === 'FAILED', 'rejected');
  assert.equal((await get(billing(1), teacherA)).paidUntil, null);
  makeInvoice('2101', '3102', 'approved');
  const approved = { ...payments.get('3102') };
  payments.set('3102', { ...approved, live_mode: true });
  await event('subscription_authorized_payment', '2101');
  await waitFor(() => sql("SELECT max(attempts) FROM subscription_runtime.inbox WHERE resource='2101'") === '1', 'unverified event checked');
  assert.equal((await get(billing(1), teacherA)).paidUntil, null);
  payments.set('3102', approved);
  await event('payment', '3102');
  await waitFor(async () => (await get(billing(1), teacherA)).status === 'PAID', 'approved');
  const paid = await get(billing(1), teacherA);
  assert(paid.paidUntil && paid.nextCharge);
  makeInvoice('2102', '3103', 'approved', Date.now() + 30 * 86400000);
  await event('subscription_authorized_payment', '2102');
  await waitFor(() => sql("SELECT status FROM subscription_runtime.invoice WHERE id='2102'") === 'APPROVED', 'renewal invoice');
  assert.equal((await get(billing(1), teacherA)).paidUntil, paid.paidUntil);
  mark('Firma, entorno, vendedor, deduplicación, pendiente, rechazo, aprobación y renovación futura comprobados.');

  await post(billing(1) + '/cancel-renewal', teacherB, {}, 403);
  await post(billing(1) + '/cancel-renewal', teacherA, {});
  await waitFor(async () => (await get(billing(1), teacherA)).status === 'CANCELLED_PAID', 'cancellation');
  assert.equal((await get(billing(1), teacherA)).paidUntil, paid.paidUntil);
  sql('UPDATE evaluation_runtime.institutions SET active=false WHERE id=1');
  assert.equal((await get(billing(1), teacherA)).administrativeActive, false);
  payments.set('3102', { ...approved, status: 'charged_back', date_last_updated: new Date().toISOString() });
  await event('payment', '3102');
  await waitFor(() => sql("SELECT status FROM subscription_runtime.invoice WHERE id='2101'") === 'REVERSED', 'chargeback');
  payments.set('3102', approved);
  await event('payment', '3102');
  await pause(1500);
  assert.equal((await get(billing(1), teacherA)).paidUntil, null);
  await post(setup(1) + '/authority', platform, { ...representation, validUntil: null }, 204);
  await post(billing(1) + '/cancel-renewal', teacherA, {}, 403);
  assert.equal((await get(billing(2), teacherB)).status, 'TRIAL');
  mark('Cancelación conserva el período, contracargo lo revierte, observación antigua no lo restaura y revocación impide operar.');

  sql(`INSERT INTO user_accounts(username,password,role,active,temporary_password,credentials_version,created_at)
       SELECT 'quota-fixture-'||id,'unused','ESTUDIANTE',true,false,0,clock_timestamp() FROM generate_series(1,200) id;
       INSERT INTO evaluation_runtime.memberships(institution_id,account_id,valid_from)
       SELECT 2,id,clock_timestamp() FROM user_accounts WHERE username LIKE 'quota-fixture-%' ORDER BY id LIMIT 197;`);
  const queries = ['quota-fixture-198','quota-fixture-199'].map(name => sqlAsync(
    `INSERT INTO evaluation_runtime.memberships(institution_id,account_id,valid_from)
      SELECT 2,id,clock_timestamp() FROM user_accounts WHERE username='${name}'`));
  assert.equal((await Promise.allSettled(queries)).filter(result => result.status === 'fulfilled').length, 1);
  assert.equal((await get(billing(2), teacherB)).activeUsers, 200);
  await assert.rejects(sqlAsync(`BEGIN; UPDATE subscription_runtime.school SET seats=seats-1 WHERE institution_id=2;
      SELECT 1/0; COMMIT;`));
  assert.equal((await get(billing(2), teacherB)).activeUsers, 200);
  assert.equal(sql('SELECT max_bytes FROM evaluation_runtime.quotas WHERE institution_id=2'), '2000000000');
  mark('Cuota concurrente y reversión de transacción comprobadas en PostgreSQL.');

  writeFileSync(join(run, 'browser/restart'), '1');
  await waitFor(() => existsSync(join(run, 'restarted.pid')), 'application restart');
  assert.equal((await get(billing(2), teacherB)).activeUsers, 200);
  writeFileSync(join(run, 'browser/recover'), '1');
  await waitFor(() => existsSync(join(run, 'recovered.pid')), 'database restore');
  database = 'chemicallab_t12_administration_recovered_test';
  assert.equal((await get(billing(2), teacherB)).activeUsers, 200);
  assert.equal(sql("SELECT status FROM subscription_runtime.invoice WHERE id='2101'"), 'REVERSED');
  assert.equal(sql('SELECT count(*) FROM subscription_runtime.contract'), '1');
  mark('Estado conservado tras reinicio y restauración lógica PostgreSQL.');
} finally {
  server.close();
  writeFileSync(join(run, 'browser/stop'), '1');
  writeFileSync(join(run, 'subscription-results.json'), JSON.stringify({ recorded, providerCalls, callbacks, posts, requests }, null, 2));
}
