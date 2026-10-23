// Load test for POST /api/v1/readings: many devices, each posting a batch every
// few seconds, with a known share of batches deliberately sent twice.
//
//   k6 run -e ADMIN_TOKEN=... load-tests/k6/ingest.js
//
// Each virtual user (VU) is one device. setup() registers one bin and one
// device per VU through the admin API, so a run needs only a URL and an admin
// token, and never touches existing bins. The run then has three stages:
//
//   ramp  -- VUs go from 0 to DEVICES over RAMP
//   hold  -- DEVICES devices for HOLD: the steady state the headline numbers
//            describe, so the ramp doesn't dilute them
//   down  -- 30 seconds to stop
//
// Correctness is checked three ways:
//   1. every response accounts for every reading sent:
//      accepted + duplicates + rejected = sent, and a fresh batch is accepted whole
//   2. every resent batch comes back as all duplicates, none accepted
//   3. teardown() counts what the database actually stored for this run's bins,
//      through the readings API, and the summary compares it with the accepted
//      total. That is the server-side proof that nothing was stored twice.
//
// Settings (all optional, given with -e NAME=value):
//   BASE_URL     http://localhost:8080        the API, or the CloudFront URL
//   ADMIN_TOKEN  (required)                   to register the run's devices
//   DEVICES      50                           peak number of devices (VUs)
//   INTERVAL     5                            seconds between one device's batches
//   CABLES       4, DEPTHS 6                  sensors per sample: CABLES x DEPTHS
//   DUP_RATE     0.05                         share of batches that are resends
//   RAMP         2m, HOLD 5m                  stage lengths (s or m)
//   LABEL        local                        recorded in the summary
//   SUMMARY_FILE (none)                       also write the full summary as JSON

import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Rate, Trend } from 'k6/metrics';

const BASE_URL = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/+$/, '');
const ADMIN_TOKEN = __ENV.ADMIN_TOKEN;
const DEVICES = intSetting('DEVICES', 50);
const INTERVAL = numberSetting('INTERVAL', 5);
const CABLES = intSetting('CABLES', 4);
const DEPTHS = intSetting('DEPTHS', 6);
const DUP_RATE = numberSetting('DUP_RATE', 0.05);
const RAMP = __ENV.RAMP || '2m';
const HOLD = __ENV.HOLD || '5m';
const LABEL = __ENV.LABEL || 'local';

const READINGS_PER_BATCH = CABLES * DEPTHS;
const RAMP_S = seconds(RAMP);
const HOLD_S = seconds(HOLD);

export const options = {
  // Registering a few hundred devices, and counting their readings afterwards,
  // takes longer than the 60-second default.
  setupTimeout: '10m',
  teardownTimeout: '10m',
  scenarios: {
    devices: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: RAMP, target: DEVICES },
        { duration: HOLD, target: DEVICES },
        { duration: '30s', target: 0 },
      ],
      gracefulRampDown: '30s',
    },
  },
  // Correctness is pass/fail: a single miscounted batch fails the run, and so
  // does more than 1% of requests failing. There is deliberately no latency
  // threshold: these runs measure latency rather than enforce a target.
  thresholds: {
    ingest_accounting_correct: ['rate==1'],
    duplicate_detection_correct: ['rate==1'],
    ingest_errors: ['rate<0.01'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

// --- metrics ---------------------------------------------------------------
// The hold-phase metrics are the headline numbers. Totals cover the whole run.
const ingestRequests = new Counter('ingest_requests');
const holdRequests = new Counter('hold_requests');
const holdReadingsAccepted = new Counter('hold_readings_accepted');
const readingsSent = new Counter('readings_sent');
const readingsAccepted = new Counter('readings_accepted');
const readingsDuplicate = new Counter('readings_duplicate');
const readingsRejected = new Counter('readings_rejected');
const batchesResent = new Counter('batches_resent');
const readingsStored = new Counter('readings_stored');
const cadenceMissed = new Counter('cadence_missed');
const ingestErrors = new Rate('ingest_errors');
const accountingCorrect = new Rate('ingest_accounting_correct');
const duplicateCorrect = new Rate('duplicate_detection_correct');
const latency = new Trend('ingest_latency', true);
const holdLatency = new Trend('hold_latency', true);

// --- setup: register this run's bins and devices -----------------------------

export function setup() {
  if (!ADMIN_TOKEN) {
    fail('ADMIN_TOKEN is not set: pass it with -e ADMIN_TOKEN=...');
  }
  const runId = new Date().toISOString().slice(0, 19).replace(/[-:T]/g, '');
  const startedAt = new Date().toISOString();
  const admin = {
    headers: { Authorization: `Bearer ${ADMIN_TOKEN}`, 'Content-Type': 'application/json' },
    tags: { kind: 'admin' },
  };

  const devices = [];
  // In chunks of 25 parallel requests, so setup is quick without flooding the
  // API before the test has started.
  for (let first = 1; first <= DEVICES; first += 25) {
    const numbers = range(first, Math.min(first + 24, DEVICES));
    const bins = http.batch(numbers.map((n) => ['POST', `${BASE_URL}/api/v1/bins`,
      JSON.stringify({ name: `k6 ${runId} #${n}`, site: 'load test', grainType: 'wheat' }), admin]));
    const binIds = bins.map((res, i) => {
      if (res.status !== 201) fail(`creating bin #${numbers[i]}: HTTP ${res.status} ${res.body}`);
      return res.json('id');
    });
    // A day between reports, the longest the API allows: the run's devices go
    // quiet when it ends, and a short interval would open a DEVICE_OFFLINE
    // alert for every one of them. They are still evaluated on every check.
    const registered = http.batch(binIds.map((binId) => ['POST', `${BASE_URL}/api/v1/bins/${binId}/devices`,
      JSON.stringify({ expectedIntervalSeconds: 86400 }), admin]));
    registered.forEach((res, i) => {
      if (res.status !== 201) fail(`registering a device on bin ${binIds[i]}: HTTP ${res.status} ${res.body}`);
      devices.push({ binId: binIds[i], apiKey: res.json('apiKey') });
    });
  }
  console.log(`run ${runId}: registered ${devices.length} devices against ${BASE_URL}`);
  return { runId, startedAt, devices };
}

// --- each VU: one device ---------------------------------------------------

// Module-level state is per VU: every VU runs its own copy of this script.
let previousBody = null;
let firstIteration = true;

export default function (data) {
  const device = data.devices[(exec.vu.idInTest - 1) % data.devices.length];

  // Spread the first reports over one interval, so the devices don't all post
  // in lockstep.
  if (firstIteration) {
    firstIteration = false;
    sleep(Math.random() * INTERVAL);
  }

  const started = Date.now();
  const resend = previousBody !== null && Math.random() < DUP_RATE;
  const body = resend ? previousBody : JSON.stringify(batch(started));
  if (!resend) previousBody = body;
  const kind = resend ? 'resend' : 'fresh';

  const res = http.post(`${BASE_URL}/api/v1/readings`, body, {
    headers: { 'X-Device-Key': device.apiKey, 'Content-Type': 'application/json' },
    tags: { kind, name: 'POST /api/v1/readings' },
    timeout: '10s',
  });

  const inHold = phase() === 'hold';
  ingestRequests.add(1);
  readingsSent.add(READINGS_PER_BATCH);
  latency.add(res.timings.duration, { kind });
  if (inHold) {
    holdRequests.add(1);
    holdLatency.add(res.timings.duration, { kind });
  }

  const ok = res.status === 202;
  ingestErrors.add(!ok);
  if (ok) {
    const counts = res.json();
    readingsAccepted.add(counts.accepted);
    readingsDuplicate.add(counts.duplicates);
    readingsRejected.add(counts.rejected);
    if (inHold) holdReadingsAccepted.add(counts.accepted);

    const accounted = counts.accepted + counts.duplicates + counts.rejected === READINGS_PER_BATCH;
    accountingCorrect.add(accounted && (resend || counts.accepted === READINGS_PER_BATCH));
    if (resend) {
      batchesResent.add(1);
      duplicateCorrect.add(counts.duplicates === READINGS_PER_BATCH && counts.accepted === 0);
    }
  }
  check(res, { 'ingest returns 202': (r) => r.status === 202 });

  // Keep the device's cadence: wait out the rest of the interval. A request
  // that took longer than the interval means the device fell behind, which is
  // counted rather than hidden.
  const elapsed = (Date.now() - started) / 1000;
  if (elapsed >= INTERVAL) cadenceMissed.add(1);
  sleep(Math.max(0, INTERVAL - elapsed));
}

// One sample: every sensor's temperature and moisture, below the default
// alert limits, so the load is ingest and evaluation rather than alerts.
function batch(nowMs) {
  const sensors = [];
  for (let cable = 0; cable < CABLES; cable++) {
    for (let depth = 0; depth < DEPTHS; depth++) {
      sensors.push({
        cable,
        depth,
        temperatureC: round1(11 + depth * 0.3 + Math.random()),
        moisturePct: round1(13 + Math.random()),
      });
    }
  }
  // seq is the send time in milliseconds: it increases with every fresh batch
  // from this device, and a resend repeats it exactly.
  return { samples: [{ seq: nowMs, recordedAt: new Date(nowMs).toISOString(), sensors }] };
}

// Which stage the scenario is in, from the time since it started.
function phase() {
  const t = (Date.now() - exec.scenario.startTime) / 1000;
  if (t < RAMP_S) return 'ramp';
  return t < RAMP_S + HOLD_S ? 'hold' : 'down';
}

// --- teardown: what did the database store? --------------------------------

export function teardown(data) {
  const day = 24 * 3600 * 1000;
  const from = new Date(Math.floor(Date.parse(data.startedAt) / day) * day).toISOString();
  const to = new Date((Math.floor(Date.now() / day) + 1) * day).toISOString();
  const admin = { headers: { Authorization: `Bearer ${ADMIN_TOKEN}` }, tags: { kind: 'admin' } };

  let stored = 0;
  for (let first = 0; first < data.devices.length; first += 25) {
    const chunk = data.devices.slice(first, first + 25);
    const responses = http.batch(chunk.map((d) => ['GET',
      `${BASE_URL}/api/v1/bins/${d.binId}/readings?from=${from}&to=${to}&bucket=day`, null, admin]));
    responses.forEach((res, i) => {
      if (res.status !== 200) fail(`counting bin ${chunk[i].binId}'s readings: HTTP ${res.status}`);
      for (const series of res.json('series')) {
        for (const point of series.points) stored += point.readings;
      }
    });
  }
  readingsStored.add(stored);
}

// --- summary ---------------------------------------------------------------

export function handleSummary(data) {
  const count = (name) => (data.metrics[name] ? data.metrics[name].values.count : 0);
  const value = (name, stat) => (data.metrics[name] ? data.metrics[name].values[stat] : null);
  const accepted = count('readings_accepted');
  const stored = count('readings_stored');

  const report = {
    label: LABEL,
    baseUrl: BASE_URL,
    finishedAt: new Date().toISOString(),
    settings: { devices: DEVICES, intervalSeconds: INTERVAL, sensorsPerBatch: READINGS_PER_BATCH,
      dupRate: DUP_RATE, ramp: RAMP, hold: HOLD },
    hold: {
      requestsPerSecond: count('hold_requests') / HOLD_S,
      readingsPerSecond: count('hold_readings_accepted') / HOLD_S,
      latencyMs: { p50: value('hold_latency', 'med'), p95: value('hold_latency', 'p(95)'),
        p99: value('hold_latency', 'p(99)'), max: value('hold_latency', 'max') },
    },
    totals: {
      requests: count('ingest_requests'),
      errorRate: value('ingest_errors', 'rate'),
      readingsSent: count('readings_sent'),
      readingsAccepted: accepted,
      readingsDuplicate: count('readings_duplicate'),
      readingsRejected: count('readings_rejected'),
      batchesResent: count('batches_resent'),
      cadenceMissed: count('cadence_missed'),
    },
    correctness: {
      accounting: value('ingest_accounting_correct', 'rate'),
      duplicateDetection: value('duplicate_detection_correct', 'rate'),
      readingsStored: stored,
      storedEqualsAccepted: stored === accepted,
    },
  };

  const pct = (r) => (r === null ? 'n/a' : `${(r * 100).toFixed(2)}%`);
  const ms = (v) => (v === null ? 'n/a' : `${v.toFixed(1)} ms`);
  const text = [
    '',
    `ingest load test: ${LABEL}, ${DEVICES} devices every ${INTERVAL}s, ${READINGS_PER_BATCH} readings a batch`,
    `  hold (${HOLD}):  ${report.hold.requestsPerSecond.toFixed(1)} requests/s, ` +
      `${report.hold.readingsPerSecond.toFixed(0)} readings/s accepted`,
    `  latency (hold):  p50 ${ms(report.hold.latencyMs.p50)}, p95 ${ms(report.hold.latencyMs.p95)}, ` +
      `p99 ${ms(report.hold.latencyMs.p99)}, max ${ms(report.hold.latencyMs.max)}`,
    `  requests:        ${report.totals.requests}, errors ${pct(report.totals.errorRate)}, ` +
      `fell behind ${report.totals.cadenceMissed}`,
    `  readings:        ${report.totals.readingsSent} sent, ${accepted} accepted, ` +
      `${report.totals.readingsDuplicate} duplicate, ${report.totals.readingsRejected} rejected`,
    `  correctness:     accounting ${pct(report.correctness.accounting)}, ` +
      (report.totals.batchesResent === 0
        ? 'no batches were resent'
        : `${report.totals.batchesResent} resent batches detected ${pct(report.correctness.duplicateDetection)}`),
    `  stored:          ${stored} readings in the database for this run's bins ` +
      `(${report.correctness.storedEqualsAccepted ? 'equals' : 'DOES NOT EQUAL'} accepted)`,
    '',
  ].join('\n');

  const outputs = { stdout: text };
  if (__ENV.SUMMARY_FILE) {
    outputs[__ENV.SUMMARY_FILE] = JSON.stringify({ report, metrics: data.metrics }, null, 2);
  }
  return outputs;
}

// --- helpers ---------------------------------------------------------------

function intSetting(name, fallback) {
  const v = __ENV[name] === undefined ? fallback : parseInt(__ENV[name], 10);
  if (!Number.isInteger(v) || v < 1) throw new Error(`${name} must be a positive whole number`);
  return v;
}

function numberSetting(name, fallback) {
  const v = __ENV[name] === undefined ? fallback : Number(__ENV[name]);
  if (!Number.isFinite(v) || v < 0) throw new Error(`${name} must be a number of at least 0`);
  return v;
}

function seconds(duration) {
  const m = /^(\d+)(s|m)$/.exec(duration);
  if (!m) throw new Error(`durations are like 30s or 5m, not ${duration}`);
  return Number(m[1]) * (m[2] === 'm' ? 60 : 1);
}

function range(from, to) {
  const out = [];
  for (let n = from; n <= to; n++) out.push(n);
  return out;
}

function round1(v) {
  return Math.round(v * 10) / 10;
}
