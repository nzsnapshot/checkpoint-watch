/*
 * Checkpoint Watch page-widget collector.
 *
 * The fallback for the case the owner's phone is always in: on a VPN, Facebook serves a logged-out
 * visitor only the post already embedded in the page's HTML. The feed never paginates — zero
 * /api/graphql responses, and no sign-in wall either, in this app AND in the phone's own browser.
 * With the VPN off everything works, so the VPN stays on and the app finds another way.
 *
 * Facebook's embeddable Page Plugin is that other way. It renders from the same VPN addresses,
 * with no login dialog at all and even while `document.visibilityState === 'hidden'`, and it
 * carries the five newest posts in Facebook's older, plainer markup:
 *
 *   .userContentWrapper                     one post
 *     abbr.timestamp[data-utime]            unix seconds, inside the <a> whose href is the permalink
 *     .userContent                          the text the author wrote, emoji included
 *     img._1p6f / .scaledImageFit*          the post's photo (the 200x200 img._s0 is the page avatar)
 *     .see_more_link / .text_exposed_link   what truncates a long post; clicking expands it in place
 *
 * Five posts, capped server-side, and the list does not grow on scroll. So this script does not
 * scroll, does not close anything, and does not have a round loop: it waits for the markup to
 * appear, expands what is truncated, reads the five posts once, and ends.
 *
 * It is registered for the same origin as `collector.js`, so both scripts arrive on both pages.
 * Each gates itself on `location.pathname` at the very top — this one runs only under `/plugins/`,
 * `collector.js` only everywhere else — and each has its own install guard, so neither can ever
 * run twice in one page. See FeedCollector's COMBINED_SCRIPT comment for the registration.
 *
 * Plain ES2017, everything in try/catch. The pure helpers (isPluginPath, pollDecision,
 * endReasonFor, cleanLink, isPostImageClass, pickImage, parseUtime, capText, diagBody) are
 * exported when this file is loaded by node: see app/src/test/js/plugin_collector.test.js.
 */
(function () {
  'use strict';

  var IN_BROWSER = typeof window !== 'undefined' && typeof document !== 'undefined';

  /** Pure: is this the Page Plugin, the one page this script is for? */
  function isPluginPath(pathname) {
    try {
      return typeof pathname === 'string' && pathname.indexOf('/plugins/') === 0;
    } catch (e) {
      return false;
    }
  }

  if (IN_BROWSER) {
    try {
      // The gate, before anything else: on the page itself this script is a no-op, and
      // collector.js — which is registered for the same origin — owns that page instead.
      if (!isPluginPath(location.pathname)) {
        return;
      }
      if (window.__cwPluginInstalled) {
        return;
      }
      window.__cwPluginInstalled = true;
      // Document-start injection also reaches same-origin iframes. Only the top document is the
      // widget we asked for.
      if (window.top !== window) {
        return;
      }
    } catch (e) {
      return;
    }
  }

  var ORIGIN = 'https://www.facebook.com';

  var POLL_MS = 500;
  /** How long the plugin gets to render before the pass gives up on it. */
  var MAX_WAIT_MS = 12000;

  /** The widget serves five; ten is headroom for the day it serves more, and a hard stop. */
  var MAX_POSTS = 10;
  var MAX_TEXT = 20000;

  var MAX_DIAG_CHARS = 8 * 1024;
  var MAX_QUEUED_CHARS = 1024 * 1024;

  var POST_SELECTOR = '.userContentWrapper';
  var TIME_SELECTOR = 'abbr[data-utime]';
  var CONTENT_SELECTOR = '.userContent';
  /** Only these expand a truncated post. Never a generic anchor: that would navigate the widget. */
  var SEE_MORE_SELECTOR = '.see_more_link, .text_exposed_link';

  var startedAt = Date.now();
  var ended = false;
  var polls = 0;
  var expanded = 0;
  var pollTimer = null;
  var queue = [];
  var queuedChars = 0;
  var flushTimer = null;
  var diagInstall = null;

  // ---------------------------------------------------------------- bridge

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

  // ------------------------------------------------------- pure judgement

  /**
   * Pure: what this poll should do.
   *
   * One rendered post is enough to stop waiting — the widget fills all five in the same frame —
   * and an empty widget is given the full [MAX_WAIT_MS] before it is called a timeout.
   */
  function pollDecision(readyPosts, elapsedMs) {
    try {
      if (readyPosts > 0) {
        return 'collect';
      }
      if (elapsedMs >= MAX_WAIT_MS) {
        return 'timeout';
      }
      return 'wait';
    } catch (e) {
      return 'wait';
    }
  }

  /** Pure: a pass that read posts finished; one that read none ran out of time. */
  function endReasonFor(postCount) {
    return postCount > 0 ? 'NO_MORE_POSTS' : 'TIMEOUT';
  }

  /**
   * Pure: a permalink from the widget's markup, or `null`.
   *
   * The plugin's hrefs are relative and carry `?ref=embed_page`, so the query and fragment go and
   * the rest is made absolute on this origin. Anything that is not plainly a path on
   * www.facebook.com is refused rather than guessed at — the markup is untrusted input, and a
   * lookalike host must never come back as somewhere the owner is invited to tap through to.
   */
  function cleanLink(href) {
    try {
      if (typeof href !== 'string') {
        return null;
      }
      var path = href.trim().split('?')[0].split('#')[0];
      if (path.length === 0) {
        return null;
      }
      if (path === ORIGIN || path.indexOf(ORIGIN + '/') === 0) {
        return path;
      }
      if (path.charAt(0) === '/') {
        return ORIGIN + path;
      }
      return null;
    } catch (e) {
      return null;
    }
  }

  /**
   * Pure: is this `img`'s class list the post's own photo?
   *
   * `_s0` is the page's 200x200 avatar, which every post carries and no post is about, so it is a
   * veto rather than simply not a match. Whole class tokens only: `_1p6fx` is not `_1p6f`.
   */
  function isPostImageClass(className) {
    try {
      if (typeof className !== 'string') {
        return false;
      }
      var tokens = className.split(/\s+/);
      var hit = false;
      for (var i = 0; i < tokens.length; i++) {
        var token = tokens[i];
        if (token === '_s0') {
          return false;
        }
        if (token === '_1p6f' || token === 'scaledImageFitWidth' || token === 'scaledImageFitHeight') {
          hit = true;
        }
      }
      return hit;
    } catch (e) {
      return false;
    }
  }

  function isHttpsUrl(url) {
    try {
      return typeof url === 'string' && url.indexOf('https://') === 0;
    } catch (e) {
      return false;
    }
  }

  /** Pure: the first post photo among `[{ className, src }]`, or `null`. */
  function pickImage(images) {
    try {
      if (!images) {
        return null;
      }
      for (var i = 0; i < images.length; i++) {
        var image = images[i];
        if (image && isPostImageClass(image.className) && isHttpsUrl(image.src)) {
          return image.src;
        }
      }
    } catch (e) {
      // ignore
    }
    return null;
  }

  /** Pure: `data-utime` as a positive count of unix seconds, or `null`. */
  function parseUtime(value) {
    try {
      if (typeof value === 'number') {
        return isFinite(value) && value > 0 ? Math.floor(value) : null;
      }
      if (typeof value !== 'string') {
        return null;
      }
      var trimmed = value.trim();
      if (!/^\d{1,12}$/.test(trimmed)) {
        return null;
      }
      var seconds = parseInt(trimmed, 10);
      return isFinite(seconds) && seconds > 0 ? seconds : null;
    } catch (e) {
      return null;
    }
  }

  /** Pure: trimmed text, cut to [max] characters. */
  function capText(text, max) {
    try {
      if (typeof text !== 'string' || typeof max !== 'number' || !(max > 0)) {
        return '';
      }
      var trimmed = text.trim();
      return trimmed.length > max ? trimmed.substring(0, max) : trimmed;
    } catch (e) {
      return '';
    }
  }

  /**
   * Pure: the pass's diagnostics as a JSON string of at most [maxChars] characters.
   *
   * When the whole thing will not fit, what it saw is dropped and the install facts and the ending
   * are kept: truncating the string instead would deliver JSON nobody can parse or read.
   */
  function diagBody(install, seen, reason, maxChars) {
    try {
      var text = JSON.stringify({ install: install, seen: seen, end: reason });
      if (typeof text !== 'string') {
        return '';
      }
      if (text.length <= maxChars) {
        return text;
      }
      var shorter = JSON.stringify({ install: install, end: reason });
      return typeof shorter === 'string' && shorter.length <= maxChars ? shorter : '';
    } catch (e) {
      return '';
    }
  }

  // ------------------------------------------------------------------ DOM

  /** The widget's posts that have a timestamp on them; anything else has not rendered yet. */
  function readyWrappers() {
    var ready = [];
    try {
      var all = document.querySelectorAll(POST_SELECTOR);
      for (var i = 0; i < all.length; i++) {
        try {
          if (all[i].querySelector(TIME_SELECTOR)) {
            ready.push(all[i]);
          }
        } catch (e) {
          // skip this wrapper
        }
      }
    } catch (e) {
      // ignore
    }
    return ready;
  }

  function wrapperCount() {
    try {
      return document.querySelectorAll(POST_SELECTOR).length;
    } catch (e) {
      return 0;
    }
  }

  /**
   * Clicks every "See more" inside the posts' text, once each.
   *
   * Only the two classes Facebook gives that control. A generic anchor here would be the
   * permalink, and clicking it would navigate the widget away from the only page this pass has.
   */
  function expandSeeMore(wrappers) {
    for (var i = 0; i < wrappers.length; i++) {
      try {
        var content = wrappers[i].querySelector(CONTENT_SELECTOR);
        if (!content) {
          continue;
        }
        var links = content.querySelectorAll(SEE_MORE_SELECTOR);
        for (var j = 0; j < links.length; j++) {
          try {
            if (links[j].__cwClicked) {
              continue;
            }
            links[j].__cwClicked = true;
            links[j].click();
            expanded++;
          } catch (e) {
            // skip this link
          }
        }
      } catch (e) {
        // skip this wrapper
      }
    }
  }

  /** The permalink on the `<a>` that wraps this post's timestamp. */
  function linkFor(wrapper, stamp) {
    try {
      var anchor = stamp && typeof stamp.closest === 'function' ? stamp.closest('a[href]') : null;
      if (anchor) {
        var cleaned = cleanLink(anchor.getAttribute('href'));
        if (cleaned) {
          return cleaned;
        }
      }
      var fallback = wrapper.querySelector('a[href*="/posts/"], a[href*="/reel/"]');
      return fallback ? cleanLink(fallback.getAttribute('href')) : null;
    } catch (e) {
      return null;
    }
  }

  function imageFor(wrapper) {
    var images = [];
    try {
      var nodes = wrapper.querySelectorAll('img');
      for (var i = 0; i < nodes.length; i++) {
        try {
          images.push({ className: nodes[i].className, src: nodes[i].src });
        } catch (e) {
          // skip this image
        }
      }
    } catch (e) {
      // ignore
    }
    return pickImage(images);
  }

  function readPosts(wrappers) {
    var posts = [];
    for (var i = 0; i < wrappers.length && posts.length < MAX_POSTS; i++) {
      try {
        var wrapper = wrappers[i];
        var stamp = wrapper.querySelector(TIME_SELECTOR);
        var utime = stamp ? parseUtime(stamp.getAttribute('data-utime')) : null;
        if (utime === null) {
          continue;
        }
        var content = wrapper.querySelector(CONTENT_SELECTOR);
        var text = capText(content ? content.innerText || '' : '', MAX_TEXT);
        if (text.length === 0) {
          continue;
        }
        posts.push({
          utime: utime,
          link: linkFor(wrapper, stamp),
          text: text,
          image: imageFor(wrapper)
        });
      } catch (e) {
        // skip this post
      }
    }
    return posts;
  }

  // ---------------------------------------------------------- diagnostics

  function stripUrl(url) {
    try {
      return String(url).split('?')[0].split('#')[0];
    } catch (e) {
      return '';
    }
  }

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
      diagInstall = {
        href: stripUrl(location.href),
        ua: navigator.userAgent,
        dpr: window.devicePixelRatio,
        ready: ready,
        atDocumentStart: ready === 'loading' && noBody
      };
    } catch (e) {
      diagInstall = null;
    }
  }

  function seenFacts(posts) {
    try {
      var withImage = 0;
      var withLink = 0;
      for (var i = 0; i < posts.length; i++) {
        if (posts[i].image) withImage++;
        if (posts[i].link) withLink++;
      }
      return {
        wrappers: wrapperCount(),
        ready: readyWrappers().length,
        posts: posts.length,
        withImage: withImage,
        withLink: withLink,
        expanded: expanded,
        polls: polls,
        elapsedMs: Date.now() - startedAt,
        vis: document.visibilityState,
        iw: window.innerWidth,
        ih: window.innerHeight
      };
    } catch (e) {
      return { posts: posts ? posts.length : 0 };
    }
  }

  // ----------------------------------------------------------------- pass

  function stopPolling() {
    try {
      if (pollTimer !== null) {
        clearInterval(pollTimer);
        pollTimer = null;
      }
    } catch (e) {
      // ignore
    }
  }

  /** The one and only delivery: the posts, then the log, then the ending. */
  function finish(posts) {
    if (ended) {
      return;
    }
    ended = true;
    stopPolling();
    var safe = posts || [];
    send({ t: 'plugin', posts: safe });
    if (!diagInstall) {
      recordInstall();
    }
    var body = diagBody(diagInstall, seenFacts(safe), endReasonFor(safe.length), MAX_DIAG_CHARS);
    if (body) {
      send({ t: 'diag', body: body });
    }
    send({ t: 'end', reason: endReasonFor(safe.length) });
    flush();
  }

  function poll() {
    if (ended) {
      stopPolling();
      return;
    }
    try {
      polls++;
      var wrappers = readyWrappers();
      switch (pollDecision(wrappers.length, Date.now() - startedAt)) {
        case 'collect':
          // Expand first, then read: a "See more" click rewrites .userContent in place, and the
          // truncated copy is the one thing this pass must not ship.
          expandSeeMore(wrappers);
          finish(readPosts(readyWrappers()));
          return;
        case 'timeout':
          finish([]);
          return;
        default:
          return;
      }
    } catch (e) {
      // A poll that throws costs one poll. The clock still ends the pass.
      try {
        if (Date.now() - startedAt >= MAX_WAIT_MS) {
          finish([]);
        }
      } catch (e2) {
        // ignore
      }
    }
  }

  function start() {
    try {
      if (window.__cwPluginStarted) {
        return;
      }
      window.__cwPluginStarted = true;
      setTimeout(poll, 200);
      pollTimer = setInterval(poll, POLL_MS);
    } catch (e) {
      // ignore
    }
  }

  if (IN_BROWSER) {
    recordInstall();
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
  }

  // Only ever outside a browser (node, for the tests).
  try {
    if (!IN_BROWSER && typeof module !== 'undefined' && module.exports) {
      module.exports = {
        isPluginPath: isPluginPath,
        pollDecision: pollDecision,
        endReasonFor: endReasonFor,
        cleanLink: cleanLink,
        isPostImageClass: isPostImageClass,
        pickImage: pickImage,
        parseUtime: parseUtime,
        capText: capText,
        diagBody: diagBody
      };
    }
  } catch (e) {
    // ignore
  }
})();
