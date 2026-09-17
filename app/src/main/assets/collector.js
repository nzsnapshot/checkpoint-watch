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
 * The pure decision helpers (isAgeText, dialogDecision) are exported when this file is loaded by
 * node, so they can be tested without a browser: see app/src/test/js/collector.test.js.
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
  var STALL_ROUNDS = 3;
  var MIN_ROUNDS_BEFORE_EMPTY_STOP = 10; // never give up on an empty feed in the first ~15 s
  var WALL_ROUNDS = 3; // a closeless dialog before any post must persist this long to be the wall
  var MAX_FAILED_ROUNDS = 5;
  var MAX_DOM_POSTS = 40;
  var MAX_DOM_TEXT = 20000;
  var MAX_AGE_TEXT = 16;
  var POST_ID = '"post_id"';

  var ended = false;
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

  function isTrulyVisible(element) {
    try {
      var rect = element.getBoundingClientRect();
      if (!rect || rect.width <= 0 || rect.height <= 0) {
        return false;
      }
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
      return text.indexOf('log in') !== -1 || text.indexOf('log into') !== -1 || text.indexOf('sign up') !== -1;
    } catch (e) {
      return false;
    }
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

  // Looks at EVERY dialog, clicks every Close it finds, and reports what is left.
  function handleDialogs() {
    var facts = [];
    try {
      var dialogs = document.querySelectorAll('[role="dialog"]');
      for (var i = 0; i < dialogs.length; i++) {
        try {
          var dialog = dialogs[i];
          var visible = isTrulyVisible(dialog);
          var close = visible ? dialog.querySelector('[aria-label="Close"]') : null;
          if (close) {
            // The real button has to be clicked: hiding the dialog does not unlock the feed.
            close.click();
          }
          facts.push({
            visible: visible,
            hasClose: !!close,
            loginSignal: visible && !close ? hasLoginSignal(dialog) : false
          });
        } catch (e) {
          // skip this dialog
        }
      }
    } catch (e) {
      return 'none';
    }
    return dialogDecision(facts);
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

  function scrollAll() {
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
          }
        } catch (e) {
          // skip this ancestor
        }
        node = node.parentElement;
      }
    } catch (e) {
      // ignore
    }
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

  function domPosts() {
    var posts = [];
    try {
      var articles = topLevelArticles();
      for (var i = 0; i < articles.length && posts.length < MAX_DOM_POSTS; i++) {
        try {
          var text = (articles[i].innerText || '').trim();
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

  function finish(reason) {
    if (ended) {
      return;
    }
    ended = true;
    stopLoop();
    sendScriptBlocks();
    send({ t: 'dom', posts: domPosts() });
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

      var dialogState = handleDialogs();
      var articles = topLevelArticles();
      if (articles.length > 0) {
        sawArticles = true;
      }

      // A closeless sign-in dialog is the terminal wall once posts have been seen. Before any post
      // it may just be the page still settling, so it has to persist for a few rounds.
      wallRounds = dialogState === 'wall' ? wallRounds + 1 : 0;
      if (wallRounds > 0 && (sawArticles || wallRounds >= WALL_ROUNDS)) {
        finish('LOGIN_WALL');
        return;
      }

      expandSeeMore(articles);
      scrollAll();

      if (articles.length === lastCount) {
        stalled++;
      } else {
        stalled = 0;
        lastCount = articles.length;
      }

      // An empty feed is not a finished feed: never stop on "no change" until a post has been seen
      // or the page has had a fair go at loading one.
      var mayStopOnStall = sawArticles || rounds >= MIN_ROUNDS_BEFORE_EMPTY_STOP;
      if ((mayStopOnStall && stalled >= STALL_ROUNDS) || rounds >= MAX_ROUNDS) {
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

  try {
    if (typeof module !== 'undefined' && module.exports) {
      module.exports = { isAgeText: isAgeText, dialogDecision: dialogDecision };
    }
  } catch (e) {
    // ignore
  }
})();
