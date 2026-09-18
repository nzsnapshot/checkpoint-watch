'use strict';

const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');

const { extractPosts, jsonBlocksFromHtml, validImageUrl, postUrl } = require('../src/extract');

/** The same pruned live captures the Android app's unit tests run against. */
const FIXTURES = path.resolve(__dirname, '..', '..', 'app', 'src', 'test', 'resources', 'fixtures');
const fixture = (name) => fs.readFileSync(path.join(FIXTURES, name), 'utf8');
const FEED_CHUNKS = ['graphql_1.txt', 'graphql_2.txt', 'graphql_3.txt', 'initial_block.json'].map(fixture);

/** Shaped exactly like the live capture of a post with a photo attachment. */
const PHOTO_POST = JSON.stringify({
  data: {
    node: {
      post_id: '1614200000000001',
      creation_time: 1789650000,
      attachments: [
        {
          styles: {
            attachment: {
              media: {
                __typename: 'Photo',
                photo_image: {
                  uri: 'https://scontent-akl1-1.xx.fbcdn.net/v/t39.30808-6/photo.jpg?_nc_cat=1&oh=abc',
                  width: 526,
                  height: 526,
                },
              },
            },
          },
        },
      ],
      comet_sections: {
        content: {
          story: {
            message: { text: '🛑 CHECKPOINT – Great North Road, KELSTON\nTime: 8:10PM' },
          },
        },
      },
    },
  },
});

test('extracts every post from the four live fixtures, newest first', () => {
  const posts = extractPosts(FEED_CHUNKS);

  assert.strictEqual(posts.length, 10);
  assert.strictEqual(new Set(posts.map((p) => p.id)).size, 10);
  assert.strictEqual(posts[0].id, '1614134890501393');
  assert.strictEqual(posts[0].createdAt, 1789646506);
  assert.match(posts[0].text, /HEAVY POLICE PRESENCE/);
  assert.strictEqual(posts[0].image, null);

  const times = posts.map((p) => p.createdAt);
  assert.deepStrictEqual(times, [...times].sort((a, b) => b - a));
});

test('every extracted post carries an id, a time and non-empty text', () => {
  for (const post of extractPosts(FEED_CHUNKS)) {
    assert.ok(post.id.length > 0);
    assert.ok(Number.isInteger(post.createdAt) && post.createdAt > 0);
    assert.ok(post.text.trim().length > 0);
  }
});

test('finds the photo attachment on a post that has one', () => {
  const [post] = extractPosts([PHOTO_POST]);

  assert.strictEqual(post.id, '1614200000000001');
  assert.strictEqual(post.createdAt, 1789650000);
  assert.strictEqual(post.image, 'https://scontent-akl1-1.xx.fbcdn.net/v/t39.30808-6/photo.jpg?_nc_cat=1&oh=abc');
});

test('ignores a photo link that is not a Facebook CDN https link', () => {
  const spoofed = PHOTO_POST.replace(
    'https://scontent-akl1-1.xx.fbcdn.net/v/t39.30808-6/photo.jpg?_nc_cat=1&oh=abc',
    'https://evil.example.com/photo.jpg',
  );

  assert.strictEqual(extractPosts([spoofed])[0].image, null);
});

test('reads newline-separated documents and strips the for(;;); prefix', () => {
  const chunk = `for (;;);${fixture('graphql_1.txt')}`;

  assert.strictEqual(extractPosts([chunk]).length, 3);
});

test('a single chunk holding several documents yields each post once', () => {
  const posts = extractPosts([FEED_CHUNKS.join('\n')]);

  assert.strictEqual(posts.length, 10);
});

test('malformed, empty and non-string input never throws', () => {
  assert.deepStrictEqual(extractPosts([]), []);
  assert.deepStrictEqual(extractPosts(null), []);
  assert.deepStrictEqual(extractPosts(['{not json at all']), []);
  assert.deepStrictEqual(extractPosts(['', '   ', '{"a":']), []);
  assert.deepStrictEqual(extractPosts([42, null, undefined, {}]), []);
  assert.deepStrictEqual(extractPosts(['{"post_id":"1","creation_time":1}']), []);
});

test('50 000 nested arrays are survived, not thrown on', () => {
  const deep = '['.repeat(50000) + ']'.repeat(50000);
  const deepObjects = '{"a":'.repeat(50000) + '1' + '}'.repeat(50000);

  assert.deepStrictEqual(extractPosts([deep]), []);
  assert.deepStrictEqual(extractPosts([deepObjects]), []);
});

test('a post buried deeper than the depth cap is simply not found', () => {
  const real = { post_id: '99', creation_time: 1, message: { text: 'hi' } };
  let nested = real;
  for (let i = 0; i < 300; i += 1) nested = { down: nested };

  assert.deepStrictEqual(extractPosts([JSON.stringify(nested)]), []);
});

test('a blank or missing post_id is ignored', () => {
  const blank = JSON.stringify({ post_id: '   ', creation_time: 1789646506, message: { text: 'x' } });
  const missing = JSON.stringify({ creation_time: 1789646506, message: { text: 'x' } });
  const notAString = JSON.stringify({ post_id: 123, creation_time: 1789646506, message: { text: 'x' } });

  assert.deepStrictEqual(extractPosts([blank]), []);
  assert.deepStrictEqual(extractPosts([missing]), []);
  assert.deepStrictEqual(extractPosts([notAString]), []);
});

test('a post with blank text is not emitted', () => {
  const blankText = JSON.stringify({ post_id: '7', creation_time: 1, message: { text: '   ' } });

  assert.deepStrictEqual(extractPosts([blankText]), []);
});

test('duplicate ids collapse to one, keeping the longest text and any photo', () => {
  const short = { post_id: '5', creation_time: 10, message: { text: 'short' } };
  const long = {
    post_id: '5',
    creation_time: 10,
    photo_image: { uri: 'https://scontent.xx.fbcdn.net/p.jpg' },
    message: { text: 'a much longer version of the same post' },
  };

  const posts = extractPosts([JSON.stringify([long, short])]);
  assert.strictEqual(posts.length, 1);
  assert.strictEqual(posts[0].text, 'a much longer version of the same post');
  assert.strictEqual(posts[0].image, 'https://scontent.xx.fbcdn.net/p.jpg');

  const reversed = extractPosts([JSON.stringify([short, long])]);
  assert.strictEqual(reversed[0].text, 'a much longer version of the same post');
  assert.strictEqual(reversed[0].image, 'https://scontent.xx.fbcdn.net/p.jpg');
});

test('jsonBlocksFromHtml returns only the application/json blocks mentioning post_id', () => {
  const blocks = jsonBlocksFromHtml(fixture('page_min.html'));

  assert.strictEqual(blocks.length, 1);
  assert.ok(blocks[0].includes('1614134890501393'));
  assert.strictEqual(extractPosts(blocks).length, 1);
});

test('jsonBlocksFromHtml copes with rubbish', () => {
  assert.deepStrictEqual(jsonBlocksFromHtml(''), []);
  assert.deepStrictEqual(jsonBlocksFromHtml(null), []);
  assert.deepStrictEqual(jsonBlocksFromHtml('<script type="application/json">{}</script>'), []);
});

test('validImageUrl accepts Facebook CDN https links and nothing else', () => {
  assert.ok(validImageUrl('https://scontent-akl1-1.xx.fbcdn.net/v/photo.jpg'));
  assert.ok(validImageUrl('https://z-1-scontent.fakl3-1.fna.fbcdn.net/v/photo.jpg?oh=1'));

  assert.ok(!validImageUrl('http://scontent.xx.fbcdn.net/v/photo.jpg'));
  assert.ok(!validImageUrl('https://fbcdn.net.evil.com/photo.jpg'));
  assert.ok(!validImageUrl('https://example.com/photo.jpg'));
  assert.ok(!validImageUrl('not a url'));
  assert.ok(!validImageUrl(null));
  assert.ok(!validImageUrl(undefined));
  assert.ok(!validImageUrl(''));
});

test('postUrl points at the page this app reads', () => {
  assert.strictEqual(postUrl('1614134890501393'), 'https://www.facebook.com/CheckpointNZ/posts/1614134890501393');
});
