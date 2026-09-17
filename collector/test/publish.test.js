'use strict';

const test = require('node:test');
const assert = require('node:assert');

const { buildFeed } = require('../src/extract');
const {
  sameFeedContent,
  shouldPush,
  contentHash,
  buildStatus,
  gitFailure,
  MAX_STATUS_ENTRIES,
  MIN_HEARTBEAT_SEC,
} = require('../src/publish');

const NOW = 1789646506;
const feedWith = (posts, generatedAt = NOW, outcome = 'FEED', lastFullScanAt = NOW - 30) =>
  buildFeed({ posts, generatedAt, lastFullScanAt, outcome });
const onePost = [{ id: 'a', createdAt: NOW - 60, text: 'a checkpoint', image: null }];

test('two feeds that differ only by their clock count as unchanged', () => {
  assert.ok(sameFeedContent(feedWith(onePost, NOW), feedWith(onePost, NOW + 300)));
});

test('a successful scan that found nothing new is not a reason to push', () => {
  // lastFullScanAt moves every successful scan; treating it as content would push every cycle.
  assert.ok(sameFeedContent(feedWith(onePost, NOW, 'FEED', NOW - 300), feedWith(onePost, NOW, 'FEED', NOW)));
});

test('a new post makes the feed changed', () => {
  const more = [...onePost, { id: 'b', createdAt: NOW, text: 'another', image: null }];

  assert.ok(!sameFeedContent(feedWith(onePost), feedWith(more)));
});

test('edited text and a changed outcome both count as changed', () => {
  const edited = [{ ...onePost[0], text: 'a checkpoint, updated' }];

  assert.ok(!sameFeedContent(feedWith(onePost), feedWith(edited)));
  assert.ok(!sameFeedContent(feedWith(onePost, NOW, 'FEED'), feedWith(onePost, NOW, 'STARVED')));
});

test('contentHash is stable and copes with nothing', () => {
  assert.strictEqual(contentHash(feedWith(onePost)), contentHash(feedWith(onePost, NOW + 9999)));
  assert.strictEqual(contentHash(null), 'none');
  assert.notStrictEqual(contentHash(feedWith([])), 'none');
});

test('changed content is always pushed, however recent the last push', () => {
  const decision = shouldPush({
    nextFeed: feedWith(onePost),
    lastPushedHash: 'something-else',
    lastPushAt: NOW - 10,
    nowSec: NOW,
  });

  assert.deepStrictEqual({ push: decision.push, reason: decision.reason }, { push: true, reason: 'changed' });
});

test('unchanged content is skipped while the last push is still fresh', () => {
  const feed = feedWith(onePost);
  const decision = shouldPush({
    nextFeed: feed,
    lastPushedHash: contentHash(feed),
    lastPushAt: NOW - 60,
    nowSec: NOW,
  });

  assert.strictEqual(decision.push, false);
  assert.strictEqual(decision.reason, 'unchanged');
});

test('unchanged content is pushed anyway once the heartbeat is due', () => {
  const feed = feedWith(onePost);
  const decision = shouldPush({
    nextFeed: feed,
    lastPushedHash: contentHash(feed),
    lastPushAt: NOW - MIN_HEARTBEAT_SEC,
    nowSec: NOW,
  });

  assert.strictEqual(decision.push, true);
  assert.strictEqual(decision.reason, 'heartbeat');
});

test('a first run with no memory of a previous push always pushes', () => {
  const decision = shouldPush({ nextFeed: feedWith(onePost), nowSec: NOW });

  assert.strictEqual(decision.push, true);
  assert.strictEqual(decision.reason, 'changed');
});

test('status keeps the last twenty scans, newest first', () => {
  let status = null;
  for (let i = 0; i < 25; i += 1) {
    status = buildStatus(status, {
      at: NOW + i,
      outcome: 'FEED',
      endReason: 'WALL',
      posts: i,
      ms: 1000 + i,
      graphqlBodies: 3,
      rounds: 8,
    });
  }

  assert.strictEqual(status.scans.length, MAX_STATUS_ENTRIES);
  assert.strictEqual(status.scans[0].at, NOW + 24);
  assert.strictEqual(status.scans[MAX_STATUS_ENTRIES - 1].at, NOW + 5);
  assert.strictEqual(status.updatedAt, NOW + 24);
  assert.strictEqual(status.version, 1);
});

test('status entries carry no hostname, username, path or address', () => {
  const status = buildStatus(null, {
    at: NOW,
    outcome: 'STARVED',
    endReason: 'NO_FEED',
    posts: 1,
    ms: 12000,
    graphqlBodies: 0,
    rounds: 6,
    // Anything extra must be dropped, not published.
    hostname: 'chris-pc',
    user: 'chris',
    path: '/home/chris/collector',
    ip: '203.0.113.7',
  });

  assert.deepStrictEqual(Object.keys(status.scans[0]), [
    'at',
    'outcome',
    'endReason',
    'posts',
    'ms',
    'graphqlBodies',
    'rounds',
  ]);
  const text = JSON.stringify(status);
  for (const secret of ['chris-pc', 'chris', '/home/', '203.0.113.7']) {
    assert.ok(!text.includes(secret), `status.json leaked ${secret}`);
  }
});

test('git failures become short codes', () => {
  assert.strictEqual(gitFailure({ stderr: 'fatal: Authentication failed for https://github.com/x' }), 'AUTH');
  assert.strictEqual(gitFailure({ stderr: 'fatal: could not read Username for https://github.com' }), 'AUTH');
  assert.strictEqual(gitFailure({ stderr: 'fatal: unable to access ...: Could not resolve host: github.com' }), 'NETWORK');
  assert.strictEqual(gitFailure({ stderr: 'remote: Repository not found.' }), 'NO_REMOTE');
  assert.strictEqual(gitFailure({ stderr: 'something odd' }), 'GIT');
  assert.strictEqual(gitFailure(null), 'GIT');
});
