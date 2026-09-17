'use strict';

/**
 * Publishing: a dedicated local clone of the repo's `data` branch, rewritten and force-pushed
 * as a single commit every time, so the branch never grows however long this runs.
 *
 * Authentication is whatever the machine's git already has — a credential helper, `gh auth
 * login`, or an SSH key. Nothing here reads, prints or stores a token, and nothing here
 * touches global git config: the collector's identity is passed per command with `-c`.
 */

const { execFileSync } = require('node:child_process');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');

const DATA_BRANCH = 'data';
const DEFAULT_REMOTE = 'https://github.com/nzsnapshot/checkpoint-watch.git';
const AUTHOR_NAME = 'checkpoint-watch-collector';
const AUTHOR_EMAIL = 'collector@users.noreply.github.com';
/** Users should see a fresh heartbeat at least this often, even when nothing has changed. */
const MIN_HEARTBEAT_SEC = 15 * 60;
const MAX_STATUS_ENTRIES = 20;

const IDENTITY = [
  '-c',
  `user.name=${AUTHOR_NAME}`,
  '-c',
  `user.email=${AUTHOR_EMAIL}`,
  '-c',
  'commit.gpgsign=false',
  '-c',
  'core.autocrlf=false',
];

function remoteUrl() {
  return process.env.CW_REMOTE || DEFAULT_REMOTE;
}

function git(args, cwd) {
  return execFileSync('git', args, {
    cwd,
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'pipe'],
    env: {
      ...process.env,
      // Never sit waiting for a password prompt that nobody is there to answer.
      GIT_TERMINAL_PROMPT: '0',
      LC_ALL: 'C',
    },
  }).trim();
}

/**
 * Feed content ignoring the clocks: two feeds are "the same" if they differ only in when they
 * were generated. Both `generatedAt` and `lastFullScanAt` are clocks — `lastFullScanAt` moves
 * on every successful scan, so counting it as content would force a push every five minutes
 * and the skip rule would never once fire. The 15-minute heartbeat keeps both fresh enough.
 * Everything a reader actually sees — the posts, and the collector's outcome — still counts.
 */
function sameFeedContent(a, b) {
  return contentHash(a) === contentHash(b);
}

function contentHash(feed) {
  if (!feed || typeof feed !== 'object') return 'none';
  const withoutClocks = { ...feed, generatedAt: 0, lastFullScanAt: 0 };
  return crypto.createHash('sha256').update(JSON.stringify(withoutClocks)).digest('hex');
}

/**
 * Whether this cycle is worth a push: content changed, or the last push is old enough that
 * users deserve a fresh heartbeat.
 */
function shouldPush({ nextFeed, lastPushedHash, lastPushAt, nowSec, minHeartbeatSec = MIN_HEARTBEAT_SEC }) {
  const hash = contentHash(nextFeed);
  if (hash !== lastPushedHash) return { push: true, reason: 'changed', hash };
  const age = Number(nowSec) - Number(lastPushAt || 0);
  if (!Number.isFinite(age) || age >= minHeartbeatSec) return { push: true, reason: 'heartbeat', hash };
  return { push: false, reason: 'unchanged', hash };
}

/**
 * The rolling scan log: newest first, capped, and carrying nothing about this machine. Fields
 * are copied one by one, so nothing a caller happens to have lying around can leak into it.
 * There is no "published" flag: this file only reaches the data branch by being published, so
 * its own freshness is the proof.
 */
function buildStatus(previous, entry) {
  const old = previous && Array.isArray(previous.scans) ? previous.scans : [];
  const scans = [entry, ...old].slice(0, MAX_STATUS_ENTRIES).map((scan) => ({
    at: scan.at,
    outcome: scan.outcome,
    endReason: scan.endReason,
    posts: scan.posts,
    ms: scan.ms,
    graphqlBodies: scan.graphqlBodies,
    rounds: scan.rounds,
  }));
  return { version: 1, page: 'CheckpointNZ', updatedAt: entry.at, scans };
}

function readJson(file) {
  try {
    return JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch {
    return null;
  }
}

function writeJson(file, value) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, `${JSON.stringify(value, null, 2)}\n`, 'utf8');
}

function repoDirFor(stateDir) {
  return path.join(stateDir, 'data-repo');
}

/** The feed we published last time, so a scan can be merged into it rather than replace it. */
function readPublishedFeed(stateDir) {
  return (
    readJson(path.join(repoDirFor(stateDir), 'feed.json')) ||
    readJson(path.join(stateDir, 'out', 'feed.json'))
  );
}

function readPublishedStatus(stateDir) {
  return (
    readJson(path.join(repoDirFor(stateDir), 'status.json')) ||
    readJson(path.join(stateDir, 'out', 'status.json'))
  );
}

/**
 * Makes sure `state/data-repo` is a repo whose HEAD is the `data` branch, pointed at the
 * remote, seeded from the remote branch if it already exists there. An orphan `data` is simply
 * what we get when the remote has no such branch yet: the first push creates it.
 */
function ensureRepo(stateDir, remote) {
  const dir = repoDirFor(stateDir);
  fs.mkdirSync(dir, { recursive: true });
  const fresh = !fs.existsSync(path.join(dir, '.git'));

  if (fresh) {
    git(['-c', `init.defaultBranch=${DATA_BRANCH}`, 'init', '-q'], dir);
    git(['remote', 'add', 'origin', remote], dir);
    // Works on an unborn HEAD, where `checkout --orphan` does not.
    git(['symbolic-ref', 'HEAD', `refs/heads/${DATA_BRANCH}`], dir);
  } else {
    git(['remote', 'set-url', 'origin', remote], dir);
  }

  let hasCommit = true;
  try {
    git(['rev-parse', '--verify', 'HEAD'], dir);
  } catch {
    hasCommit = false;
  }

  if (!hasCommit) {
    try {
      git(['fetch', '--quiet', '--depth=1', 'origin', DATA_BRANCH], dir);
      git(['reset', '--hard', '--quiet', 'FETCH_HEAD'], dir);
    } catch {
      // No `data` branch out there yet (or no network). The first push will create it.
    }
  }
  return dir;
}

/**
 * Writes the two files and, when it is worth it, replaces the branch with one fresh commit and
 * force-pushes. `commit-tree` with no parent is what keeps it at exactly one commit forever.
 */
function publish({
  feed,
  status,
  stateDir,
  dryRun = false,
  remote = remoteUrl(),
  nowSec = Math.floor(Date.now() / 1000),
  minHeartbeatSec = MIN_HEARTBEAT_SEC,
}) {
  if (dryRun) {
    const outDir = path.join(stateDir, 'out');
    writeJson(path.join(outDir, 'feed.json'), feed);
    writeJson(path.join(outDir, 'status.json'), status);
    return { pushed: false, dryRun: true, reason: 'dry-run', outDir };
  }

  const statePath = path.join(stateDir, 'publish-state.json');
  const saved = readJson(statePath) || {};
  const decision = shouldPush({
    nextFeed: feed,
    lastPushedHash: saved.lastPushedHash,
    lastPushAt: saved.lastPushAt,
    nowSec,
    minHeartbeatSec,
  });

  let dir;
  try {
    dir = ensureRepo(stateDir, remote);
  } catch (error) {
    return { pushed: false, reason: 'git-setup-failed', error: gitFailure(error), detail: detailOf(error) };
  }

  writeJson(path.join(dir, 'feed.json'), feed);
  writeJson(path.join(dir, 'status.json'), status);

  if (!decision.push) {
    return { pushed: false, reason: decision.reason };
  }

  try {
    git(['add', '-A'], dir);
    const tree = git(['write-tree'], dir);
    const message = `feed: ${feed.posts.length} posts, ${feed.collector.outcome.toLowerCase()}`;
    const commit = git([...IDENTITY, 'commit-tree', tree, '-m', message], dir);
    git(['update-ref', `refs/heads/${DATA_BRANCH}`, commit], dir);
    git(
      [...IDENTITY, 'push', '--force', '--quiet', 'origin', `refs/heads/${DATA_BRANCH}:refs/heads/${DATA_BRANCH}`],
      dir,
    );
  } catch (error) {
    return { pushed: false, reason: 'push-failed', error: gitFailure(error), detail: detailOf(error) };
  }

  writeJson(statePath, { lastPushAt: nowSec, lastPushedHash: decision.hash });
  return { pushed: true, reason: decision.reason };
}

/** A short code for status.json; the wordy version stays in the local log. */
function gitFailure(error) {
  const text = detailOf(error);
  if (/Authentication failed|could not read Username|Permission denied|403|denied to/i.test(text)) return 'AUTH';
  if (/Could not resolve host|unable to access|Connection (timed out|refused)|network/i.test(text)) return 'NETWORK';
  if (/not found|does not appear to be a git repository|Repository not found/i.test(text)) return 'NO_REMOTE';
  return 'GIT';
}

function detailOf(error) {
  if (!error) return '';
  const stderr = error.stderr ? String(error.stderr) : '';
  const stdout = error.stdout ? String(error.stdout) : '';
  const text = `${stderr}${stdout}`.trim() || String(error.message || '');
  return text.split('\n').filter(Boolean).slice(0, 2).join(' | ');
}

module.exports = {
  publish,
  shouldPush,
  sameFeedContent,
  contentHash,
  buildStatus,
  ensureRepo,
  readPublishedFeed,
  readPublishedStatus,
  repoDirFor,
  remoteUrl,
  gitFailure,
  DATA_BRANCH,
  DEFAULT_REMOTE,
  AUTHOR_NAME,
  AUTHOR_EMAIL,
  MIN_HEARTBEAT_SEC,
  MAX_STATUS_ENTRIES,
};
