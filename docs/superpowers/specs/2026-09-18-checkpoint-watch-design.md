# Checkpoint Watch — Design

Date: 2026-09-18
Status: approved in chat; owner asked to proceed straight to build

## Purpose

A personal-use Android app for a GrapheneOS phone. It reads the public Facebook
page <https://www.facebook.com/CheckpointNZ> ("Checkpoint Watch Auckland")
without a Facebook account, extracts the road reports posted there, stores them
in a local database, and shows them in its own UI. Facebook is never shown on
screen.

Distribution is a signed APK on a GitHub release, installed and updated through
Obtainium. No Play Store, no Google services, no analytics, and no network
traffic other than to Facebook's own servers — the page URL, plus the content
hosts (`*.fbcdn.net` and similar) the page itself loads.

## Verified facts (spike, 2026-09-18)

These were measured against the live page and drive the design.

1. Plain HTTP (curl / TLS client) with a desktop user agent returns only the
   newest post, embedded as JSON in the HTML. Mobile hosts (`m.`, `mbasic.`)
   return HTTP 400. Rejected as the collector.
2. Facebook's Page Plugin embed renders logged out with no popup but is capped
   at 5 truncated posts. Rejected.
3. In a real browser engine, the first login dialog ("See more from …") has a
   `[aria-label="Close"]` button. Clicking it and scrolling loads **10 posts**
   (about 4 hours of history at the page's current rate).
4. After those 10, Facebook shows a second dialog ("See more on Facebook") with
   no close button and stops serving posts. Hiding it does not help. **10 posts
   per visit is a server-side ceiling for logged-out visitors.**
5. Hiding the first dialog with CSS instead of clicking Close does not unlock
   the feed. The real Close button must be clicked.
6. Posts 2–10 arrive as `/api/graphql/` responses (3 responses, ~500 KB). Each
   story carries `post_id` (numeric, permanent), `creation_time` (unix
   seconds) and `message.text` (full, untruncated). Post 1 carries the same
   fields in a JSON `<script>` block in the initial HTML.
7. Post text follows a stable pattern:

   ```
   🛑 CHECKPOINT – Lincoln Road, HENDERSON
   After the off-ramp coming from the motorway
   Time: 11:55PM
   ```

   Types seen: `CHECKPOINT`, `HEAVY POLICE PRESENCE`, `CRASH`, `SPEED CAMERA`.
   Suburb is optional (`CHECKPOINT – Trig Road`). `Time:` may have a trailing
   note (`9:30PM (Pictured)`). A `From: WhatsApp subscriber` line may follow.
   **One post may contain several reports**, each starting with its own header
   line.

## Consequences accepted

- Each scan sees at most the 10 newest posts. History accumulates across scans.
- If the app is not opened for longer than ~10 posts' worth of time, the posts
  in between are unrecoverable. The app marks this as a gap; it never implies
  the timeline is complete.
- A post missing from a later scan is never treated as deleted.
- Facebook can change its page at any time. All Facebook-specific knowledge is
  isolated in two small units (collector script, feed extractor) so it can be
  patched quickly.

## Stack

- Kotlin, Jetpack Compose (Material 3), Room (SQLite), kotlinx.serialization,
  coroutines. AndroidX WebKit for `WebViewCompat`.
- `compileSdk`/`targetSdk` 36, `minSdk` 29. Single `app` module.
- Build from the command line with the Gradle wrapper (Gradle 9.3.1 is already
  cached on the dev machine; JDK 17; SDK platform 36 / build-tools 36.0.0).
- Permissions: `INTERNET`; plus those listed under Background updates.
- Application ID `nz.personal.checkpointwatch`, app label "Checkpoint Watch".

## Architecture

```
ui/            Compose screen + ViewModel
collect/       FeedCollector (WebView), collector.js, FeedJsonExtractor, DomPostExtractor
parse/         ReportParser, ReportedTimeResolver
data/          Room entities, DAOs, ScrapeRecorder (merge logic), Repository
```

Data flow for one scan:

```
FeedCollector ──raw JSON text / DOM posts──▶ FeedJsonExtractor ──RawPost──▶
ScrapeRecorder ──(ReportParser per post)──▶ Room ──Flow──▶ ViewModel ──▶ UI
```

### collect/FeedCollector

Owns one WebView and exposes `suspend fun collect(): CollectResult`.

- The WebView is created in code and added to the Activity's root layout
  **behind** the Compose content at a fixed 1280×2400 px, not focusable, not
  clickable, `importantForAccessibility = no`. It must be attached and
  `VISIBLE` so the page's lazy loading (IntersectionObserver) runs; the opaque
  Compose surface on top fully covers it. It is never brought to front.
  (Risk to verify on device: that a covered WebView still renders. Fallback:
  translate it off-screen.)
- Settings: JavaScript on, DOM storage on, desktop Chrome user-agent string,
  `useWideViewPort`, images blocked (`blockNetworkImage`) to save data,
  no file/content access, no geolocation, safe browsing default.
- Before each scan: clear cookies and web storage so every scan is a fresh
  logged-out visitor (constant `FRESH_SESSION_EACH_SCAN = true`).
- Navigation guard: `shouldOverrideUrlLoading` blocks any navigation away from
  the page URL (login redirects, app-store intents). New windows are disabled.
- `collector.js` is injected at document start via
  `WebViewCompat.addDocumentStartJavaScript` (allowed origin
  `https://www.facebook.com`); if unsupported, injected in `onPageStarted`.
- JS → Kotlin uses `WebViewCompat.addWebMessageListener` restricted to
  `https://www.facebook.com`. No `addJavascriptInterface`.
- Limits: 20 s for page load, 45 s for the whole scan. Cancelling the coroutine
  (app stopped) calls `stopLoading()` and loads `about:blank`.

`CollectResult`:

```kotlin
data class CollectResult(
    val jsonChunks: List<String>,     // raw graphql responses + initial JSON script blocks
    val domPosts: List<DomPost>,      // fallback: text + relative age + permalink
    val end: EndReason                // LOGIN_WALL, NO_MORE_POSTS, TIMEOUT, NETWORK_ERROR, BLOCKED
)
```

### collect/collector.js

Deliberately dumb; all interpretation happens in Kotlin where it is unit-tested.

1. Wrap `XMLHttpRequest` and `fetch`; forward the body of any response whose
   URL contains `/api/graphql` as message `{t:"json", body}`.
2. On DOMContentLoaded, forward every `script[type="application/json"]` whose
   text contains `"post_id"` as `{t:"json", body}`.
3. Loop (max 24 rounds, 1.5 s apart, and a 38 s wall clock of its own so the
   DOM fallback is always sent before Kotlin's 45 s budget expires):
   - if a dialog has `[aria-label="Close"]`, click it;
   - if only a closeless *sign-in* dialog is left → `LOGIN_WALL`, after it has
     persisted 2 rounds once posts have been seen, or 8 rounds before any have
     (a dialog whose Close button has not rendered yet must not end an empty
     scan);
   - click any "See more" buttons inside top-level articles;
   - scroll the window, and any `position:fixed` scroller pinned around
     `[role=main]` while a dialog is up;
   - if the top-level article count has not changed for **4** rounds (~6 s;
     3 is too quick on mobile data) → `NO_MORE_POSTS`. An empty feed is never
     given up on before round 10, whatever the stall counter says.
4. Before ending, send `{t:"dom", posts:[{text, age, link}]}` for every
   top-level `[role=article]` with text (fallback data). `text` prefers the
   message element (`[data-ad-preview="message"]` and its siblings) over the
   whole article. Kotlin can also ask for this dump early, through
   `window.__cwDump`, when its own timeout fires on a page that loaded too
   slowly for the loop to finish.

### collect/FeedJsonExtractor (pure Kotlin)

Input: list of raw chunks. Each chunk may be several JSON documents separated
by newlines. Parse each with kotlinx.serialization into a `JsonElement` and
walk the tree: any object that has a string `post_id` and, within its subtree,
a `message.text` string and a `creation_time` number yields

```kotlin
data class RawPost(val postId: String, val createdAt: Instant, val text: String, val url: String)
```

`url` is `https://www.facebook.com/CheckpointNZ/posts/<postId>`. Duplicates
(same `postId`) collapse; the longest text wins. Malformed lines are skipped.
Posts without text (pure photo/promo) are dropped.

### collect/DomPostExtractor (pure Kotlin, fallback)

Used only when `FeedJsonExtractor` yields nothing. Converts `DomPost` to
`RawPost`: `createdAt = scanTime − age` ("45s", "22m", "2h", "1d", "1w", "2y";
marked approximate), `postId = "dom:" + sha1(normalisedText).take(16)`.

The text is **cleaned first**, from the outside in, stopping at the first line
that is not recognisable chrome: from the top the online-status lines, the
page's own name, the relative age and separators; from the bottom reaction
counts, "See more" and the Like/Comment/Share row. Whole lines only. Without
this the hash moves every scan (the age line alone does it), so the same post
reads as a new one each time and can never bridge onto the JSON feed's
`message.text`. Nothing is dropped for being before the first report header —
the author may write a line above it, and `ReportParser` keeps that.

A scraped `link` is kept only if it is `https` on `facebook.com` or a
subdomain; anything else falls back to the page URL.

### parse/ReportParser (pure Kotlin)

`fun parse(text: String, createdAt: Instant): List<ParsedReport>`

- A **header line** matches: optional emoji/symbols, then an upper-case type
  phrase, then ` – ` / ` - ` / ` — `, then the location.
- Type mapping (case-insensitive, by keyword): contains `CHECKPOINT` →
  `CHECKPOINT`; contains `POLICE` → `POLICE_PRESENCE`; contains `CRASH` →
  `CRASH`; contains `SPEED CAMERA` or `CAMERA` → `SPEED_CAMERA`; any other
  upper-case header → `OTHER` with the original label kept.
- Location: if the text after the last comma is all upper-case it is the
  `suburb` (stored upper-case); the rest is `road`. No comma → `road` only.
- Lines after a header up to the next header belong to that report:
  `Time:` line → `reportedTimeText`; `From:` line → `source`; everything else
  → `details` (joined with newlines).
- A post with no header line yields one `OTHER` report whose `details` is the
  whole text, so nothing is ever lost.
- Multiple headers → multiple reports sharing the post (`indexInPost` 0..n).

### parse/ReportedTimeResolver (pure Kotlin)

Turns `"9:30PM (Pictured)"` + post `createdAt` into an `Instant` in
`Pacific/Auckland`: take the latest date on which that wall-clock time is not
more than 15 minutes after `createdAt`. Unparseable → `null`; the UI then
falls back to the post time.

### data/ (Room)

```
posts(
  post_id TEXT PK, url TEXT, text TEXT, text_hash TEXT,
  created_at INTEGER, created_at_approx INTEGER(0/1),
  first_seen_at INTEGER, last_seen_at INTEGER, edited_at INTEGER NULL,
  gap_before INTEGER(0/1)          -- history between this post and the next older one is unknown
)
reports(
  id INTEGER PK AUTOINCREMENT, post_id TEXT FK→posts ON DELETE CASCADE,
  index_in_post INTEGER, type TEXT, type_label TEXT,
  road TEXT NULL, suburb TEXT NULL, details TEXT,
  reported_time_text TEXT NULL, reported_at INTEGER NULL, source TEXT NULL
)
post_revisions(id PK, post_id FK→posts ON DELETE CASCADE, text TEXT, replaced_at INTEGER)
scrapes(
  id INTEGER PK AUTOINCREMENT, started_at, finished_at,
  status TEXT,                      -- OK, OK_WITH_GAP, FAILED_NETWORK, FAILED_NO_DATA, CANCELLED
  end_reason TEXT, trigger TEXT, collector TEXT,
  posts_seen INTEGER, posts_new INTEGER, posts_updated INTEGER
)
scrape_sightings(
  scrape_id FK→scrapes ON DELETE CASCADE,
  post_id FK→posts ON DELETE CASCADE,
  PRIMARY KEY(scrape_id, post_id)
)
```

Indexes: `posts(created_at)`, `posts(text_hash)`, `reports(post_id)`,
`reports(suburb)`, `reports(type)`, `post_revisions(post_id)`,
`scrape_sightings(scrape_id)`, `scrape_sightings(post_id)` — the last three
are what the foreign keys above need.

**Retention** (`RetentionPolicy`, applied in the recorder's transaction after
the scan's own writes): scrapes older than 30 days go, taking their sightings
with them; posts go once they are *both* older than 180 days and unseen for
180 days, taking their reports, revisions and sightings. There is no export
and no backup, so both windows are deliberately generous.

### data/ScrapeRecorder (merge logic)

One transaction per scan:

1. Insert the `scrapes` row, with provisional counts (nothing may reference a
   row that is not there yet); it is rewritten at the end with the real status
   and counts.
2. For each `RawPost` (newest first): match an existing post by `post_id`,
   else by `text_hash` within ±48 h of `created_at` (bridges JSON ↔ DOM
   fallback IDs).
   - none → insert post, parse and insert reports, count as new;
   - match, same text → update `last_seen_at`;
   - match, text changed → copy old text to `post_revisions`, update post,
     delete and re-insert its reports, set `edited_at`, count as updated.
   - always insert a `scrape_sightings` row.
3. **Gap rule:** if the database already had posts before this scan and none
   of this scan's posts matched an existing post, set `gap_before = 1` on the
   oldest post of this scan and use status `OK_WITH_GAP`.
4. **Gap healing:** a scan is a contiguous newest-first slice of the feed, so
   if this scan matched a post carrying `gap_before` *and* also contains a
   post older than it, the far side of that gap is what we are now looking at:
   clear the flag (including across the `dom:` → numeric bridge). Without
   this, a one-post scan's gap marker would be permanent. Setting and healing
   can never happen in the same scan — healing needs a match, and the gap rule
   only fires when there were none.
5. Zero posts extracted → `FAILED_NO_DATA`; nothing else changes.
6. Apply the retention policy above.

`ScrapeRecorder` depends on DAO interfaces so it is unit-testable with fakes.

### ui/

Single screen, Material 3, follows system light/dark.

- **On open:** saved reports render immediately from Room. A scan starts on
  `ON_START` if the last scan finished more than 2 minutes ago, and on
  pull-to-refresh. The scan is cancelled on `ON_STOP` (screen locked / app
  left); the next open starts a fresh one.
- **Status banner:** "Finding checkpoints…" with progress while scanning, then
  one of: "Found 4 new reports" · "No new reports" · "Found 3 new reports —
  earlier posts unavailable" (gap) · "Couldn't reach Facebook — showing saved
  reports" · "Facebook returned no posts — showing saved reports". Also shows
  "Last checked 3 min ago".
- **Report card:** type icon + colour, `road`, `suburb`, details, "Reported
  9:30 PM · 2 h ago", source if present. Freshness styling by age of
  `reported_at ?: created_at`: under 2 h normal, 2–6 h dimmed, over 6 h
  greyed with "old" tag. The app never says "active" or "confirmed".
- **Types are first-class and equal:** Checkpoint (red, stop sign), Police
  presence (blue, shield), Crash (orange, warning), Speed camera (purple,
  camera), Other (grey). Filter chips for each type; all on by default.
- **Area handling:** suburb filter (dropdown built from suburbs in the DB).
  Each card shows an "Also in PAKURANGA" line listing other reports of any
  type in the same suburb reported within 3 hours of it (e.g. "Crash · 40 min ago"),
  so a crash and police presence in the same area are visible together.
  Reports with no suburb match on identical road name instead.
- **Gap marker:** a divider "Earlier posts unavailable — app wasn't opened"
  rendered after any post with `gap_before = 1`.
- **Tap a card:** expands to full original post text and a "Open on Facebook"
  button (opens the default browser; user-initiated only).
- List is grouped by day (Today / Yesterday / date) in `Pacific/Auckland`.

## Background updates (optional, off by default)

The owner wants the option to stay up to date without opening the app.

- **Setting:** "Update in background" — Off / every 15 min / 30 min / 1 h /
  2 h. Because a scan sees ~4 h of posts, any of these intervals keeps the
  history gap-free under normal posting rates. Stored in DataStore.
- **Scheduler:** WorkManager unique periodic work (`KEEP`/`UPDATE` on setting
  change), constraint `NetworkType.CONNECTED`. WorkManager has no Google
  services dependency and survives reboot. Android may delay runs under Doze;
  the settings screen explains this and offers a button that opens the
  system battery page so the owner can set the app to "Unrestricted" (the
  app does not request the exemption itself). 15 min is the platform minimum.
- **Worker:** `ScanWorker` (CoroutineWorker) runs the same
  `FeedCollector → FeedJsonExtractor → ScrapeRecorder` pipeline. A process-wide
  mutex guarantees one scan at a time (foreground scan wins; the worker
  returns `success` without scanning if one is running or finished < 2 min
  ago).
- **Headless WebView host:** with no Activity there is no window to attach
  to. `FeedCollector` takes a `WebViewHost`: `ActivityHost` (attached behind
  the UI, as above) or `HeadlessHost`, which creates the WebView on the main
  thread with the application context, forces `measure`/`layout` to
  1280×2400, and dispatches window-visibility VISIBLE so the page believes it
  is shown. No overlay permission, nothing on screen.
- **Known risk (unverified until run on the phone):** Chromium may throttle
  an unattached WebView so Facebook's lazy loading yields fewer than 10
  posts. Mitigation, in order: (1) the headless host above; (2) if the
  WebView scan yields nothing, `HttpLatestFetcher` does the plain HTTPS GET
  verified in the spike (desktop user agent → newest post embedded in HTML)
  and feeds it through the same extractor, so a background run always
  captures at least the newest post; (3) the next foreground open does a full
  scan and the gap rule reports honestly if anything was missed.
  The scrape row records `trigger` (FOREGROUND/BACKGROUND) and `collector`
  (WEBVIEW/WEBVIEW_DOM/HTTP) so this is measurable on the device.
- **Notifications (optional, off by default):** "Notify me about new
  reports". Requests `POST_NOTIFICATIONS` only when switched on. After a
  background scan with new reports, posts one grouped notification per scan
  ("2 new: Checkpoint – Lincoln Road, HENDERSON · Crash – Pakuranga Road"),
  limited to the types and watched suburbs chosen in settings (default: all).
  Tapping opens the app. Foreground scans never notify. Reports older than
  2 h at scan time never notify.
- **Settings screen:** background interval, battery-settings shortcut,
  notifications toggle, notify types, watched suburbs, last 10 scans log
  (time, trigger, collector, new/seen, status) for transparency, and
  "About / data source".

`scrapes` gains two columns: `trigger TEXT`, `collector TEXT`
(replacing `used_fallback`).

Additional permissions: `POST_NOTIFICATIONS` (runtime, only if enabled),
`RECEIVE_BOOT_COMPLETED` and `WAKE_LOCK` (merged in by WorkManager).

## Design quality bar

The owner asked for a product that feels premium, not a utility scraper. The
UI is held to these rules:

- **Identity:** a dark-first "night drive" look (most use is at night in a
  car park or before driving): near-black blue surfaces, one warm amber
  accent, high-contrast type. A matching light theme. Custom colour scheme,
  not stock Material purple. Adaptive launcher icon (amber beacon on
  midnight) with a monochrome layer for themed icons.
- **Typography:** one clear hierarchy — road name is the hero of each card
  (title, semibold), suburb as a small-caps label, details in body, time in
  tabular numerals. No more than three text sizes on a card.
- **Hero summary:** the top of the screen answers "what's happening now" at a
  glance: counts per type for the last 2 hours ("4 checkpoints · 1 crash ·
  1 police") and the freshest report time, above the list.
- **Type language:** each type has a consistent colour + icon used everywhere
  (chips, card rail, summary). Colour is never the only signal (icon + label
  always present) and all pairs meet WCAG AA contrast.
- **Motion:** scanning is shown by a calm animated sweep in the banner, not a
  blocking spinner; new reports animate in (`animateItem`), the result banner
  cross-fades; cards expand with a spring. Respects "remove animations".
- **States are designed, not defaulted:** first-run empty state ("Finding
  checkpoints for the first time…"), no-results-for-filter state, offline
  state, gap marker, stale data — each with its own copy and illustration
  icon.
- **Feel:** edge-to-edge with correct insets, predictive back, haptic tick on
  pull-to-refresh completion and on new reports found, 48 dp touch targets,
  TalkBack content descriptions that read a card as one sentence, font
  scaling to 200 % without clipping, landscape and large-screen width cap
  (max content width 640 dp).
- **Speed:** cold start shows saved reports within one frame of Room
  emitting; list is a `LazyColumn` with stable keys; no work on the main
  thread; a baseline of zero jank while a scan runs behind the UI.
- **Copy:** short, calm, NZ English. Never alarming, never claims a report is
  confirmed or still active.

## Error handling

| Situation | Behaviour |
|---|---|
| No network / page load error | status `FAILED_NETWORK`, banner, saved data shown |
| Page loads but no posts from JSON | use DOM fallback, `collector = WEBVIEW_DOM` |
| Neither yields posts | `FAILED_NO_DATA`, banner |
| Hard login wall before any post | same as above |
| Scan exceeds 45 s | keep whatever was captured, record normally |
| App stopped mid-scan | `CANCELLED`; captured-so-far posts are still recorded |
| Parser can't understand a post | stored as `OTHER`, raw text preserved |
| WebView renderer crash | `onRenderProcessGone` handled; WebView recreated next scan |

## Testing

- JVM unit tests (no device needed):
  - `ReportParser` — fixtures from real posts captured 2026-09-18, including
    multi-report post, no-suburb header, `Time:` with trailing note, `From:`
    line, headerless text, all four types.
  - `ReportedTimeResolver` — same day, crosses midnight, NZ daylight-saving
    change, garbage input.
  - `FeedJsonExtractor` — a real captured graphql response and initial-HTML
    JSON block saved under `app/src/test/resources/`, plus malformed input.
  - `DomPostExtractor` — age parsing, stable IDs.
  - `ScrapeRecorder` — new/seen/edited posts, JSON↔DOM bridging, gap rule,
    zero posts, with in-memory fake DAOs.
  - `NotificationPlanner` — type/suburb filtering, 2 h cut-off, grouping text.
  - `HttpLatestFetcher` parsing path — via the captured HTML fixture.
- Build verification: `./gradlew testDebugUnitTest assembleDebug`.
- On-device verification (needs the phone over USB or an emulator; none is
  attached/installed today): scan finds ~10 posts, Facebook never visible,
  leaving the app cancels the scan, second scan reports "No new reports";
  background run with the app closed records a scrape row, and which
  collector it needed.

## Out of scope

Maps/geocoding, exact-time alarms or foreground services, comments and
reactions, photos, other Facebook pages, logging in, export/backup.

Distribution is in scope and shipped: the APK is signed with a private release
key (`keystore.properties`, never committed), attached to a GitHub release, and
installed and updated through Obtainium. `assembleRelease` falls back to the
debug key for local smoke builds; `packageReleaseApk` refuses to run without the
real key, so a debug-signed APK can never reach `dist/` under a release name.
