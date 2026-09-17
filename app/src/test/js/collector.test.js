/*
 * Node tests for the pure decision helpers in app/src/main/assets/collector.js.
 *
 * The rest of the script needs a DOM and Facebook's page, so it can only be verified on a device;
 * these two functions are where the collector's judgement lives, so they are tested here.
 *
 * Run: node app/src/test/js/collector.test.js   (not part of the Gradle build: no JS toolchain)
 */
'use strict';

var assert = require('assert');
var collector = require('../../main/assets/collector.js');

var passed = 0;

function check(name, fn) {
  fn();
  passed++;
  console.log('ok - ' + name);
}

check('isAgeText accepts the ages Facebook renders', function () {
  ['22m', '5 m', '2h', '3 hrs', '1 d', '2 days ago', '45s', 'Just now', 'just now', '1 w', '2 yrs'].forEach(
    function (text) {
      assert.strictEqual(collector.isAgeText(text), true, text);
    }
  );
});

check('isAgeText rejects road names and other article text', function () {
  [
    '5 Mile',
    '5 Mile Point',
    'Lincoln Road',
    'CHECKPOINT',
    '',
    '   ',
    '12',
    'm',
    '2 hours and a bit',
    '11:55PM',
    'See more',
    null,
    undefined,
    42
  ].forEach(function (text) {
    assert.strictEqual(collector.isAgeText(text), false, String(text));
  });
});

check('isAgeText rejects anything longer than a real age label', function () {
  assert.strictEqual(collector.isAgeText('2 hours ago exactly'), false);
  assert.strictEqual(collector.isAgeText('123456789 minutes'), false);
});

check('dialogDecision: nothing on screen', function () {
  assert.strictEqual(collector.dialogDecision([]), 'none');
  assert.strictEqual(
    collector.dialogDecision([{ visible: false, hasClose: false, loginSignal: true }]),
    'none'
  );
});

check('dialogDecision: the first dialog, which can be closed', function () {
  assert.strictEqual(
    collector.dialogDecision([{ visible: true, hasClose: true, loginSignal: true }]),
    'closed'
  );
});

check('dialogDecision: a closeable dialog anywhere wins over a closeless one', function () {
  // Document order must not decide this: a closeless dialog earlier in the DOM used to end the
  // scan before the real, closeable one was ever looked at.
  assert.strictEqual(
    collector.dialogDecision([
      { visible: true, hasClose: false, loginSignal: true },
      { visible: true, hasClose: true, loginSignal: false }
    ]),
    'closed'
  );
});

check('dialogDecision: the wall is a visible, closeless, sign-in dialog', function () {
  assert.strictEqual(
    collector.dialogDecision([{ visible: true, hasClose: false, loginSignal: true }]),
    'wall'
  );
});

check('dialogDecision: a closeless dialog with no sign-in signal is not the wall', function () {
  assert.strictEqual(
    collector.dialogDecision([{ visible: true, hasClose: false, loginSignal: false }]),
    'none'
  );
});

check('dialogDecision: hidden scaffolding is ignored', function () {
  assert.strictEqual(
    collector.dialogDecision([
      { visible: false, hasClose: false, loginSignal: true },
      { visible: false, hasClose: true, loginSignal: false }
    ]),
    'none'
  );
});

check('dialogDecision survives rubbish input', function () {
  assert.strictEqual(collector.dialogDecision([null, undefined, {}]), 'none');
});

check('shouldEndOnWall: never without a wall this round', function () {
  assert.strictEqual(collector.shouldEndOnWall(0, true), false);
  assert.strictEqual(collector.shouldEndOnWall(0, false), false);
  assert.strictEqual(collector.shouldEndOnWall(undefined, true), false);
});

check('shouldEndOnWall: two rounds are enough once posts have been seen', function () {
  assert.strictEqual(collector.shouldEndOnWall(1, true), false);
  assert.strictEqual(collector.shouldEndOnWall(2, true), true);
  assert.strictEqual(collector.shouldEndOnWall(5, true), true);
});

check('shouldEndOnWall: an empty feed gets far longer than the stall floor', function () {
  // ~12 s, so a dialog whose Close button has not rendered yet cannot end an empty scan early.
  [1, 2, 3, 4, 5, 6, 7].forEach(function (rounds) {
    assert.strictEqual(collector.shouldEndOnWall(rounds, false), false, 'round ' + rounds);
  });
  assert.strictEqual(collector.shouldEndOnWall(8, false), true);
  assert.strictEqual(collector.shouldEndOnWall(9, false), true);
});

check('shouldStopOnStall: the phone case — placeholders pending and no feed response yet', function () {
  // From the owner's v1.0.1 log: the dialog was closed, one post had rendered, three more were
  // still empty skeletons, no graphql body had arrived, and the scan gave up after four stalled
  // rounds with 29 s of its budget unspent. That must never happen again.
  assert.strictEqual(collector.shouldStopOnStall(4, true, 5, 3, 0), false);
});

check('shouldStopOnStall: an empty feed is never given up on early', function () {
  // Nothing has rendered yet: the stall counter must not end the scan until the page has had
  // MIN_ROUNDS_BEFORE_EMPTY_STOP (10) rounds to produce a first article.
  [1, 2, 3, 4, 5, 9].forEach(function (rounds) {
    assert.strictEqual(collector.shouldStopOnStall(8, false, rounds, 0, 1), false, 'round ' + rounds);
  });
  assert.strictEqual(collector.shouldStopOnStall(8, false, 10, 0, 1), true);
});

check('shouldStopOnStall: eight stalled rounds once the feed has grown and answered', function () {
  // ~12 s of no new article, with nothing pending and a feed response already in hand: this is
  // the only cheap way a scan is allowed to end, because the honest endings are the sign-in wall
  // and the script's own clock.
  assert.strictEqual(collector.shouldStopOnStall(4, true, 6, 0, 2), false);
  assert.strictEqual(collector.shouldStopOnStall(7, true, 9, 0, 2), false);
  assert.strictEqual(collector.shouldStopOnStall(8, true, 10, 0, 2), true);
});

check('shouldStopOnStall: a pending placeholder holds the scan open', function () {
  // A skeleton article is the page telling us a post is on its way.
  assert.strictEqual(collector.shouldStopOnStall(8, true, 10, 1, 5), false);
  assert.strictEqual(collector.shouldStopOnStall(15, true, 16, 1, 5), false);
});

check('shouldStopOnStall: no graphql body yet holds the scan open too', function () {
  // Nothing has been forwarded, so there is nothing to show for the scan whatever the DOM says.
  assert.strictEqual(collector.shouldStopOnStall(8, true, 10, 0, 0), false);
  assert.strictEqual(collector.shouldStopOnStall(15, true, 16, 0, 0), false);
});

check('shouldStopOnStall: sixteen stalled rounds end it whatever is pending', function () {
  // ~24 s of a page that is not moving. Past here the wait is no longer patience.
  assert.strictEqual(collector.shouldStopOnStall(16, true, 17, 3, 0), true);
  assert.strictEqual(collector.shouldStopOnStall(16, false, 17, 3, 0), true);
  // ...but not before the empty-feed floor.
  assert.strictEqual(collector.shouldStopOnStall(16, false, 9, 3, 0), false);
});

check('pickPostText prefers the message element over the whole article', function () {
  assert.strictEqual(
    collector.pickPostText('CHECKPOINT - Lincoln Road', 'Checkpoint Watch Auckland\n22m\nCHECKPOINT - Lincoln Road\nLike'),
    'CHECKPOINT - Lincoln Road'
  );
});

check('pickPostText falls back to the article when there is no message element', function () {
  var article = 'Checkpoint Watch Auckland\n22m\nCHECKPOINT - Lincoln Road';
  assert.strictEqual(collector.pickPostText('', article), article);
  assert.strictEqual(collector.pickPostText('   ', article), article);
  assert.strictEqual(collector.pickPostText(null, article), article);
  assert.strictEqual(collector.pickPostText(undefined, article), article);
});

check('pickPostText trims, and survives rubbish', function () {
  assert.strictEqual(collector.pickPostText('  message  ', 'article'), 'message');
  assert.strictEqual(collector.pickPostText(null, null), '');
  assert.strictEqual(collector.pickPostText(42, 7), '');
});

// ------------------------------------------------------------------ isShown

check('isShown: the measured phone case — a zero-width wrapper around a real dialog', function () {
  // Measured live on the page: the [role=dialog] wrapper's own rect was 0 x 625 (its content
  // overflows a zero-width box) while its Close button measured 36 x 36 and the dialog was
  // plainly on screen. The old wrapper-rect test called that hidden, so the scan neither closed
  // the dialog nor recognised the wall, and ended NO_MORE_POSTS with one post.
  assert.strictEqual(collector.isShown(true, [{ w: 0, h: 625 }, { w: 36, h: 36 }]), true);
});

check('isShown: a non-zero wrapper is enough on its own', function () {
  assert.strictEqual(collector.isShown(true, [{ w: 486, h: 625 }]), true);
});

check('isShown: a descendant with a box is enough', function () {
  assert.strictEqual(
    collector.isShown(true, [{ w: 0, h: 0 }, { w: 0, h: 0 }, { w: 300, h: 40 }]),
    true
  );
});

check('isShown: a dialog whose own style hides it is never shown', function () {
  // display:none, visibility:hidden, aria-hidden, opacity 0 — whatever the boxes say.
  assert.strictEqual(collector.isShown(false, [{ w: 486, h: 625 }]), false);
  assert.strictEqual(collector.isShown(false, [{ w: 0, h: 625 }, { w: 36, h: 36 }]), false);
});

check('isShown: nothing measurable anywhere is not shown', function () {
  assert.strictEqual(collector.isShown(true, []), false);
  assert.strictEqual(collector.isShown(true, [{ w: 0, h: 0 }, { w: 0, h: 120 }]), false);
});

check('isShown survives rubbish input', function () {
  assert.strictEqual(collector.isShown(true, null), false);
  assert.strictEqual(collector.isShown(true, [null, undefined, {}]), false);
  assert.strictEqual(collector.isShown(undefined, undefined), false);
});

// ----------------------------------------------------------------- viewport

check('desiredViewport is the minimal desktop width, and nothing else', function () {
  // "width=1280" alone is what a browser's "Desktop site" does. An initial-scale or a
  // shrink-to-fit would pull the layout back towards the device's own width, which is the whole
  // problem being fixed.
  assert.strictEqual(collector.desiredViewport(), 'width=1280');
});

check('needsViewportRewrite: Facebook\'s own desktop viewport must be replaced', function () {
  // Measured on the live desktop page. width=device-width on a 1280 physical-pixel WebView at
  // density 2.75 is a CSS viewport of ~465px, so the desktop site lays out phone-narrow.
  assert.strictEqual(
    collector.needsViewportRewrite(
      'width=device-width,initial-scale=1,maximum-scale=2,shrink-to-fit=no'
    ),
    true
  );
});

check('needsViewportRewrite: a viewport that is already ours is left alone', function () {
  assert.strictEqual(collector.needsViewportRewrite('width=1280'), false);
  assert.strictEqual(collector.needsViewportRewrite('  width=1280  '), false);
  assert.strictEqual(collector.needsViewportRewrite('width = 1280'), false);
  assert.strictEqual(collector.needsViewportRewrite('WIDTH=1280'), false);
});

check('needsViewportRewrite: a missing or empty viewport needs writing', function () {
  assert.strictEqual(collector.needsViewportRewrite(''), true);
  assert.strictEqual(collector.needsViewportRewrite('width=1024'), true);
  assert.strictEqual(collector.needsViewportRewrite('width=1280,initial-scale=1'), true);
});

check('needsViewportRewrite: rubbish is left alone rather than guessed at', function () {
  // Not a string at all: there is nothing to compare, and writing on a hunch is how a scan breaks.
  assert.strictEqual(collector.needsViewportRewrite(null), true);
  assert.strictEqual(collector.needsViewportRewrite(undefined), true);
  assert.strictEqual(collector.needsViewportRewrite(42), true);
});

// -------------------------------------------------------------- diagnostics

check('compactText: newlines become spaces and the text is cut to length', function () {
  assert.strictEqual(collector.compactText('See more from\nCheckpoint Watch', 40), 'See more from Checkpoint Watch');
  assert.strictEqual(collector.compactText('0123456789', 4), '0123');
  assert.strictEqual(collector.compactText('  padded  ', 40), 'padded');
  assert.strictEqual(collector.compactText('a\r\n\nb', 40), 'a b');
});

check('compactText survives rubbish', function () {
  assert.strictEqual(collector.compactText(null, 40), '');
  assert.strictEqual(collector.compactText(42, 40), '');
  assert.strictEqual(collector.compactText('text', 0), '');
  assert.strictEqual(collector.compactText('text', -1), '');
});

check('diagBody: a small payload is sent whole', function () {
  var body = collector.diagBody({ href: 'https://www.facebook.com/CheckpointNZ' }, [{ r: 1 }, { r: 2 }], 'LOGIN_WALL', 40960);
  var parsed = JSON.parse(body);
  assert.strictEqual(parsed.end, 'LOGIN_WALL');
  assert.strictEqual(parsed.rounds.length, 2);
  assert.strictEqual(parsed.install.href, 'https://www.facebook.com/CheckpointNZ');
});

check('diagBody: an oversized payload drops the repetitive middle, keeping the first and last rounds', function () {
  var rounds = [];
  for (var i = 1; i <= 30; i++) {
    rounds.push({ r: i, ms: i * 1500, txt: 'a round that is not especially short at all' });
  }
  var body = collector.diagBody({ href: 'x' }, rounds, 'NO_MORE_POSTS', 400);
  assert.ok(body.length <= 400, 'body was ' + body.length + ' chars');
  var parsed = JSON.parse(body);
  assert.strictEqual(parsed.rounds[0].r, 1);
  assert.strictEqual(parsed.rounds[parsed.rounds.length - 1].r, 30);
  assert.strictEqual(parsed.end, 'NO_MORE_POSTS');
});

check('diagBody survives rubbish', function () {
  assert.strictEqual(typeof collector.diagBody(null, null, null, 40960), 'string');
  // A structure that cannot be stringified must cost the diagnostics, never the scan.
  var circular = {};
  circular.self = circular;
  assert.strictEqual(collector.diagBody(circular, [], 'TIMEOUT', 40960), '');
});

console.log('\n' + passed + ' checks passed');
