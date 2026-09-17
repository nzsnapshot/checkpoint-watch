'use strict';

const test = require('node:test');
const assert = require('node:assert');

const { shouldGiveUpNoFeed, isWall, errorReason, NO_FEED_ROUNDS } = require('../src/scan');

test('we keep scrolling while feed responses are arriving', () => {
  assert.strictEqual(shouldGiveUpNoFeed({ closed: true, rounds: 20, graphqlBodies: 3 }), false);
});

test('we keep scrolling for the first few empty rounds after closing the dialog', () => {
  for (let rounds = 0; rounds < NO_FEED_ROUNDS; rounds += 1) {
    assert.strictEqual(shouldGiveUpNoFeed({ closed: true, rounds, graphqlBodies: 0 }), false);
  }
});

test('six empty rounds after a successful Close means this connection is being starved', () => {
  assert.strictEqual(shouldGiveUpNoFeed({ closed: true, rounds: NO_FEED_ROUNDS, graphqlBodies: 0 }), true);
  assert.strictEqual(shouldGiveUpNoFeed({ closed: true, rounds: 12, graphqlBodies: 0 }), true);
});

test('we never give up early if the dialog was never closed', () => {
  assert.strictEqual(shouldGiveUpNoFeed({ closed: false, rounds: 20, graphqlBodies: 0 }), false);
});

test('a dialog with no Close button is the wall; one with a Close button is not', () => {
  assert.strictEqual(isWall([]), false);
  assert.strictEqual(isWall([{ hasDialog: true, hasClose: true }]), false);
  assert.strictEqual(isWall([{ hasDialog: true, hasClose: false }]), true);
  assert.strictEqual(isWall([{ hasDialog: true, hasClose: true }, { hasDialog: true, hasClose: false }]), true);
});

test('error reasons are short codes, never a stack or a path', () => {
  assert.strictEqual(errorReason(new Error('Timeout 30000ms exceeded.\n  at /home/someone/x.js')), 'TIMEOUT');
  assert.strictEqual(errorReason(new Error('net::ERR_CONNECTION_RESET at https://www.facebook.com')), 'NETWORK');
  assert.strictEqual(errorReason(new Error('page.goto: ERR_NAME_NOT_RESOLVED')), 'DNS');
  assert.strictEqual(errorReason(new Error("Executable doesn't exist at /ms-playwright/chromium")), 'NO_BROWSER');
  assert.strictEqual(errorReason(new Error('something else entirely')), 'ERROR');
  assert.strictEqual(errorReason(null), 'ERROR');
});
