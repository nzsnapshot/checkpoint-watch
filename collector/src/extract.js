'use strict';

/**
 * Pure functions that turn Facebook's raw JSON into the posts we publish.
 *
 * The tree walk is a faithful port of the Android app's FeedJsonExtractor.kt: emit at the
 * top-most object that has a non-blank string `post_id` once a `creation_time` and a
 * `message.text` can be found in its subtree, do not descend past an emitted post, cap the
 * recursion depth, and dedupe by id keeping the longest text. Nothing here ever throws on bad
 * input; a chunk we cannot make sense of simply contributes no posts.
 */

const PAGE = 'CheckpointNZ';
const PAGE_URL = `https://www.facebook.com/${PAGE}`;

/**
 * Recursion cap. Real Facebook payloads nest about 25 levels deep; 200 leaves generous
 * headroom while guaranteeing we can never blow the stack on a deep or hostile document.
 */
const MAX_DEPTH = 200;

const FOR_LOOP_PREFIX = /^\s*for\s*\(;;\);\s*/;
const SCRIPT_BLOCK = /<script\s+type="application\/json"[^>]*>([\s\S]*?)<\/script>/gi;

const DEFAULT_MAX_AGE_HOURS = 72;
const DEFAULT_MAX_POSTS = 150;

/** `https://www.facebook.com/CheckpointNZ/posts/<id>` */
function postUrl(id) {
  return `${PAGE_URL}/posts/${id}`;
}

/** A usable photo link: https, and served from Facebook's own CDN. */
function validImageUrl(u) {
  if (typeof u !== 'string' || u.length === 0) return false;
  let parsed;
  try {
    parsed = new URL(u);
  } catch {
    return false;
  }
  if (parsed.protocol !== 'https:') return false;
  return parsed.hostname.endsWith('.fbcdn.net');
}

function isPlainObject(v) {
  return v !== null && typeof v === 'object' && !Array.isArray(v);
}

function stripForLoopPrefix(chunk) {
  return chunk.replace(FOR_LOOP_PREFIX, '');
}

function tryParse(text) {
  try {
    return JSON.parse(text);
  } catch {
    // Malformed, truncated, or nested so deeply that the parser gives up. Either way: skip it.
    return null;
  }
}

/** Bodies of `<script type="application/json">` tags that mention `post_id`. */
function jsonBlocksFromHtml(html) {
  if (typeof html !== 'string') return [];
  const blocks = [];
  SCRIPT_BLOCK.lastIndex = 0;
  let match;
  while ((match = SCRIPT_BLOCK.exec(html)) !== null) {
    const body = match[1];
    if (body.includes('post_id')) blocks.push(body);
  }
  return blocks;
}

/** A non-blank string field, or null. */
function stringField(obj, key) {
  const v = obj[key];
  if (typeof v !== 'string') return null;
  return v.trim().length === 0 ? null : v;
}

/** kotlinx's `longOrNull`: a JSON number, or a string that is entirely digits. */
function asLong(v) {
  if (typeof v === 'number') return Number.isFinite(v) ? Math.trunc(v) : null;
  if (typeof v === 'string' && /^-?\d+$/.test(v.trim())) {
    const n = Number(v.trim());
    return Number.isSafeInteger(n) ? n : null;
  }
  return null;
}

/** First value for `key` that reads as a whole number, anywhere in the subtree. */
function findLongInSubtree(node, key, depth) {
  if (depth > MAX_DEPTH) return null;
  if (Array.isArray(node)) {
    for (const item of node) {
      const nested = findLongInSubtree(item, key, depth + 1);
      if (nested !== null) return nested;
    }
    return null;
  }
  if (isPlainObject(node)) {
    for (const k of Object.keys(node)) {
      const v = node[k];
      if (k === key) {
        const direct = asLong(v);
        if (direct !== null) return direct;
      }
      const nested = findLongInSubtree(v, key, depth + 1);
      if (nested !== null) return nested;
    }
  }
  return null;
}

function findCreationTime(obj, depth) {
  const direct = asLong(obj.creation_time);
  if (direct !== null) return direct;
  return findLongInSubtree(obj, 'creation_time', depth);
}

/** First `message.text` found by pre-order, key-order search of the subtree. */
function findMessageText(node, depth) {
  if (depth > MAX_DEPTH) return null;
  if (Array.isArray(node)) {
    for (const item of node) {
      const nested = findMessageText(item, depth + 1);
      if (nested !== null) return nested;
    }
    return null;
  }
  if (isPlainObject(node)) {
    for (const key of Object.keys(node)) {
      const value = node[key];
      if (key === 'message' && isPlainObject(value) && typeof value.text === 'string') {
        return value.text;
      }
      const nested = findMessageText(value, depth + 1);
      if (nested !== null) return nested;
    }
  }
  return null;
}

/** First `photo_image.uri` found in the subtree, if it is a link we would actually show. */
function findPhotoUri(node, depth) {
  if (depth > MAX_DEPTH) return null;
  if (Array.isArray(node)) {
    for (const item of node) {
      const nested = findPhotoUri(item, depth + 1);
      if (nested !== null) return nested;
    }
    return null;
  }
  if (isPlainObject(node)) {
    for (const key of Object.keys(node)) {
      const value = node[key];
      if (key === 'photo_image' && isPlainObject(value) && validImageUrl(value.uri)) {
        return value.uri;
      }
      const nested = findPhotoUri(value, depth + 1);
      if (nested !== null) return nested;
    }
  }
  return null;
}

/**
 * Walks a parsed document, collecting posts. Emits at the top-most object carrying a usable
 * `post_id` and stops descending there, so the many copies Facebook nests inside a story do
 * not each become a post of their own.
 */
function walk(node, out, depth) {
  if (depth > MAX_DEPTH) return;
  if (Array.isArray(node)) {
    for (const item of node) walk(item, out, depth + 1);
    return;
  }
  if (!isPlainObject(node)) return;

  const postId = stringField(node, 'post_id');
  if (postId !== null) {
    const createdAt = findCreationTime(node, depth);
    const text = findMessageText(node, depth);
    if (createdAt !== null && typeof text === 'string' && text.trim().length > 0) {
      out.push({
        id: postId,
        createdAt,
        text,
        image: findPhotoUri(node, depth),
      });
      return;
    }
  }
  for (const key of Object.keys(node)) walk(node[key], out, depth + 1);
}

/** Keep one post per id: the longest text wins, and any photo we saw is retained. */
function dedupeKeepingLongestText(posts) {
  const byId = new Map();
  for (const post of posts) {
    const existing = byId.get(post.id);
    if (!existing) {
      byId.set(post.id, { ...post });
      continue;
    }
    if (post.text.length > existing.text.length) {
      byId.set(post.id, { ...post, image: post.image || existing.image || null });
    } else if (!existing.image && post.image) {
      existing.image = post.image;
    }
  }
  return [...byId.values()];
}

/**
 * Posts found across every chunk, newest first. A chunk may be one JSON document or several
 * separated by newlines, and may carry Facebook's `for (;;);` anti-hijacking prefix.
 */
function extractPosts(chunks) {
  const found = [];
  const list = Array.isArray(chunks) ? chunks : [];
  for (const chunk of list) {
    if (typeof chunk !== 'string' || chunk.length === 0) continue;
    const stripped = stripForLoopPrefix(chunk);
    const whole = tryParse(stripped);
    if (whole !== null) {
      walk(whole, found, 0);
      continue;
    }
    for (const line of stripped.split('\n')) {
      const trimmed = line.trim();
      if (trimmed.length === 0) continue;
      const doc = tryParse(stripForLoopPrefix(trimmed));
      if (doc === null) continue;
      walk(doc, found, 0);
    }
  }
  return sortNewestFirst(dedupeKeepingLongestText(found));
}

/** Newest first, with the id as a stable tie-breaker. */
function sortNewestFirst(posts) {
  return [...posts].sort((a, b) => {
    if (b.createdAt !== a.createdAt) return b.createdAt - a.createdAt;
    return a.id < b.id ? 1 : a.id > b.id ? -1 : 0;
  });
}

/**
 * Folds a scan's posts into the ones we already published: dedupe by id, take the newer text
 * only when it is longer (Facebook sometimes serves a truncated copy), never lose a photo we
 * already had, then drop anything older than `maxAgeHours` and cap the list at `maxPosts`.
 */
function mergeFeed(previousPosts, newPosts, nowSec, options = {}) {
  const maxAgeHours = options.maxAgeHours ?? DEFAULT_MAX_AGE_HOURS;
  const maxPosts = options.maxPosts ?? DEFAULT_MAX_POSTS;

  const byId = new Map();
  const add = (post) => {
    if (!post || typeof post.id !== 'string' || post.id.trim().length === 0) return;
    const text = typeof post.text === 'string' ? post.text : '';
    const createdAt = asLong(post.createdAt);
    if (createdAt === null) return;
    const image = validImageUrl(post.image) ? post.image : null;
    const existing = byId.get(post.id);
    if (!existing) {
      byId.set(post.id, { id: post.id, createdAt, text, image });
      return;
    }
    if (text.length > existing.text.length) existing.text = text;
    if (!existing.image && image) existing.image = image;
  };

  for (const post of Array.isArray(previousPosts) ? previousPosts : []) add(post);
  for (const post of Array.isArray(newPosts) ? newPosts : []) add(post);

  const cutoff = asLong(nowSec) === null ? 0 : asLong(nowSec) - maxAgeHours * 3600;
  return sortNewestFirst([...byId.values()])
    .filter((post) => post.createdAt >= cutoff)
    .slice(0, maxPosts);
}

/** The published document. Small, stable, and free of anything about the machine it ran on. */
function buildFeed({ posts, generatedAt, lastFullScanAt = null, outcome = 'ERROR' }) {
  const list = Array.isArray(posts) ? posts : [];
  return {
    version: 1,
    page: PAGE,
    generatedAt: asLong(generatedAt) ?? 0,
    lastFullScanAt: asLong(lastFullScanAt),
    collector: { outcome, posts: list.length },
    posts: list.map((post) => ({
      id: post.id,
      createdAt: post.createdAt,
      text: post.text,
      url: postUrl(post.id),
      image: validImageUrl(post.image) ? post.image : null,
    })),
  };
}

module.exports = {
  PAGE,
  PAGE_URL,
  MAX_DEPTH,
  DEFAULT_MAX_AGE_HOURS,
  DEFAULT_MAX_POSTS,
  extractPosts,
  jsonBlocksFromHtml,
  mergeFeed,
  buildFeed,
  validImageUrl,
  postUrl,
  sortNewestFirst,
};
