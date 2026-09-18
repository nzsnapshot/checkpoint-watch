'use strict';

const test = require('node:test');
const assert = require('node:assert');

const { mergeFeed, buildFeed } = require('../src/extract');

const NOW = 1789646506;
const HOUR = 3600;
const IMAGE = 'https://scontent-akl1-1.xx.fbcdn.net/v/photo.jpg';

const post = (id, hoursAgo, text = `post ${id}`, image = null) => ({
  id,
  createdAt: NOW - hoursAgo * HOUR,
  text,
  image,
});

test('merges new posts into the old ones without duplicating anything', () => {
  const previous = [post('a', 1), post('b', 2)];
  const fresh = [post('b', 2), post('c', 0)];

  const merged = mergeFeed(previous, fresh, NOW);

  assert.deepStrictEqual(merged.map((p) => p.id), ['c', 'a', 'b']);
});

test('the longer text wins, whichever side it came from', () => {
  const previous = [post('a', 1, 'the full text of the post, as first seen')];
  const truncated = [post('a', 1, 'the full text...')];

  assert.strictEqual(mergeFeed(previous, truncated, NOW)[0].text, 'the full text of the post, as first seen');
  assert.strictEqual(mergeFeed(truncated, previous, NOW)[0].text, 'the full text of the post, as first seen');
});

test('a photo we already had is never lost to a later scan that missed it', () => {
  const previous = [post('a', 1, 'text', IMAGE)];
  const withoutPhoto = [post('a', 1, 'text')];

  assert.strictEqual(mergeFeed(previous, withoutPhoto, NOW)[0].image, IMAGE);
});

test('a photo found later is picked up', () => {
  const merged = mergeFeed([post('a', 1)], [post('a', 1, 'post a', IMAGE)], NOW);

  assert.strictEqual(merged[0].image, IMAGE);
});

test('a photo link from somewhere other than the Facebook CDN is dropped', () => {
  const merged = mergeFeed([], [post('a', 1, 'text', 'https://evil.example.com/p.jpg')], NOW);

  assert.strictEqual(merged[0].image, null);
});

test('posts older than the age limit are pruned', () => {
  const merged = mergeFeed([post('old', 73), post('fresh', 71)], [], NOW, { maxAgeHours: 72 });

  assert.deepStrictEqual(merged.map((p) => p.id), ['fresh']);
});

test('the list is capped at maxPosts, keeping the newest', () => {
  const many = Array.from({ length: 20 }, (_, i) => post(`p${i}`, i));

  const merged = mergeFeed(many, [], NOW, { maxPosts: 5 });

  assert.strictEqual(merged.length, 5);
  assert.deepStrictEqual(merged.map((p) => p.id), ['p0', 'p1', 'p2', 'p3', 'p4']);
});

test('the result is newest first, with a stable order for equal times', () => {
  const merged = mergeFeed([post('b', 1), post('a', 1), post('c', 0)], [], NOW);

  assert.deepStrictEqual(merged.map((p) => p.id), ['c', 'b', 'a']);
});

test('rubbish entries are skipped rather than published', () => {
  const merged = mergeFeed(
    [post('a', 1), { id: '', createdAt: NOW, text: 'x' }, { id: 'b' }, null],
    [{ id: 'c', createdAt: 'not a number', text: 'x' }],
    NOW,
  );

  assert.deepStrictEqual(merged.map((p) => p.id), ['a']);
});

test('merging does not mutate the lists it was given', () => {
  const previous = [post('a', 1, 'short')];
  const fresh = [post('a', 1, 'a longer text')];
  const snapshot = JSON.stringify(previous);

  mergeFeed(previous, fresh, NOW);

  assert.strictEqual(JSON.stringify(previous), snapshot);
});

test('buildFeed produces the published schema', () => {
  const feed = buildFeed({
    posts: [post('1614134890501393', 0, 'hello', IMAGE), post('b', 1)],
    generatedAt: NOW,
    lastFullScanAt: NOW - 60,
    outcome: 'FEED',
  });

  assert.deepStrictEqual(Object.keys(feed), [
    'version',
    'page',
    'generatedAt',
    'lastFullScanAt',
    'collector',
    'posts',
  ]);
  assert.strictEqual(feed.version, 1);
  assert.strictEqual(feed.page, 'CheckpointNZ');
  assert.strictEqual(feed.generatedAt, NOW);
  assert.strictEqual(feed.lastFullScanAt, NOW - 60);
  assert.deepStrictEqual(feed.collector, { outcome: 'FEED', posts: 2 });
  assert.deepStrictEqual(Object.keys(feed.posts[0]), ['id', 'createdAt', 'text', 'url', 'image']);
  assert.strictEqual(feed.posts[0].url, 'https://www.facebook.com/CheckpointNZ/posts/1614134890501393');
  assert.strictEqual(feed.posts[0].image, IMAGE);
  assert.strictEqual(feed.posts[1].image, null);
});

test('buildFeed copes with a starved scan that found nothing', () => {
  const feed = buildFeed({ posts: [], generatedAt: NOW, lastFullScanAt: null, outcome: 'STARVED' });

  assert.deepStrictEqual(feed.collector, { outcome: 'STARVED', posts: 0 });
  assert.strictEqual(feed.lastFullScanAt, null);
  assert.deepStrictEqual(feed.posts, []);
});
