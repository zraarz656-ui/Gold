# TradeQuest — agent notes

Offline, single-user Android app. Kotlin + Jetpack Compose, MVVM, Hilt, Room,
Coroutines/Flow, WorkManager, DataStore. XAUUSD only; a "live" market is replayed from
bundled 1-minute history using a whole-week time offset.

## Modules

- `engine` — pure Kotlin/JVM (no Android). `ClockEngine`, `MarketCalendar`, `Aggregator`,
  `FillEngine`, `AccountState`, constants (`MarketTime`, lot/commission/spread levels).
  Tests run on the JVM with JUnit 5.
- `chart` — Compose candlestick chart. `ChartController` holds `ChartState` (bars, viewport,
  theme, `orderLines`, `markers`, `marketClosed`); `ChartRenderer` draws it. `ChartOverlays.kt`
  defines `ChartOrderLine` / `ChartMarker`.
- `data` — Room database, DAOs, repositories (`CandleRepository`, `SeasonRepository`,
  `TradingRepository`, `SettingsRepository`), `PreferencesStore` (DataStore),
  `DatasetImporter`, `CatchUpProcessor`, `AccountCheckpoint`. Tests are Robolectric + in-memory Room.
- `app` — Hilt/Compose app: `TradingViewModel`, screens, `LiveClock` (minute ticker),
  `CatchUpWorker`/`CatchUpScheduler`/`Notifier`.

### Theming

All app colours come from `app/ui/theme/Theme.kt` (`TradeQuestColors` + `LocalTradeQuestColors`).
`MainActivity` wraps the UI in `TradeQuestTheme(controller.state.theme)`, so the app palette
follows the chart theme (dark, light, OLED, colour-blind). Screens must **not** hard-code
`Color(...)`; use the tokens (`surface`, `surfaceVariant`, `onSurface`, `onSurfaceVariant`,
`outline`, `accent`, `onAccent`, `positive`, `negative`, `warning`). Text inputs must use
`TradeNumberField`/`tradeFieldColors()` (explicit `OutlinedTextFieldDefaults.colors`) so
input text, labels and placeholders stay readable. The selected theme is persisted in
DataStore under `theme_id` (`PreferencesStore`) and applied before the first frame via
`TradingViewModel.bootstrap`; `themeForId` maps the id back to a `ChartTheme`, defaulting
to Dark. `ThemeContrastTest` asserts >= 4.5:1 for text tokens and >= 3:1 for `outline`;
`OrderSheetThemeUiTest` (debug-only) types into the sheet in all four themes.

## Build & test

JDK 17 or 21 and the Android SDK are required. In this environment the sandbox `$HOME`
can be wiped between sessions, so the toolchain is kept on the persistent `/workspace`
mount at `/workspace/toolchain/jdk-21.0.12.1+1` and `/workspace/toolchain/android-sdk`
(`local.properties` points `sdk.dir` there):

```bash
export JAVA_HOME=/workspace/toolchain/jdk-21.0.12.1+1
export ANDROID_HOME=/workspace/toolchain/android-sdk ANDROID_SDK_ROOT=/workspace/toolchain/android-sdk
./gradlew :data:test :engine:test :app:assembleDebug
```

Debug builds are signed with the committed fixed key `keystore/tradequest-debug.keystore`
(alias `tradequest-debug`, password `android`; see `keystore/KEYSTORE.md`). One stable key
means an updated APK installs over the previously installed build without uninstalling.
The key is debug-only and not a secret; release builds are unsigned here.

## Phase 1/2 recovery from the debug APK

The original Phase 1/2 sources were never pushed and no longer exist on disk. The provided
debug APK (`/workspace/recovered/apk/app-debug.apk`) is the **only** source of truth. To
inspect it:

```bash
# Standard class decompile (composable bodies are often dumped as bytecode: "Method dump skipped")
java -jar jadx/bin/jadx --no-res -d out <apk>
# Reconstructable Kotlin for a single class whose body was skipped:
java -jar jadx/bin/jadx --no-res --decompilation-mode simple -d out --single-class com.tradequest.chart.ChartScreenKt <apk>
```

The chart's gesture semantics (one pointer loop: pan/fling/pinch/gutter drag+scale,
long-press crosshair, double-tap auto-fit, news tap) mirror `CandleChartKt$chartGestures$2`.
Engine models (`Candle(ts,o,h,l,c,v)`, `EquitySnapshot(ts,equity,balance,usedMargin)`,
`NewsEvent(ts,title,impact)`), `ChartController`, `ChartState`, `Viewport`, `ChartMath`
constants and `GestureMath` were diffed field-by-field against the APK and match.

## Gotchas

- Assets named `*.gz` are transparently gunzipped by AGP when merged into the APK, so the
  bundled `app/src/main/assets/xauusd_m1.csv.gz` is delivered as `assets/xauusd_m1.csv`.
  `DatasetImporter` reads both names and sniffs the gzip magic; never assume the `.gz` name
  survives to the device.
- `gradlew clean` reinstalls nothing; the JDK/Android SDK are not part of the repo. They are
  (re)installed to `/workspace/toolchain/…` (see Build & test), not `$HOME`, because the
  sandbox home is not durable across sessions.

## Invariants

- Chart geometry has exactly one source of truth: `ChartMath.plotRect(canvasWidth,
  canvasHeight, axisWidthPx, bottomAxisPx)` returns the plot area (everything except the
  right price gutter and the bottom time axis). `geometryFor` builds `ChartGeometry` from it
  and every draw path, hit test and label derives from `geo.plot` / the `priceToY(.., PlotRect)`
  / `yToPrice(.., PlotRect)` overloads. The plot layers are wrapped in `clipRect(plot..)` and
  the gutter labels in `clipRect(plot.right .. size.width)`, so nothing can bleed over a
  neighbouring strip/tab regardless of layout.
- A candle may only be exposed when `ts <= ClockEngine.lastVisibleCandleTs(histNow)`
  (`CandleRepository` enforces this; never hand it a raw `histNow`).
- Catch-up is deterministic: `AccountCheckpoint` (serialised `AccountState`) is written in
  the same Room transaction as `Season.lastProcessedTs`, so an interrupted run resumes
  exactly. Keep both in one `withTransaction`.
- Orders are evaluated from the next candle after placement.
- `OrderType.MARKET` carries no side, so a market `OrderRequest` must set `side`
  explicitly; the order sheet's `initialType`/`initialSide` seed the form but never fix the
  side, and both Buy and Sell actions are always rendered for a market order.
- Catch-up runs on the minute tick as well as on open/resume (`TradingViewModel.observeTicker`).
  The tick is what makes a market order fill when the next candle closes; without it fills
  would only happen on reopen. Running catch-up rewrites the order tables, so every trade
  mutation (`placeOrder`, `cancelOrder`, `closePosition`, `editStops`, `applyLevelOutcome`,
  `debugTimeTravel`) takes the same `catchUpMutex` to avoid a lost-write race.
- Chart order levels (Phase 3) are pure data: `ChartOverlays.kt` defines `ChartOrderLine`
  (`handles` lists the missing "+SL"/"+TP" affordances), `ChartOverlayState` (lines, markers,
  `bid`, `spread`) and `DragPreview`. `LevelEdit.kt`/`LevelRules` snap to 0.01, validate
  side/entry/market and emit a `LevelOutcome`; `LevelHitTest` resolves a touch (close box >
  handle > line/tag) from the same `LevelGeometry` constants the renderer draws with. The
  gesture loop is the **only** place a level drag begins; `ChartController.beginLevelDrag` /
  `updateLevelDrag` / `endLevelDrag` own the preview, which survives `setOverlay` rebuilds so
  an in-flight drag is never clobbered. `TradeQuestScreen` shows the confirm chip only for a
  fresh handle level (`LevelOutcome.Set.isNew`); moving an existing line commits on release.
  Both persist through `TradingViewModel.applyLevelOutcome` → `TradingRepository`.
- Every closed trade carries `closedAt` = the timestamp of the candle whose processing
  closed it (`ClosedPosition.closeTs`): SL/TP/stop-out use the candle that triggered them,
  a manual close uses the season's last visible candle. `closedAt` is never wall-clock and
  never null for rows written by the engine. Legacy null rows are not migrated; use the
  debug "Reset season" action instead.
- Process death must lose nothing: all authoritative state lives in Room, not in memory.
- Debug-only actions ("Reset season", "Time travel +N") are gated by `BuildConfig.DEBUG`
  in the UI. Time travel only shifts `Season.offsetMs` backwards (advancing `histNow`); it
  touches no candle or trade data, is clamped so the last visible candle is never beyond
  the dataset's last candle, and then runs the normal catch-up.
- Chart screen layout: one header row holds the Chart/Positions tabs, the timeframes
  (horizontally scrollable) and a "⋮" button opening the `ChartSettingsSheet` (theme +
  label size, persisted through `TradingViewModel.setTheme/setLabelSize`). The plot keeps
  at least 55% of a 20:9 screen (`ChartHeightTest`). The right price gutter is measured
  from the widest price text (`ChartGutter`, widest label + 12dp); the current-price pill
  fills that gutter, is opaque and is drawn last so nothing covers it, and never extends
  past the screen edge. Every SL/TP/pending/entry line gets exactly one tag and no tag is
  ever dropped: tags stack at least 20dp apart (`OrderTags`, shared by the renderer and the
  hit test) and a tag displaced from its line draws a thin leader. A lone entry within 20dp
  of another merges into a grouped "N pos  pnl" tag (`EntryGroups`); its width is measured
  so the text is never clipped. Only the selected position (tap an entry tag to select,
  tap again to clear) offers "+SL"/"+TP" handles, placed left of the whole tag column.

## Chart scroll and price tag (Phase 3 polish)
- Horizontal scroll (`ChartMath.clampScroll` / `ChartController.pan`) allows travel past the
  newest candle: `rightPaddingMax` is 60% of the plot width in candles; the default live-edge
  padding is `max(10 candles, 12% of width)`. `maxRightPadding` is floored at the default so
  the live edge is always reachable when fewer than ~17 candles fit.
- With fewer candles than fit across the plot, the run is parked on the right (newest candle
  at the default padding) instead of clipped at the left border, on every timeframe.
- `liveEdgeFollowing` is true only within a +/-1.5-candle band around the live-edge scroll;
  scrolling into the empty right-hand space (or the past) stops the chart following new
  candles. `jumpToLatest()` and Fit both re-clamp to the default padding.
- The current-price tag is slim: 22dp tall, 4dp radius, 12sp semibold, with a notch pointing
  at the price line and a dot at the last close. `CURRENT_PRICE_TAG_SP` and the gutter padding
  (`ChartGutter.PAD_DP = 4dp`) must be kept in sync with `LevelGeometry` so the tag fits.
- `ChartState.countdownMs` (mm:ss under the price tag, `formatCountdown`) is refreshed from
  `refreshDerived()` on each ticker fire; while the market is shut it is null and the footer
  shows "Opens in Hh Mm (weekday HH:mm local)" from `MarketCalendar.nextOpen`. The countdown
  on/off preference lives in `PreferencesStore` (`show_candle_countdown`).

## Order timing (the 11:39 bug)
- With the market OPEN a `MARKET` order fills **at once** via `FillEngine.fillMarketImmediately`
  at the displayed ask (long) or bid (short), stamped `openedAt = placedAt`. It never waits for
  the next candle and is never evaluated against the candle it was placed on.
- Only `BUY_LIMIT`/`SELL_LIMIT`/`BUY_STOP`/`SELL_STOP`, plus SL and TP, rest and fill on a
  later candle (`candle.ts > order.placedAtTs`).
- With the market SHUT (weekend) a `MARKET` order is stored as `OrderStatus.QUEUED` and the
  engine fills it at the first candle after the reopen, at that candle's open-based price
  (`reason = "QUEUED"`). The card reads "Queued, fills when market opens" and the chart footer
  says market orders are queued.
- `placedAtTs` in `Order`/`OrderDto` is the placement instant. It is the *live* historical
  clock `_histNow` at placement, which is NOT `lastVisibleCandleTs(histNow)`: the latter is up
  to one minute earlier. `fillMarketImmediately` uses the passed bid/ask (the same quote the
  sheet shows), so the fill price matches the button.
- **Never** stamp an entry earlier than its placement: `FillEngine` uses
  `openedAtTs = maxOf(candle.ts, order.placedAtTs)`. The 11:39-vs-11:48 report came from the
  engine stamping the *earlier* `lastVisibleCandleTs` while the app displayed the later
  `_histNow`; using one consistent `placedAt` fixes it.
- `place(seasonId, request, placedAt, bid, ask)` is the only entry point; the VM passes
  `_histNow` and the current quote. `OrderStatus` gained `QUEUED` (between PENDING and OPEN);
  `OrderStatus.QUEUED` behaves like PENDING in the chart overlays and the live-orders queries.

## Trading is gated on data readiness (the "no orders after minimize/restore" bug)
- A submit is dropped when the app is not yet ready. The old flag `ready` was a plain
  `var` set at the *end* of `bootstrap()`, after `startSeason()` — but `startSeason()` sets
  `_quote` inside `loadInitialWindow()`. There was therefore a window (most reachable after
  a process restart following minimize) in which the chart showed live prices while every
  Buy/Sell early-returned with `W/TradeQuest: placeOrder: early return - Market data is
  still loading`. Symptom: "price updates, orders do nothing".
- Fix: `startSeason()` now publishes `_histNow`, then calls `markReady()` (attaches the
  ticker and order observers) **before** catch-up/window loading, so a submit is only ever
  gated on real prerequisites (active season, lot size, a live quote for a market order,
  not over the daily loss limit). `markReady()` is idempotent.
- Readiness is exposed as `StateFlow<Boolean> ready` for the UI/tests. `placeOrder` logs
  every step (tag `TradeQuest`) and sets `submitError` for a snackbar when it must refuse.
  A `bootstrap()` failure is caught and shown as `StartupPhase.ERROR` instead of leaving the
  app live-looking but unable to trade.
- Regression test `OrderSubmitAfterResumeTest` drives the real ViewModel through
  launch → resume → submit and asserts a position opens; `submitIsAcceptedAsSoonAsTheQuoteIsLive`
  pins the exact failure window.

## Closed trades, P&L and invalid stop/trigger prices (findings 1–4)
- **Finding #1/#4 — "closed trades vanish" / "orders close by themselves".** `TradingViewModel.history`
  was built with `closedSince(seasonId, lastVisibleCandleTs(season.lastProcessedTs))`, i.e. a
  *sliding one-minute window* whose lower bound advanced every minute as catch-up moved the
  clock. Any trade closed more than a minute earlier silently dropped out. History now uses
  `TradingRepository.closedOrders(seasonId)` (`closedAt >= 0`): closed trades are permanent.
- **Finding #4 root cause — invalid stop/trigger prices.** Manual SL/TP inputs and resting
  trigger prices were never validated. The chart drag path checks them via `LevelRules`, but
  the order sheet and the Positions editor did not. An SL on the wrong side of the entry makes
  the engine close the position on the very next candle (e.g. a long at 3980 with `sl=5000`
  "closed as SL" at +$1,953), and a buy limit above the market fills instantly as a GAP.
  `OrderRules` (data module, pure) now rejects: SL/TP on the wrong side of, or equal to, the
  entry; non-positive prices; a resting trigger on the wrong side of the market or within
  `spread + 0.10` of it; and `lots <= 0`. Enforced in `TradingViewModel.placeOrder`
  (authoritative), `editStops`, and inline in `OrderSheet` (shown without dismissing the sheet).
- **Finding #2 — Day P&L.** With no position, `dayPnl` fell back to `season.startBalance`
  (via `DailyStats.startEquity` defaulting there), so it showed the whole-season P&L. It now
  prefers `DailyStats.startEquity`, then `state.dayStartEquity` (the engine records day-start
  equity at each rollover), then the season balance, and persists a `DailyStats` row.
- Regression tests: `ClosedTradeHistoryTest`, `DayPnlTest`, `OrderRulesTest`,
  and two cases in `OrderSubmitAfterResumeTest`. All share `TradingHarness` (Robolectric +
  in-memory Room + the real ViewModel/repositories).

## Pushing (auth note, current environment)
- The default `git push` prompts for a username and hangs, so always push non-interactively
  with `GIT_TERMINAL_PROMPT=0`.
- Use `GH_TOKEN`: `GIT_TERMINAL_PROMPT=0 git push "https://x-access-token:${GH_TOKEN}@github.com/<owner>/<repo>.git" HEAD:refs/heads/<branch>`.
  It authenticates as the repo owner and succeeds.
- `GH_PUSH_TOKEN` is rejected ("Invalid username or token"); `GITHUB_TOKEN` authenticates
  the read API but is an integration token with no `contents:write` scope, so git push and
  the write API return 403 "Resource not accessible by integration". Do not embed any token
  in `.git/config`; pass it in the push URL only.
