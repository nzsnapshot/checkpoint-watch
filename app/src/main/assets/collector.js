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
 */
(function () {
  'use strict';

  try {
    if (window.__cwInstalled) {
      return;
    }
    window.__cwInstalled = true;
  } catch (e) {
    return;
  }

  var MAX_BODY = 3 * 1024 * 1024; // bodies bigger than this are not a feed response
  var MAX_QUEUED = 64;
  var ROUND_MS = 1500;
  var MAX_ROUNDS = 12;
  var STALL_ROUNDS = 3;
  var MAX_DOM_POSTS = 40;
  var MAX_DOM_TEXT = 20000;
  var POST_ID = '"post_id"';

  var ended = false;
  var queue = [];
  var flushTimer = null;
  var roundTimer = null;
  var rounds = 0;
  var lastCount = -1;
  var stalled = 0;

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
        queue.shift();
      }
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
      if (queue.length < MAX_QUEUED) {
        queue.push(text);
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
    // ignore
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
    // ignore
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

  function isVisible(element) {
    try {
      var rect = element.getBoundingClientRect();
      return rect.width > 0 && rect.height > 0;
    } catch (e) {
      return false;
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

  // 'none' | 'closed' | 'wall'
  function handleDialogs() {
    var result = 'none';
    try {
      var dialogs = document.querySelectorAll('[role="dialog"]');
      for (var i = 0; i < dialogs.length; i++) {
        try {
          var dialog = dialogs[i];
          if (!isVisible(dialog)) {
            continue;
          }
          var close = dialog.querySelector('[aria-label="Close"]');
          if (!close) {
            // The second dialog has no way out: this is the hard login wall.
            return 'wall';
          }
          close.click();
          result = 'closed';
        } catch (e) {
          // skip this dialog
        }
      }
    } catch (e) {
      // ignore
    }
    return result;
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

  var AGE_PATTERN = /^(just now|\d+\s*[smhdwy][a-z]*(\s+ago)?)$/i;

  function articleAge(article) {
    try {
      var links = article.querySelectorAll('a[href]');
      for (var i = 0; i < links.length; i++) {
        var text = (links[i].innerText || '').trim();
        if (text.length > 0 && text.length <= 12 && AGE_PATTERN.test(text)) {
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
        return String(anchor.href).split('?')[0];
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
    try {
      if (ended) {
        stopLoop();
        return;
      }
      rounds++;

      if (handleDialogs() === 'wall') {
        finish('LOGIN_WALL');
        return;
      }

      var articles = topLevelArticles();
      expandSeeMore(articles);
      scrollAll();

      if (articles.length === lastCount) {
        stalled++;
      } else {
        stalled = 0;
        lastCount = articles.length;
      }

      if (stalled >= STALL_ROUNDS || rounds >= MAX_ROUNDS) {
        finish('NO_MORE_POSTS');
      }
    } catch (e) {
      try {
        finish('NO_MORE_POSTS');
      } catch (e2) {
        // ignore
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
    // ignore
  }
})();
