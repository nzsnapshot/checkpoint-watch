/*
 * Node tests for the pure helpers in app/src/main/assets/plugin_collector.js.
 *
 * The script itself needs Facebook's Page Plugin in a WebView, so only its judgement is tested
 * here: which page it is allowed to run on, when it has waited long enough, which image on a post
 * is the post's and which is the page's avatar, and what a permalink is once it has been cleaned.
 *
 * Run: node app/src/test/js/plugin_collector.test.js   (not part of the Gradle build: no JS toolchain)
 */
'use strict';

var assert = require('assert');
var plugin = require('../../main/assets/plugin_collector.js');
var collector = require('../../main/assets/collector.js');

var passed = 0;

function check(name, fn) {
  fn();
  passed++;
  console.log('ok - ' + name);
}

// ------------------------------------------------------------- which page

check('isPluginPath recognises the Page Plugin, and only it', function () {
  assert.strictEqual(plugin.isPluginPath('/plugins/page.php'), true);
  assert.strictEqual(plugin.isPluginPath('/plugins/post.php'), true);
  assert.strictEqual(plugin.isPluginPath('/plugins/'), true);
  assert.strictEqual(plugin.isPluginPath('/CheckpointNZ'), false);
  assert.strictEqual(plugin.isPluginPath('/'), false);
  assert.strictEqual(plugin.isPluginPath('/notplugins/page.php'), false);
  assert.strictEqual(plugin.isPluginPath('/CheckpointNZ/plugins/page.php'), false);
});

check('isPluginPath survives rubbish', function () {
  assert.strictEqual(plugin.isPluginPath(null), false);
  assert.strictEqual(plugin.isPluginPath(undefined), false);
  assert.strictEqual(plugin.isPluginPath(42), false);
  assert.strictEqual(plugin.isPluginPath(''), false);
});

check('collector.js and plugin_collector.js gate on opposite sides of the same line', function () {
  // Both scripts are registered for the same origin, so each has to recognise the other's page
  // and do nothing on it. One shared rule, asserted from both ends.
  ['/plugins/page.php', '/CheckpointNZ', '/', 'nonsense', null].forEach(function (path) {
    assert.strictEqual(
      collector.isPluginPath(path),
      plugin.isPluginPath(path),
      'disagreed about ' + String(path)
    );
  });
});

// ------------------------------------------------------------ the waiting

check('pollDecision: collect as soon as a post with a timestamp has rendered', function () {
  assert.strictEqual(plugin.pollDecision(1, 0), 'collect');
  assert.strictEqual(plugin.pollDecision(5, 500), 'collect');
  assert.strictEqual(plugin.pollDecision(5, 99999), 'collect');
});

check('pollDecision: keep waiting for the twelve seconds the plugin is given', function () {
  assert.strictEqual(plugin.pollDecision(0, 0), 'wait');
  assert.strictEqual(plugin.pollDecision(0, 500), 'wait');
  assert.strictEqual(plugin.pollDecision(0, 11500), 'wait');
});

check('pollDecision: an empty plugin runs out of time rather than waiting for ever', function () {
  assert.strictEqual(plugin.pollDecision(0, 12000), 'timeout');
  assert.strictEqual(plugin.pollDecision(0, 20000), 'timeout');
});

check('pollDecision survives rubbish', function () {
  assert.strictEqual(plugin.pollDecision(null, null), 'wait');
  assert.strictEqual(plugin.pollDecision(undefined, undefined), 'wait');
});

check('endReasonFor: posts are a finished pass, nothing at all is a timeout', function () {
  assert.strictEqual(plugin.endReasonFor(5), 'NO_MORE_POSTS');
  assert.strictEqual(plugin.endReasonFor(1), 'NO_MORE_POSTS');
  assert.strictEqual(plugin.endReasonFor(0), 'TIMEOUT');
  assert.strictEqual(plugin.endReasonFor(null), 'TIMEOUT');
});

// -------------------------------------------------------------- permalinks

check('cleanLink: a plugin permalink loses its embed query and becomes absolute', function () {
  assert.strictEqual(
    plugin.cleanLink('/CheckpointNZ/posts/pfbid02abc?ref=embed_page'),
    'https://www.facebook.com/CheckpointNZ/posts/pfbid02abc'
  );
  assert.strictEqual(
    plugin.cleanLink('/reel/1234567890/?ref=embed_page'),
    'https://www.facebook.com/reel/1234567890/'
  );
});

check('cleanLink: an already-absolute facebook link is kept, minus query and fragment', function () {
  assert.strictEqual(
    plugin.cleanLink('https://www.facebook.com/CheckpointNZ/posts/pfbid02abc?ref=embed_page#x'),
    'https://www.facebook.com/CheckpointNZ/posts/pfbid02abc'
  );
  assert.strictEqual(
    plugin.cleanLink('https://www.facebook.com'),
    'https://www.facebook.com'
  );
});

check('cleanLink: anything that is not our page host is refused', function () {
  // The plugin's markup is untrusted input; a lookalike host must never come back as a permalink.
  assert.strictEqual(plugin.cleanLink('https://www.facebook.com.evil.example/CheckpointNZ'), null);
  assert.strictEqual(plugin.cleanLink('http://www.facebook.com/CheckpointNZ'), null);
  assert.strictEqual(plugin.cleanLink('https://l.facebook.com/l.php?u=http%3A%2F%2Fevil'), null);
  assert.strictEqual(plugin.cleanLink('javascript:alert(1)'), null);
  assert.strictEqual(plugin.cleanLink('CheckpointNZ/posts/1'), null);
});

check('cleanLink survives rubbish', function () {
  assert.strictEqual(plugin.cleanLink(null), null);
  assert.strictEqual(plugin.cleanLink(undefined), null);
  assert.strictEqual(plugin.cleanLink(''), null);
  assert.strictEqual(plugin.cleanLink('   '), null);
  assert.strictEqual(plugin.cleanLink(42), null);
  assert.strictEqual(plugin.cleanLink('?ref=embed_page'), null);
});

// ------------------------------------------------------------------ images

check('isPostImageClass: the post photo the plugin renders', function () {
  assert.strictEqual(plugin.isPostImageClass('_1p6f img'), true);
  assert.strictEqual(plugin.isPostImageClass('scaledImageFitWidth img'), true);
  assert.strictEqual(plugin.isPostImageClass('img scaledImageFitHeight'), true);
});

check('isPostImageClass: the 200x200 page avatar is not a post photo', function () {
  assert.strictEqual(plugin.isPostImageClass('_s0 _rw img'), false);
  // Even if it somehow carried both, the avatar marker is the veto.
  assert.strictEqual(plugin.isPostImageClass('_s0 _1p6f'), false);
});

check('isPostImageClass: unrelated images are left alone', function () {
  assert.strictEqual(plugin.isPostImageClass('img'), false);
  assert.strictEqual(plugin.isPostImageClass(''), false);
  assert.strictEqual(plugin.isPostImageClass('_1p6fx'), false);
  assert.strictEqual(plugin.isPostImageClass(null), false);
  assert.strictEqual(plugin.isPostImageClass(42), false);
});

check('pickImage: the first qualifying https image wins', function () {
  assert.strictEqual(
    plugin.pickImage([
      { className: '_s0 img', src: 'https://scontent.test.fbcdn.net/avatar.jpg' },
      { className: '_1p6f img', src: 'https://scontent.test.fbcdn.net/photo.jpg' },
      { className: 'scaledImageFitWidth', src: 'https://scontent.test.fbcdn.net/other.jpg' }
    ]),
    'https://scontent.test.fbcdn.net/photo.jpg'
  );
});

check('pickImage: nothing qualifying, or nothing over https, is no image at all', function () {
  assert.strictEqual(plugin.pickImage([{ className: '_s0', src: 'https://x/avatar.jpg' }]), null);
  assert.strictEqual(plugin.pickImage([{ className: '_1p6f', src: 'http://x/photo.jpg' }]), null);
  assert.strictEqual(plugin.pickImage([{ className: '_1p6f', src: 'data:image/png;base64,AAAA' }]), null);
  assert.strictEqual(plugin.pickImage([]), null);
  assert.strictEqual(plugin.pickImage(null), null);
  assert.strictEqual(plugin.pickImage([null, undefined, {}]), null);
});

// --------------------------------------------------------------- timestamps

check('parseUtime reads the unix seconds Facebook puts in data-utime', function () {
  assert.strictEqual(plugin.parseUtime('1789677637'), 1789677637);
  assert.strictEqual(plugin.parseUtime(' 1789677637 '), 1789677637);
  assert.strictEqual(plugin.parseUtime(1789677637), 1789677637);
});

check('parseUtime refuses anything that is not a plain positive count of seconds', function () {
  assert.strictEqual(plugin.parseUtime('0'), null);
  assert.strictEqual(plugin.parseUtime('-5'), null);
  assert.strictEqual(plugin.parseUtime('12.5'), null);
  assert.strictEqual(plugin.parseUtime('yesterday'), null);
  assert.strictEqual(plugin.parseUtime(''), null);
  assert.strictEqual(plugin.parseUtime(null), null);
  assert.strictEqual(plugin.parseUtime(undefined), null);
  assert.strictEqual(plugin.parseUtime({}), null);
});

// ---------------------------------------------------------------- the text

check('capText cuts at the cap and leaves shorter text alone', function () {
  assert.strictEqual(plugin.capText('a short post', 20000), 'a short post');
  assert.strictEqual(plugin.capText('0123456789', 4), '0123');
  assert.strictEqual(plugin.capText('  padded  ', 20000), 'padded');
});

check('capText survives rubbish', function () {
  assert.strictEqual(plugin.capText(null, 20000), '');
  assert.strictEqual(plugin.capText(42, 20000), '');
  assert.strictEqual(plugin.capText('text', 0), '');
});

// ----------------------------------------------------------- diagnostics

check('diagBody carries the install facts and what the pass saw', function () {
  var body = plugin.diagBody(
    { href: 'https://www.facebook.com/plugins/page.php', atDocumentStart: true },
    { wrappers: 5, posts: 5, elapsedMs: 2500, vis: 'hidden', iw: 500 },
    'NO_MORE_POSTS',
    40960
  );
  var parsed = JSON.parse(body);
  assert.strictEqual(parsed.end, 'NO_MORE_POSTS');
  assert.strictEqual(parsed.seen.posts, 5);
  assert.strictEqual(parsed.seen.vis, 'hidden');
  assert.strictEqual(parsed.install.atDocumentStart, true);
});

check('diagBody that will not fit drops what it saw rather than sending broken JSON', function () {
  var body = plugin.diagBody({ href: 'x' }, { note: new Array(400).join('a') }, 'TIMEOUT', 120);
  assert.ok(body.length <= 120, 'body was ' + body.length + ' chars');
  var parsed = JSON.parse(body);
  assert.strictEqual(parsed.end, 'TIMEOUT');
  assert.strictEqual(parsed.install.href, 'x');
});

check('diagBody survives rubbish', function () {
  assert.strictEqual(typeof plugin.diagBody(null, null, null, 40960), 'string');
  var circular = {};
  circular.self = circular;
  assert.strictEqual(plugin.diagBody(circular, {}, 'TIMEOUT', 40960), '');
});

console.log('\n' + passed + ' checks passed');
