'use strict';

/**
 * The service: scan, merge into what we already published, publish, sleep, repeat.
 *
 * It keeps running whether or not Facebook is co-operating. When a scan comes back starved or
 * blocked it still publishes the status file, so the app can tell the difference between "the
 * collector is down" and "the collector is alive but being limited".
 */

const fs = require('node:fs');
const path = require('node:path');

const { mergeFeed, buildFeed } = require('./extract');
const { scanOnce } = require('./scan');
const { publish, buildStatus, readPublishedFeed, readPublishedStatus } = require('./publish');

const ROOT = path.resolve(__dirname, '..');
const STATE_DIR = path.join(ROOT, 'state');
const LOCK_FILE = path.join(STATE_DIR, 'collector.lock');
const LOG_FILE = path.join(STATE_DIR, 'collector.log');
const RUNTIME_FILE = path.join(STATE_DIR, 'runtime.json');

const DEFAULT_INTERVAL_MIN = 5;
const JITTER_MS = 30 * 1000;
const MAX_BACKOFF_MS = 30 * 60 * 1000;
const LOG_MAX_BYTES = 1024 * 1024;
const LOG_KEEP = 2;

const args = new Set(process.argv.slice(2));
const ONCE = args.has('--once');
const DRY_RUN = args.has('--dry-run');
const HEADFUL = args.has('--headful');

let stopping = false;
let wakeUp = null;

function nowSec() {
  return Math.floor(Date.now() / 1000);
}

function intervalMs() {
  const minutes = Number(process.env.CW_INTERVAL_MIN);
  const safe = Number.isFinite(minutes) && minutes > 0 ? minutes : DEFAULT_INTERVAL_MIN;
  return safe * 60 * 1000;
}

/** One line per cycle, to the console and to a log that never grows past ~3 MB. */
function log(line) {
  const stamped = `${new Date().toISOString()} ${line}`;
  process.stdout.write(`${stamped}\n`);
  try {
    fs.mkdirSync(STATE_DIR, { recursive: true });
    rotateLogIfBig();
    fs.appendFileSync(LOG_FILE, `${stamped}\n`, 'utf8');
  } catch {
    // A log we cannot write is not a reason to stop collecting.
  }
}

function rotateLogIfBig() {
  let size = 0;
  try {
    size = fs.statSync(LOG_FILE).size;
  } catch {
    return;
  }
  if (size < LOG_MAX_BYTES) return;
  try {
    fs.rmSync(`${LOG_FILE}.${LOG_KEEP}`, { force: true });
    for (let i = LOG_KEEP - 1; i >= 1; i -= 1) {
      const from = `${LOG_FILE}.${i}`;
      if (fs.existsSync(from)) fs.renameSync(from, `${LOG_FILE}.${i + 1}`);
    }
    fs.renameSync(LOG_FILE, `${LOG_FILE}.1`);
  } catch {
    // Ignore: worst case the log keeps growing a little longer.
  }
}

/** One collector per machine. A lock left behind by a crash is taken over, not obeyed. */
function acquireLock() {
  fs.mkdirSync(STATE_DIR, { recursive: true });
  try {
    const pid = Number(fs.readFileSync(LOCK_FILE, 'utf8').trim());
    if (Number.isInteger(pid) && pid > 0 && pid !== process.pid && isRunning(pid)) {
      return false;
    }
  } catch {
    // No lock, or an unreadable one: ours now.
  }
  fs.writeFileSync(LOCK_FILE, String(process.pid), 'utf8');
  return true;
}

function isRunning(pid) {
  try {
    process.kill(pid, 0);
    return true;
  } catch (error) {
    return error && error.code === 'EPERM';
  }
}

function releaseLock() {
  try {
    const pid = Number(fs.readFileSync(LOCK_FILE, 'utf8').trim());
    if (pid === process.pid) fs.rmSync(LOCK_FILE, { force: true });
  } catch {
    // Nothing to release.
  }
}

function readRuntime() {
  try {
    return JSON.parse(fs.readFileSync(RUNTIME_FILE, 'utf8'));
  } catch {
    return {};
  }
}

function writeRuntime(value) {
  try {
    fs.mkdirSync(STATE_DIR, { recursive: true });
    fs.writeFileSync(RUNTIME_FILE, `${JSON.stringify(value, null, 2)}\n`, 'utf8');
  } catch {
    // Non-fatal: we lose only the "last full scan" memory.
  }
}

/**
 * The timer stays ref'd on purpose: between cycles it is the only thing keeping the event loop
 * alive, and an unref'd timer lets Node exit cleanly mid-sleep as if the service had finished.
 */
function sleep(ms) {
  return new Promise((resolve) => {
    const timer = setTimeout(resolve, ms);
    wakeUp = () => {
      clearTimeout(timer);
      resolve();
    };
  });
}

/** Scan, merge, publish. Returns the outcome so the loop can decide how long to wait. */
async function cycle() {
  const at = nowSec();
  const runtime = readRuntime();

  const previousFeed = readPublishedFeed(STATE_DIR);
  const previousPosts = previousFeed && Array.isArray(previousFeed.posts) ? previousFeed.posts : [];

  const result = await scanOnce({ headless: !HEADFUL });
  const posts = mergeFeed(previousPosts, result.posts, at);
  const lastFullScanAt = result.outcome === 'FEED' ? at : (runtime.lastFullScanAt ?? null);

  const feed = buildFeed({
    posts,
    generatedAt: at,
    lastFullScanAt,
    outcome: result.outcome,
  });

  let published;
  try {
    published = publish({
      feed,
      status: buildStatus(readPublishedStatus(STATE_DIR), {
        at,
        outcome: result.outcome,
        endReason: result.endReason,
        posts: posts.length,
        ms: result.stats.ms,
        graphqlBodies: result.stats.graphqlBodies,
        rounds: result.stats.rounds,
      }),
      stateDir: STATE_DIR,
      dryRun: DRY_RUN,
      nowSec: at,
    });
  } catch (error) {
    published = { pushed: false, reason: 'publish-threw', detail: String(error && error.message) };
  }

  writeRuntime({ ...runtime, lastFullScanAt, lastCycleAt: at });

  const bits = [
    `outcome=${result.outcome}`,
    `end=${result.endReason}`,
    `scanned=${result.posts.length}`,
    `feed=${posts.length}`,
    `graphql=${result.stats.graphqlBodies}`,
    `rounds=${result.stats.rounds}`,
    `ms=${result.stats.ms}`,
    `publish=${published.dryRun ? 'dry-run' : published.pushed ? 'pushed' : `skipped(${published.reason})`}`,
  ];
  if (published.error) bits.push(`publishError=${published.error}`);
  log(bits.join(' '));
  if (published.detail) log(`  git: ${published.detail}`);

  return { result, published, posts };
}

async function main() {
  if (!acquireLock()) {
    process.stderr.write('Another collector is already running (state/collector.lock).\n');
    process.exit(1);
  }

  const shutdown = () => {
    if (stopping) return;
    stopping = true;
    log('stopping');
    if (wakeUp) wakeUp();
  };
  process.on('SIGINT', shutdown);
  process.on('SIGTERM', shutdown);

  try {
    if (ONCE) {
      const { result, published } = await cycle();
      const bad = result.outcome === 'ERROR' || result.outcome === 'BLOCKED';
      const publishFailed = Boolean(published.error);
      return bad || publishFailed ? 1 : 0;
    }

    let backoffMs = 0;
    log(`started interval=${intervalMs() / 60000}min dryRun=${DRY_RUN}`);
    while (!stopping) {
      let outcome = 'ERROR';
      try {
        const { result } = await cycle();
        outcome = result.outcome;
      } catch (error) {
        log(`cycle failed: ${String(error && error.message).split('\n')[0]}`);
      }

      if (outcome === 'FEED') {
        backoffMs = 0;
      } else {
        backoffMs = backoffMs === 0 ? intervalMs() : Math.min(backoffMs * 2, MAX_BACKOFF_MS);
      }

      if (stopping) break;
      const base = Math.max(intervalMs(), backoffMs);
      const jitter = Math.round((Math.random() * 2 - 1) * JITTER_MS);
      const waitMs = Math.max(30000, base + jitter);
      log(`sleeping ${Math.round(waitMs / 1000)}s`);
      await sleep(waitMs);
    }
    return 0;
  } finally {
    releaseLock();
  }
}

main()
  .then((code) => process.exit(code))
  .catch((error) => {
    log(`fatal: ${String(error && error.message).split('\n')[0]}`);
    releaseLock();
    process.exit(1);
  });
