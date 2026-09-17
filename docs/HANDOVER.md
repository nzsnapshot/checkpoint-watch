# Hand-over: continuing Checkpoint Watch on the Windows home PC

Written 2026-09-18 when work moved from the MacBook to the home Windows PC.
Read this first, then `README.md` and `docs/superpowers/specs/2026-09-18-checkpoint-watch-design.md`.

## Where things stand

| Thing | State |
|---|---|
| `main` | Released app **v1.0.2** (tag `v1.0.2`, signed APK on the Releases page, installed via Obtainium). |
| branch `feat/home-collector` | Home collector (Node + Playwright) — core DONE and tested (46 `node:test` tests pass): `collector/src/extract.js`, `scan.js`, `publish.js`, `index.js`. **NOT done:** Windows/Linux/macOS installers, `status-windows.ps1`, `collector/README.md`, a real run on a non-VPN connection. `collector/run-windows.ps1` is an unfinished first draft. |
| branch `feat/v1.1.0` | App v1.1.0 in progress; unit tests pass at the last commit. **Done:** page-widget fallback collector (second WebView pass), early `NO_FEED` end for starved scans, VPN hint in the banner, scan-history labels, Room **migration 1→2** (`posts.imageUrl`, `posts.imagePath`) with exported schema. **NOT done:** recorder persisting `imageUrl`, `ImageStore` download/cache/retention, image UI (thumbnail + expanded image), **Map button** (`geo:` intent, OSM fallback), **relay-feed reader** (below), version bump to 1.1.0 / code 4, README + spec updates, screenshots. |

## What we learned the hard way (evidence, not guesses)

- Facebook "starves" logged-out visitors from **VPN / datacentre IP addresses**: after the login dialog is closed the feed never paginates (zero `/api/graphql` feed responses, no sign-in wall). Only the single newest post, embedded in the page HTML, is served. Verified on the owner's phone (always-on VPN, also in the phone's real browser), on the MacBook through Seattle and Brisbane VPN exits, and on GitHub Actions runners.
- The embeddable Page Plugin (`/plugins/page.php?...&tabs=timeline`) gives 5 posts with images and works even in a hidden page — but from VPN/datacentre addresses it **redirects to /login**. So it helps background scans on normal connections, not VPN users.
- From a **residential home connection** everything works: ~10 posts per visit, then Facebook's closeless sign-in wall (the normal end of a scan).
- The owner's phone VPN cannot be turned off, and "our users" are on VPNs too. Hence the architecture below.

## Target architecture

```
Home Windows PC (no VPN)                         GitHub repo                      Phones (on VPN)
collector: headless Chromium every ~5 min  ──►  branch `data`: feed.json  ◄──  app reads feed.json FIRST
           (backs off when limited)              (one force-pushed commit)       falls back to its own scan
```

Feed contract (stable; both sides already agree on it):
`https://raw.githubusercontent.com/nzsnapshot/checkpoint-watch/data/feed.json`

```json
{"version":1,"page":"CheckpointNZ","generatedAt":0,"lastFullScanAt":0,
 "collector":{"outcome":"FEED","posts":10},
 "posts":[{"id":"1614…","createdAt":0,"text":"…","url":"https://www.facebook.com/CheckpointNZ/posts/1614…","image":"https://scontent…fbcdn.net/…"}]}
```
Newest first, up to 150 posts / 72 h. `outcome` is `FEED | STARVED | BLOCKED | ERROR`.

## To do on the Windows PC, in order

1. **Collector** (`git checkout feat/home-collector`):
   - Prerequisites: Node 18+ (`winget install OpenJS.NodeJS.LTS`), git, `gh auth login` (HTTPS) so the PC can push to the repo, and **no VPN on this PC**.
   - `cd collector; npm install; npx playwright install chromium; npm test`
   - `node src\index.js --once --dry-run` → expect `FEED` with ~10 posts. `STARVED` means this connection is being limited (VPN?).
   - Write the installers described in the brief below, run one real publish in the foreground (`node src\index.js --once`) so any Git credential prompt is visible, confirm the `data` branch and `feed.json` appear on GitHub, then register the logon task and merge the branch to `main`.
   - Windows installer requirements: Windows PowerShell 5.1 compatible, non-admin, `$PSScriptRoot`-relative, Scheduled Task `CheckpointWatchCollector` (AtLogOn, restart on failure, no time limit, hidden window via `run-windows.ps1`), Startup-folder shortcut as the fallback, `uninstall-windows.ps1`, `status-windows.ps1` (task state + last log lines + last outcome).
2. **App relay reader** (`feat/v1.1.0`): `RelayFeedParser` (pure, hostile-input safe, version 1 only, image host must end with `.fbcdn.net`), `RelayFeedFetcher` (HTTPS GET, ≤ 2 MB, 10 s timeouts, cache-busting `?t=<epochMinutes>`), and in `ScanCoordinator`: fetch the relay first; if fresh (`generatedAt` ≤ 20 min and `lastFullScanAt` ≤ 60 min old) record its posts with `CollectorKind.RELAY` and skip the WebView; otherwise run the existing pipeline and still merge the relay's posts. Labels: "Home collector". Diagnostics lines `relay=fresh|stale|unreachable|invalid`.
3. Finish images + Map button (see table), bump to **1.1.0 / versionCode 4**, update README (VPN section, hosts contacted: facebook.com, fbcdn.net, raw.githubusercontent.com).
4. Verify: `node` tests, `gradlew clean testDebugUnitTest lintDebug assembleDebug`.

## Releases — IMPORTANT

Obtainium only accepts updates signed with the **same key** as v1.0.x. That key exists **only on the MacBook** (outside the repo) and must never be committed or uploaded. Either build and publish releases from the MacBook (`./gradlew :app:packageReleaseApk -PversionCode=N -PversionName=X.Y.Z`, then `gh release create vX.Y.Z dist/checkpoint-watch-X.Y.Z.apk`), or the owner copies the key folder to the PC by hand (USB) and creates `keystore.properties` there. `packageReleaseApk` refuses to run without it, by design. The Windows PC can build and test **debug** APKs freely.

## House rules for this repo

- Commits use the GitHub no-reply identity already configured in the MacBook clone; on the PC set the same **for this repo only**: `git config user.name nzsnapshot` and the `…+nzsnapshot@users.noreply.github.com` address shown on existing commits. Never commit real names, e-mail addresses, machine usernames or absolute home paths — the history was scrubbed once already.
- Every commit message ends with a `Co-Authored-By:` line for the AI that wrote it.
- No Google services, no analytics, no secrets in the repo. Calm NZ English copy; never call a report "active" or "confirmed".
- In-app diagnostics: Settings → Scan history → **Copy last scan**. Ask the owner for that text before changing `collector.js`.
