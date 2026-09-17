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

console.log('\n' + passed + ' checks passed');
