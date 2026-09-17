'use strict';

/**
 * One visit to the public page with headless Chromium.
 *
 * What the page does, from a home connection and logged out: it shows a "See more from ..."
 * dialog that has to be really clicked away, then it loads about ten posts over
 * `/api/graphql` as you scroll, then it puts up a sign-in wall with no close button and stops.
 * The very newest post is never in those responses — it is baked into a
 * `<script type="application/json">` block in the first HTML we receive.
 *
 * From a VPN or a data centre the same visit is "starved": the dialog closes, nothing ever
 * paginates, and no wall appears. That is a normal outcome, not a failure.
 */

const { extractPosts, jsonBlocksFromHtml, PAGE_URL } = require('./extract');

const DESKTOP_UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 ' +
  '(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36';

const VIEWPORT = { width: 1280, height: 2400 };
const ROUND_MS = 1500;
const MAX_ROUNDS = 24;
/** Rounds to keep trying after a successful Close before calling it starved. */
const NO_FEED_ROUNDS = 6;
const CLOSE_SELECTOR = '[role="dialog"] [aria-label="Close"]';
const BLOCKED_RESOURCES = new Set(['image', 'media', 'font']);

/**
 * The give-up rule: once we have closed the dialog and scrolled six times with not one feed
 * response to show for it, this connection is being starved and more scrolling will not help.
 */
function shouldGiveUpNoFeed({ closed, rounds, graphqlBodies }) {
  return Boolean(closed) && rounds >= NO_FEED_ROUNDS && graphqlBodies === 0;
}

/** Facebook's hard wall: a dialog with nothing to close it with. */
function isWall(dialogs) {
  return dialogs.some((d) => d.hasDialog && !d.hasClose);
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * Scrolls the way a reader would: up a little first so the page sees movement in both
 * directions, then down to the bottom, a scroll event for listeners that debounce, and the
 * last article dragged into view. Returns what the page looks like afterwards.
 */
/* istanbul ignore next -- runs inside the browser */
function scrollAndInspect() {
  window.scrollBy(0, -600);
  window.scrollTo(0, document.body.scrollHeight);
  window.dispatchEvent(new Event('scroll'));
  const articles = document.querySelectorAll('div[role="article"], [role="article"]');
  const last = articles[articles.length - 1];
  if (last && typeof last.scrollIntoView === 'function') {
    last.scrollIntoView({ block: 'end' });
  }
  const dialogs = [...document.querySelectorAll('[role="dialog"]')].map((d) => ({
    hasDialog: true,
    hasClose: Boolean(d.querySelector('[aria-label="Close"]')),
  }));
  return {
    articles: articles.length,
    dialogs,
    visibility: document.visibilityState,
  };
}

async function scanOnce({ headless = true, timeoutMs = 45000 } = {}) {
  const startedAt = Date.now();
  // Required lazily so the pure helpers above stay importable without a browser installed.
  const { chromium } = require('playwright');

  const chunks = [];
  const pending = [];
  let graphqlBodies = 0;
  let rounds = 0;
  let articles = 0;
  let closed = false;
  let endReason = 'UNKNOWN';
  let outcome = 'ERROR';
  let blocked = false;
  let browser = null;

  try {
    browser = await chromium.launch({ headless });
    const context = await browser.newContext({
      userAgent: DESKTOP_UA,
      viewport: VIEWPORT,
      locale: 'en-NZ',
      timezoneId: 'Pacific/Auckland',
      javaScriptEnabled: true,
    });
    context.setDefaultTimeout(Math.min(timeoutMs, 30000));

    // Images, video and fonts are most of the bytes and none of the information.
    await context.route('**/*', (route) => {
      const type = route.request().resourceType();
      if (BLOCKED_RESOURCES.has(type)) return route.abort().catch(() => {});
      return route.continue().catch(() => {});
    });

    const page = await context.newPage();

    page.on('response', (response) => {
      const url = response.url();
      if (!url.includes('/api/graphql')) return;
      pending.push(
        response
          .text()
          .then((body) => {
            if (typeof body === 'string' && body.includes('"post_id"')) {
              graphqlBodies += 1;
              chunks.push(body);
            }
          })
          .catch(() => {}),
      );
    });

    await page.goto(PAGE_URL, {
      waitUntil: 'domcontentloaded',
      timeout: Math.min(timeoutMs, 30000),
    });

    if (page.url().includes('/login')) {
      blocked = true;
      endReason = 'REDIRECTED_TO_LOGIN';
    } else {
      // The newest post only ever lives here.
      const html = await page.content().catch(() => '');
      chunks.push(...jsonBlocksFromHtml(html));

      closed = await closeDialog(page);

      const deadline = startedAt + timeoutMs;
      endReason = 'MAX_ROUNDS';
      while (rounds < MAX_ROUNDS) {
        if (Date.now() >= deadline) {
          endReason = 'TIMEOUT';
          break;
        }
        rounds += 1;
        let state;
        try {
          state = await page.evaluate(scrollAndInspect);
        } catch {
          endReason = 'PAGE_GONE';
          break;
        }
        articles = state.articles;

        // A late dialog gets one cheap look per round; the first wait above was the patient one.
        if (!closed) closed = await closeDialog(page, 1000);

        if (isWall(state.dialogs) && articles >= 2) {
          endReason = 'WALL';
          break;
        }
        if (page.url().includes('/login')) {
          blocked = true;
          endReason = 'REDIRECTED_TO_LOGIN';
          break;
        }
        if (shouldGiveUpNoFeed({ closed, rounds, graphqlBodies })) {
          endReason = 'NO_FEED';
          break;
        }
        await sleep(ROUND_MS);
      }
    }

    await Promise.all(pending);

    if (blocked) {
      outcome = 'BLOCKED';
    } else if (graphqlBodies > 0) {
      outcome = 'FEED';
    } else {
      outcome = 'STARVED';
    }
  } catch (error) {
    outcome = 'ERROR';
    endReason = errorReason(error);
  } finally {
    if (browser) await browser.close().catch(() => {});
  }

  let posts = [];
  try {
    posts = extractPosts(chunks);
  } catch {
    posts = [];
  }

  return {
    ok: outcome === 'FEED',
    outcome,
    endReason,
    posts,
    stats: {
      graphqlBodies,
      rounds,
      ms: Date.now() - startedAt,
      articles,
    },
  };
}

/** The only thing we ever click. */
async function closeDialog(page, waitMs = 8000) {
  try {
    const button = page.locator(CLOSE_SELECTOR).first();
    await button.waitFor({ state: 'visible', timeout: waitMs });
    await button.click({ timeout: 5000 });
    return true;
  } catch {
    return false;
  }
}

/** A short, machine-safe reason: no paths, no hostnames, no stack. */
function errorReason(error) {
  const message = error && error.message ? String(error.message) : 'unknown';
  const firstLine = message.split('\n')[0].trim();
  if (/timeout/i.test(firstLine)) return 'TIMEOUT';
  if (/ERR_NAME_NOT_RESOLVED|ENOTFOUND|EAI_AGAIN/i.test(firstLine)) return 'DNS';
  if (/ERR_CONNECTION|ECONNREFUSED|ECONNRESET|net::/i.test(firstLine)) return 'NETWORK';
  if (/Executable doesn't exist|browserType\.launch/i.test(firstLine)) return 'NO_BROWSER';
  return 'ERROR';
}

module.exports = {
  scanOnce,
  shouldGiveUpNoFeed,
  isWall,
  errorReason,
  DESKTOP_UA,
  VIEWPORT,
  MAX_ROUNDS,
  NO_FEED_ROUNDS,
  CLOSE_SELECTOR,
};
