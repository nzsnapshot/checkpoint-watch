/*
 * Checkpoint Watch collector.
 *
 * Injected at document start into https://www.facebook.com/CheckpointNZ by FeedCollector, before
 * any of Facebook's own scripts run. It is deliberately dumb: it closes the first login dialog,
 * scrolls, and ships raw response bodies to Kotlin, where all interpretation happens and is unit
 * tested. Nothing here parses a post.
 *
 * Reproduces the sequence verified live on 2026-09-18:
 *   load -> [role=dialog] "See more from ..." with an [aria-label="Close"] button -> really click
 *   it (hiding it does not unlock the feed) -> scroll to the bottom -> the page fetches
 *   /api/graphql -> after ~10 posts a second [role=dialog] with no Close button appears and the
 *   feed stops (hard login wall).
 *
 * Plain ES2017, no page globals other than the two install guards, everything in try/catch: a
 * throw here would be a scan that silently collects nothing.
 *
 * The pure decision helpers (isAgeText, isShown, dialogDecision, shouldEndOnWall, shouldStopOnStall,
 * pickPostText, compactText, diagBody) are exported when this file is loaded by node, so they can
 * be tested without a browser: see app/src/test/js/collector.test.js.
 *
 * It also keeps a small, bounded diagnostic log of every round and ships it as one `diag` message,
 * because the WebView this runs in cannot be inspected from the outside: when a scan on the
 * owner's phone finds one post, that log is the only evidence of why.
 */
(function () {
  'use strict';

  var IN_BROWSER = typeof window !== 'undefined' && typeof document !== 'undefined';

  if (IN_BROWSER) {
    try {
      if (window.__cwInstalled) {
        return;
      }
      window.__cwInstalled = true;
      // Document-start injection also runs in every same-origin iframe Facebook creates. Only the
      // top document has the feed, and only it should scroll, scrape or post messages.
      if (window.top !== window) {
        return;
      }
    } catch (e) {
      return;
    }
  }

  var MAX_BODY = 3 * 1024 * 1024; // bodies bigger than this are not a feed response
  var MAX_QUEUED_CHARS = 6 * 1024 * 1024; // pre-bridge queue, bounded by size rather than count
  var ROUND_MS = 1500;
  var MAX_ROUNDS = 24; // 24 x 1.5 s ~ 36 s, inside Kotlin's 45 s scan budget
  // Four rounds (~6 s) of no new article before the feed counts as finished. Three (~4.5 s) is
  // too quick on mobile data, where the next batch of posts is often still in flight.
  var STALL_ROUNDS = 4;
  var MIN_ROUNDS_BEFORE_EMPTY_STOP = 10; // never give up on an empty feed in the first ~15 s
  // How long a closeless sign-in dialog must persist before it is believed to be the hard wall:
  // quickly once posts have been seen, patiently while the page is still settling.
  var WALL_ROUNDS_AFTER_POSTS = 2;
  var WALL_ROUNDS_BEFORE_POSTS = 8;
  // The script's own wall clock. Kotlin's 45 s budget starts at loadUrl and this starts at document
  // start, so finishing at 38 s leaves room for the page load and still delivers the DOM fallback
  // (which is only ever sent from finish()) before Kotlin gives up.
  var MAX_ELAPSED_MS = 38000;
  var MAX_FAILED_ROUNDS = 5;
  var MAX_DOM_POSTS = 40;
  var MAX_DOM_TEXT = 20000;
  var MAX_AGE_TEXT = 16;
  var POST_ID = '"post_id"';

  // Diagnostics. All bounded: this is evidence, not a log file, and it travels over the bridge.
  var MAX_DIAG_ROUNDS = 30;
  var MAX_DIAG_CHARS = 40 * 1024;
  var MAX_DIAG_DIALOGS = 4;
  var MAX_DIALOG_TEXT = 40;
  var MAX_DIALOG_DESCENDANTS = 60;

  var startedAt = Date.now();
  var ended = false;
  var dumped = false;
  var queue = [];
  var queuedChars = 0;
  var flushTimer = null;
  var roundTimer = null;
  var rounds = 0;
  var lastCount = -1;
  var stalled = 0;
  var sawArticles = false;
  var wallRounds = 0;
  var failedRounds = 0;

  var diagInstall = null;
  var diagRounds = [];
  var pendingRound = null;
  var jsonCount = 0;
  var scriptCount = 0;
  var xhrWrapped = false;
  var fetchWrapped = false;

  // ---------------------------------------------------------------- bridge

  // cwBridge is injected by addWebMessageListener. It is normally there before this script runs,
  // but the order is not contractual, so messages are queued until it appears.
  function postNow(text) {
    try {
      if (window.cwBridge && typeof window.cwBridge.postMessage === 'function') {
        window.cwBridge.postMessage(text);
        return true;
      }
    } catch (e) {
      // fall through: treat as not delivered
    }
    return false;
  }

  function flush() {
    try {
      while (queue.length > 0) {
        if (!postNow(queue[0])) {
          return;
        }
        queuedChars -= queue[0].length;
        queue.shift();
      }
      queuedChars = 0;
      if (flushTimer !== null) {
        clearInterval(flushTimer);
        flushTimer = null;
      }
    } catch (e) {
      // ignore
    }
  }

  function send(message) {
    try {
      var text = JSON.stringify(message);
      if (typeof text !== 'string') {
        return;
      }
      if (queue.length === 0 && postNow(text)) {
        return;
      }
      if (queuedChars + text.length <= MAX_QUEUED_CHARS) {
        queue.push(text);
        queuedChars += text.length;
      }
      if (flushTimer === null) {
        flushTimer = setInterval(flush, 200);
      }
    } catch (e) {
      // ignore
    }
  }

  function sendBody(body) {
    try {
      if (typeof body !== 'string' || body.length === 0 || body.length > MAX_BODY) {
        return;
      }
      if (body.indexOf(POST_ID) === -1) {
        return;
      }
      jsonCount++;
      send({ t: 'json', body: body });
    } catch (e) {
      // ignore
    }
  }

  function isFeedUrl(url) {
    try {
      return typeof url === 'string' && url.indexOf('/api/graphql') !== -1;
    } catch (e) {
      return false;
    }
  }

  // ------------------------------------------------- response interception

  try {
    var xhrProto = window.XMLHttpRequest && window.XMLHttpRequest.prototype;
    if (xhrProto && typeof xhrProto.open === 'function' && typeof xhrProto.send === 'function') {
      var originalOpen = xhrProto.open;
      var originalSend = xhrProto.send;

      xhrProto.open = function (method, url) {
        try {
          this.__cwUrl = url;
        } catch (e) {
          // ignore
        }
        return originalOpen.apply(this, arguments);
      };

      xhrProto.send = function () {
        var request = this;
        try {
          request.addEventListener('load', function () {
            try {
              if (!isFeedUrl(request.__cwUrl) && !isFeedUrl(request.responseURL)) {
                return;
              }
              if (request.responseType === '' || request.responseType === 'text') {
                sendBody(request.responseText);
              }
            } catch (e) {
              // ignore
            }
          });
        } catch (e) {
          // ignore
        }
        return originalSend.apply(this, arguments);
      };
      xhrWrapped = true;
    }
  } catch (e) {
    // not a browser, or no XMLHttpRequest: nothing to wrap
  }

  try {
    if (typeof window.fetch === 'function') {
      var originalFetch = window.fetch;
      window.fetch = function (input) {
        var url = '';
        try {
          url = typeof input === 'string' ? input : (input && input.url) || '';
        } catch (e) {
          url = '';
        }
        var promise = originalFetch.apply(this, arguments);
        try {
          if (isFeedUrl(url) && promise && typeof promise.then === 'function') {
            promise.then(function (response) {
              try {
                // Read a clone so the page still gets its own untouched body.
                if (!response || typeof response.clone !== 'function') {
                  return;
                }
                response.clone().text().then(sendBody, function () {});
              } catch (e) {
                // ignore
              }
            }, function () {});
          }
        } catch (e) {
          // ignore
        }
        return promise;
      };
      fetchWrapped = true;
    }
  } catch (e) {
    // not a browser, or no fetch: nothing to wrap
  }

  // ------------------------------------------------------- initial payload

  // The newest post is only ever in a <script type="application/json"> block of the first HTML.
  function sendScriptBlocks() {
    try {
      var nodes = document.querySelectorAll('script[type="application/json"]');
      for (var i = 0; i < nodes.length; i++) {
        try {
          var node = nodes[i];
          if (node.__cwSent) {
            continue;
          }
          node.__cwSent = true;
          var text = node.textContent || '';
          if (text.indexOf(POST_ID) === -1 || text.length > MAX_BODY) {
            continue;
          }
          scriptCount++;
          send({ t: 'json', body: text });
        } catch (e) {
          // ignore this block
        }
      }
    } catch (e) {
      // ignore
    }
  }

  // ------------------------------------------------------------------ DOM

  /** Is this element's own computed style one that shows it? Says nothing about its size. */
  function isStyleShown(element) {
    try {
      if (element.getAttribute && element.getAttribute('aria-hidden') === 'true') {
        return false;
      }
      var style = window.getComputedStyle(element);
      if (!style) {
        return true;
      }
      if (style.display === 'none' || style.visibility === 'hidden') {
        return false;
      }
      var opacity = parseFloat(style.opacity);
      return isNaN(opacity) || opacity > 0.05;
    } catch (e) {
      return false;
    }
  }

  /**
   * Pure: is a dialog on screen, given its own style and the boxes measured around it?
   *
   * The wrapper's own rect is not evidence of anything. Measured on the live page, the
   * [role=dialog] wrapper had a rect of 0 x 625 — its content overflows a zero-width box — while
   * its Close button measured 36 x 36 and the dialog was plainly on screen. A wrapper-rect test
   * therefore calls the real login dialog hidden, which costs the scan both the click that
   * unlocks the feed and the ability to recognise the wall, and the scan ends with one post.
   *
   * So the style is the veto and the boxes are the evidence: any non-zero box anywhere — the
   * wrapper, the Close button, or one of the first descendants — means something is being drawn.
   *
   * @param styleOk  the dialog's own computed style shows it
   * @param boxes    [{ w, h }] measured around the dialog, in whatever order
   */
  function isShown(styleOk, boxes) {
    try {
      if (!styleOk || !boxes) {
        return false;
      }
      for (var i = 0; i < boxes.length; i++) {
        var box = boxes[i];
        if (box && box.w > 0 && box.h > 0) {
          return true;
        }
      }
      return false;
    } catch (e) {
      return false;
    }
  }

  function boxOf(element) {
    try {
      var rect = element.getBoundingClientRect();
      return rect ? { w: rect.width, h: rect.height } : { w: 0, h: 0 };
    } catch (e) {
      return { w: 0, h: 0 };
    }
  }

  /** Boxes of the dialog's first [MAX_DIALOG_DESCENDANTS] descendants; only asked for when needed. */
  function descendantBoxes(dialog) {
    var boxes = [];
    try {
      var nodes = dialog.querySelectorAll('*');
      var limit = Math.min(nodes.length, MAX_DIALOG_DESCENDANTS);
      for (var i = 0; i < limit; i++) {
        boxes.push(boxOf(nodes[i]));
      }
    } catch (e) {
      // ignore
    }
    return boxes;
  }

  // A dialog is the hard login wall only if it is actually asking us to sign in.
  function hasLoginSignal(dialog) {
    try {
      if (dialog.querySelector('input[type="password"]')) {
        return true;
      }
      if (dialog.querySelector('form[action*="login"], a[href*="/login"], a[href*="login.php"]')) {
        return true;
      }
      var text = (dialog.innerText || '').toLowerCase();
      return text.indexOf('log in') !== -1 || text.indexOf('sign up') !== -1;
    } catch (e) {
      return false;
    }
  }

  /**
   * Pure: has a closeless sign-in dialog been up long enough to call it the hard login wall?
   *
   * After the feed has produced posts this is the expected end of a scan, so two rounds are enough.
   * Before any post it is far more likely to be the page still settling (or a dialog whose Close
   * button has not rendered yet), so it has to persist much longer than the stall floor.
   */
  function shouldEndOnWall(wallRounds, sawArticlesYet) {
    if (!(wallRounds > 0)) {
      return false;
    }
    return wallRounds >= (sawArticlesYet ? WALL_ROUNDS_AFTER_POSTS : WALL_ROUNDS_BEFORE_POSTS);
  }

  /**
   * Pure: has the feed stopped growing for long enough to call it finished?
   *
   * An empty feed is not a finished feed: until a single article has rendered, the stall counter
   * says nothing, so the page gets MIN_ROUNDS_BEFORE_EMPTY_STOP rounds before "no change" is
   * allowed to mean anything at all.
   */
  function shouldStopOnStall(stalled, sawArticlesYet, rounds) {
    if (!sawArticlesYet && !(rounds >= MIN_ROUNDS_BEFORE_EMPTY_STOP)) {
      return false;
    }
    return stalled >= STALL_ROUNDS;
  }

  /**
   * Pure decision over the facts gathered from every dialog on the page.
   * facts: [{ visible: boolean, hasClose: boolean, loginSignal: boolean }]
   * Returns 'closed' (something was closable), 'wall' (only a closeless sign-in dialog), 'none'.
   */
  function dialogDecision(facts) {
    var closed = false;
    var wall = false;
    try {
      for (var i = 0; i < facts.length; i++) {
        var fact = facts[i] || {};
        if (!fact.visible) {
          continue;
        }
        if (fact.hasClose) {
          closed = true;
        } else if (fact.loginSignal) {
          wall = true;
        }
      }
    } catch (e) {
      return 'none';
    }
    if (closed) {
      return 'closed';
    }
    return wall ? 'wall' : 'none';
  }

  /**
   * Looks at EVERY dialog, clicks every Close inside a shown one, and reports what is left.
   *
   * Returns { state, dialogs }: the decision, and up to [MAX_DIAG_DIALOGS] records of what each
   * dialog measured and what was done to it, for the round log.
   */
  function handleDialogs() {
    var facts = [];
    var seen = [];
    try {
      var dialogs = document.querySelectorAll('[role="dialog"]');
      for (var i = 0; i < dialogs.length; i++) {
        try {
          var dialog = dialogs[i];
          var closes = dialog.querySelectorAll('[aria-label="Close"]');
          var wrapperBox = boxOf(dialog);
          var closeBox = closes.length > 0 ? boxOf(closes[0]) : { w: 0, h: 0 };
          var styleOk = isStyleShown(dialog);

          // The wrapper and the Close button first, because they are two rects rather than sixty;
          // the descendants are only measured when neither of them proves anything.
          var shown = isShown(styleOk, [wrapperBox, closeBox]);
          if (!shown && styleOk) {
            shown = isShown(styleOk, descendantBoxes(dialog));
          }

          // Never skip a Close because of the wrapper's own box: the wrapper is exactly the thing
          // that measured zero on the phone, and the button it holds is what unlocks the feed.
          var clicked = 0;
          if (shown) {
            for (var j = 0; j < closes.length; j++) {
              try {
                // The real button has to be clicked: hiding the dialog does not unlock the feed.
                closes[j].click();
                clicked++;
              } catch (e) {
                // skip this button
              }
            }
          }

          var hasClose = closes.length > 0;
          var login = shown && !hasClose ? hasLoginSignal(dialog) : false;
          facts.push({ visible: shown, hasClose: hasClose, loginSignal: login });

          if (seen.length < MAX_DIAG_DIALOGS) {
            seen.push({
              w: rounded(wrapperBox.w),
              h: rounded(wrapperBox.h),
              styleOk: styleOk,
              shown: shown,
              close: hasClose,
              cw: rounded(closeBox.w),
              ch: rounded(closeBox.h),
              clicked: clicked,
              login: login,
              txt: compactText(dialogText(dialog), MAX_DIALOG_TEXT)
            });
          }
        } catch (e) {
          // skip this dialog
        }
      }
    } catch (e) {
      return { state: 'none', dialogs: seen };
    }
    return { state: dialogDecision(facts), dialogs: seen };
  }

  function dialogText(dialog) {
    try {
      return dialog.innerText || '';
    } catch (e) {
      return '';
    }
  }

  function topLevelArticles() {
    var articles = [];
    try {
      var all = document.querySelectorAll('[role="article"]');
      for (var i = 0; i < all.length; i++) {
        try {
          var element = all[i];
          // Comments and shared posts are articles nested inside articles; only the outer one is
          // a post of the page.
          if (element.parentElement && element.parentElement.closest('[role="article"]')) {
            continue;
          }
          var text = (element.innerText || '').trim();
          if (text.length < 20) {
            continue;
          }
          articles.push(element);
        } catch (e) {
          // skip this article
        }
      }
    } catch (e) {
      // ignore
    }
    return articles;
  }

  /** Every [role=article] on the page, nested ones included: the raw count, for the round log. */
  function allArticleCount() {
    try {
      return document.querySelectorAll('[role="article"]').length;
    } catch (e) {
      return 0;
    }
  }

  function expandSeeMore(articles) {
    try {
      for (var i = 0; i < articles.length; i++) {
        try {
          var buttons = articles[i].querySelectorAll('[role="button"]');
          for (var j = 0; j < buttons.length; j++) {
            var button = buttons[j];
            if (button.__cwClicked) {
              continue;
            }
            var label = (button.innerText || '').trim().toLowerCase();
            if (label === 'see more') {
              button.__cwClicked = true;
              button.click();
            }
          }
        } catch (e) {
          // skip this article
        }
      }
    } catch (e) {
      // ignore
    }
  }

  /** Scrolls everything that can be scrolled. Returns whether a fixed scroller was found. */
  function scrollAll() {
    var fixedScroller = false;
    try {
      window.scrollTo(0, document.documentElement.scrollHeight);
    } catch (e) {
      // ignore
    }
    try {
      // While a dialog is up Facebook pins the feed inside a position:fixed scroller, and the
      // window no longer scrolls. Scroll those ancestors of [role=main] as well.
      var node = document.querySelector('[role="main"]');
      var hops = 0;
      while (node && node !== document.body && hops < 30) {
        hops++;
        try {
          var style = window.getComputedStyle(node);
          if (
            style &&
            style.position === 'fixed' &&
            node.clientHeight > 200 &&
            node.scrollHeight > node.clientHeight
          ) {
            node.scrollTop = node.scrollHeight;
            fixedScroller = true;
          }
        } catch (e) {
          // skip this ancestor
        }
        node = node.parentElement;
      }
    } catch (e) {
      // ignore
    }
    return fixedScroller;
  }

  var AGE_PATTERN = new RegExp(
    '^(just now|\\d+\\s*(s|sec|secs|second|seconds|m|min|mins|minute|minutes|h|hr|hrs|hour|hours|' +
      'd|day|days|w|wk|wks|week|weeks|y|yr|yrs|year|years)(\\s+ago)?)$',
    'i'
  );

  /** Pure: is this the short relative age Facebook puts in a post header ("22m", "2 hrs ago")? */
  function isAgeText(text) {
    try {
      if (typeof text !== 'string') {
        return false;
      }
      var trimmed = text.trim();
      if (trimmed.length === 0 || trimmed.length > MAX_AGE_TEXT) {
        return false;
      }
      return AGE_PATTERN.test(trimmed);
    } catch (e) {
      return false;
    }
  }

  function articleAge(article) {
    try {
      var links = article.querySelectorAll('a[href]');
      for (var i = 0; i < links.length; i++) {
        var text = (links[i].innerText || '').trim();
        if (isAgeText(text)) {
          return text;
        }
      }
    } catch (e) {
      // ignore
    }
    return '';
  }

  function articleLink(article) {
    try {
      var anchor = article.querySelector('a[href*="/posts/"]');
      if (anchor && anchor.href) {
        return String(anchor.href).split('?')[0].split('#')[0];
      }
    } catch (e) {
      // ignore
    }
    return null;
  }

  // Where Facebook puts the text the author actually wrote, inside all the article's chrome.
  var MESSAGE_SELECTOR =
    '[data-ad-preview="message"], [data-ad-comet-preview="message"], ' +
    '[data-ad-rendering-role="story_message"]';

  /**
   * Pure: the post's own text if the message element gave us one, else the whole article.
   *
   * The article's innerText is the post plus the page name, the age, the reaction counts and the
   * Like/Comment/Share row — noise that changes between scans, so it changes the text hash and
   * stops the post ever bridging onto the JSON feed's message.text. Kotlin cleans what it is
   * given (DomPostExtractor.cleanText); this is the cheaper first cut, when the page tells us.
   */
  function pickPostText(messageText, articleText) {
    var message = typeof messageText === 'string' ? messageText.trim() : '';
    if (message.length > 0) {
      return message;
    }
    return typeof articleText === 'string' ? articleText.trim() : '';
  }

  function articleText(article) {
    var message = '';
    try {
      var node = article.querySelector(MESSAGE_SELECTOR);
      if (node) {
        message = node.innerText || '';
      }
    } catch (e) {
      message = '';
    }
    return pickPostText(message, article.innerText || '');
  }

  function domPosts() {
    var posts = [];
    try {
      var articles = topLevelArticles();
      for (var i = 0; i < articles.length && posts.length < MAX_DOM_POSTS; i++) {
        try {
          var text = articleText(articles[i]);
          if (!text) {
            continue;
          }
          posts.push({
            text: text.length > MAX_DOM_TEXT ? text.substring(0, MAX_DOM_TEXT) : text,
            age: articleAge(articles[i]),
            link: articleLink(articles[i])
          });
        } catch (e) {
          // skip this article
        }
      }
    } catch (e) {
      // ignore
    }
    return posts;
  }

  // ---------------------------------------------------------- diagnostics
  //
  // Nothing below may ever change what the scan does. Every entry point is wrapped, every helper
  // returns a harmless default, and the whole log is bounded — 30 rounds, 4 dialogs each, 40
  // characters of dialog text — so it stays evidence rather than becoming a payload.
  //
  // It carries no personal data: no cookies, no tokens, no post text, and URLs are stripped of
  // their query and fragment before they are recorded.

  function rounded(value) {
    try {
      return typeof value === 'number' && isFinite(value) ? Math.round(value) : 0;
    } catch (e) {
      return 0;
    }
  }

  /** Pure: one line of text, newlines flattened to spaces, cut to [max] characters. */
  function compactText(text, max) {
    try {
      if (typeof text !== 'string' || typeof max !== 'number' || !(max > 0)) {
        return '';
      }
      var flat = text.replace(/[\r\n]+/g, ' ').trim();
      return flat.length > max ? flat.substring(0, max) : flat;
    } catch (e) {
      return '';
    }
  }

  /** A URL with its query and fragment removed, so nothing identifying can ride along. */
  function stripUrl(url) {
    try {
      return String(url).split('?')[0].split('#')[0];
    } catch (e) {
      return '';
    }
  }

  function viewportMeta() {
    try {
      var meta = document.querySelector('meta[name="viewport"]');
      return meta ? meta.getAttribute('content') : null;
    } catch (e) {
      return null;
    }
  }

  /**
   * The facts that are only true once, recorded when the script installs.
   *
   * At document start there is no `<head>` yet, so the viewport meta is almost always missing
   * here; [refreshInstall] picks it up on the first round that can see it.
   */
  function recordInstall() {
    try {
      var ready = '';
      try {
        ready = document.readyState;
      } catch (e) {
        ready = '';
      }
      var noBody = true;
      try {
        noBody = !document.body;
      } catch (e) {
        noBody = true;
      }
      var mobile = null;
      try {
        mobile = navigator.userAgentData ? !!navigator.userAgentData.mobile : null;
      } catch (e) {
        mobile = null;
      }
      diagInstall = {
        href: stripUrl(location.href),
        ua: navigator.userAgent,
        uaMobile: mobile,
        viewport: viewportMeta(),
        dpr: window.devicePixelRatio,
        xhrWrapped: xhrWrapped,
        fetchWrapped: fetchWrapped,
        ready: ready,
        // Injected before the page's own scripts? Nothing was parsed yet if so.
        atDocumentStart: ready === 'loading' && noBody
      };
    } catch (e) {
      diagInstall = null;
    }
  }

  function refreshInstall() {
    try {
      if (diagInstall && diagInstall.viewport === null) {
        diagInstall.viewport = viewportMeta();
      }
    } catch (e) {
      // ignore
    }
  }

  /**
   * Starts this round's record. `sy` and `dh` are read before the round scrolls, so they say what
   * the PREVIOUS round's scroll actually achieved — which is the question when a feed never grows.
   */
  function startRound() {
    try {
      pendingRound = {
        r: rounds,
        ms: Date.now() - startedAt,
        vis: document.visibilityState,
        iw: window.innerWidth,
        ih: window.innerHeight,
        dh: document.documentElement ? document.documentElement.scrollHeight : 0,
        sy: rounded(window.scrollY),
        arts: 0,
        allArts: 0,
        dlg: [],
        fixedScroller: false,
        json: jsonCount,
        scripts: scriptCount,
        state: 'none',
        stalled: stalled,
        wallRounds: wallRounds
      };
    } catch (e) {
      pendingRound = null;
    }
  }

  /**
   * Files the round in progress. Called from the end of every round and from the top of
   * [finish], so the round that decided to end the scan is in the log that reports the ending.
   */
  function flushRound() {
    try {
      var record = pendingRound;
      pendingRound = null;
      if (record && diagRounds.length < MAX_DIAG_ROUNDS) {
        diagRounds.push(record);
      }
    } catch (e) {
      // ignore
    }
  }

  function noteRound(field, value) {
    try {
      if (pendingRound) {
        pendingRound[field] = value;
      }
    } catch (e) {
      // ignore
    }
  }

  /**
   * Pure: the whole diagnostic payload as a JSON string of at most [maxChars] characters.
   *
   * When it does not fit, rounds are dropped from the middle: the first round (what the page
   * looked like when the script arrived) and the last ones (how it ended) are the two halves of
   * the story, and the repetitive middle of a stalled scan is the part worth losing.
   */
  function diagBody(install, rounds_, end, maxChars) {
    try {
      var list = (rounds_ || []).slice();
      for (;;) {
        var text = JSON.stringify({ install: install, rounds: list, end: end });
        if (typeof text !== 'string') {
          return '';
        }
        if (text.length <= maxChars || list.length <= 1) {
          return text;
        }
        list.splice(1, 1);
      }
    } catch (e) {
      return '';
    }
  }

  function sendDiag(reason) {
    try {
      flushRound();
      if (!diagInstall) {
        recordInstall();
      }
      var body = diagBody(diagInstall, diagRounds, reason, MAX_DIAG_CHARS);
      if (body) {
        send({ t: 'diag', body: body });
      }
    } catch (e) {
      // ignore
    }
  }

  // ----------------------------------------------------------------- loop

  function stopLoop() {
    try {
      if (roundTimer !== null) {
        clearInterval(roundTimer);
        roundTimer = null;
      }
    } catch (e) {
      // ignore
    }
  }

  /**
   * Ships the DOM fallback without ending the scan. Kotlin calls this through window.__cwDump when
   * its own timeout fires, so a page that loaded too slowly for the loop to finish still yields the
   * posts it did render. Sends at most one dump, and nothing at all once finish() has sent its own.
   */
  function dump() {
    try {
      if (ended || dumped) {
        return;
      }
      dumped = true;
      sendScriptBlocks();
      send({ t: 'dom', posts: domPosts() });
      // Kotlin's own clock ran out, so this is the last word on what happened.
      sendDiag('DUMP');
      flush();
    } catch (e) {
      // ignore
    }
  }

  function finish(reason) {
    if (ended) {
      return;
    }
    ended = true;
    stopLoop();
    sendScriptBlocks();
    send({ t: 'dom', posts: domPosts() });
    sendDiag(reason);
    send({ t: 'end', reason: reason });
    flush();
  }

  function round() {
    if (ended) {
      stopLoop();
      return;
    }
    try {
      rounds++;
      startRound();
      refreshInstall();

      // The script's own budget, so the DOM fallback is always sent before Kotlin's clock runs out.
      if (Date.now() - startedAt >= MAX_ELAPSED_MS) {
        finish('TIMEOUT');
        return;
      }

      var dialogs = handleDialogs();
      var dialogState = dialogs.state;
      noteRound('dlg', dialogs.dialogs);
      noteRound('state', dialogState);

      var articles = topLevelArticles();
      if (articles.length > 0) {
        sawArticles = true;
      }
      noteRound('arts', articles.length);
      noteRound('allArts', allArticleCount());

      wallRounds = dialogState === 'wall' ? wallRounds + 1 : 0;
      noteRound('wallRounds', wallRounds);
      if (shouldEndOnWall(wallRounds, sawArticles)) {
        finish('LOGIN_WALL');
        return;
      }

      expandSeeMore(articles);
      noteRound('fixedScroller', scrollAll());

      if (articles.length === lastCount) {
        stalled++;
      } else {
        stalled = 0;
        lastCount = articles.length;
      }
      noteRound('stalled', stalled);

      if (shouldStopOnStall(stalled, sawArticles, rounds) || rounds >= MAX_ROUNDS) {
        finish('NO_MORE_POSTS');
        return;
      }
      failedRounds = 0;
    } catch (e) {
      // One bad round (a detached node, a selector Facebook changed) skips; only a run of them
      // means this page is not one we can work with.
      failedRounds++;
      if (failedRounds >= MAX_FAILED_ROUNDS) {
        try {
          finish('NO_MORE_POSTS');
        } catch (e2) {
          // ignore
        }
      }
    }
    // Whatever the round did or threw, its record is filed. A no-op once finish() filed it.
    flushRound();
  }

  function start() {
    try {
      if (window.__cwStarted) {
        return;
      }
      window.__cwStarted = true;
      sendScriptBlocks();
      // An early first round closes the dialog as soon as it appears; the rest are paced.
      setTimeout(round, 300);
      roundTimer = setInterval(round, ROUND_MS);
    } catch (e) {
      // ignore
    }
  }

  if (IN_BROWSER) {
    // Before anything else is done, and never allowed to fail: this is the record of the
    // environment the script arrived in, which is half of what a puzzling scan needs explaining.
    recordInstall();
  }

  try {
    // The one hook Kotlin calls into: a last-chance DOM dump when its own timeout fires.
    window.__cwDump = dump;
  } catch (e) {
    // ignore
  }

  try {
    if (document.readyState === 'interactive' || document.readyState === 'complete') {
      start();
    } else {
      document.addEventListener('DOMContentLoaded', start);
      window.addEventListener('load', start);
    }
  } catch (e) {
    // not a browser: nothing to start
  }

  // Only ever outside a browser (node, for the tests). A page that happens to define window.module
  // must never have its exports overwritten by us.
  try {
    if (!IN_BROWSER && typeof module !== 'undefined' && module.exports) {
      module.exports = {
        isAgeText: isAgeText,
        isShown: isShown,
        dialogDecision: dialogDecision,
        shouldEndOnWall: shouldEndOnWall,
        shouldStopOnStall: shouldStopOnStall,
        pickPostText: pickPostText,
        compactText: compactText,
        diagBody: diagBody
      };
    }
  } catch (e) {
    // ignore
  }
})();
