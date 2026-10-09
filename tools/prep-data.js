#!/usr/bin/env node
/**
 * Prepare the bundled XAUUSD 1-minute dataset.
 *
 * Two modes:
 *   node tools/prep-data.js <from YYYY-MM-DD> <to YYYY-MM-DD> [outFile]
 *       Download real candles from Dukascopy in weekly chunks (retry/backoff on 429).
 *
 *   node tools/prep-data.js --from-csv <path.csv> [outFile]
 *       Convert a CSV you downloaded elsewhere into the canonical format.
 *
 * Output: `<outFile>` (default app/src/main/assets/xauusd_m1.csv.gz) with columns
 * `ts,o,h,l,c,v`, ts as UTC epoch milliseconds, sorted ascending and de-duplicated, plus a
 * sibling `xauusd_m1.meta.json` describing provenance:
 *   { source, tool, version, fetchedAt, from, to, rowCount, sha256 }
 *
 * `sha256` is the SHA-256 of the canonical CSV **content** (the bytes the app ends up
 * reading; AGP gunzips the `.gz` transport at merge time), so the app can verify the file
 * it actually loads. Only this script writes the meta file.
 */

const fs = require("fs");
const path = require("path");
const zlib = require("zlib");
const crypto = require("crypto");

const META_NAME = "xauusd_m1.meta.json";
const DAY_MS = 24 * 60 * 60 * 1000;
const WEEK_MS = 7 * DAY_MS;

function usage() {
  console.error(
    "usage:\n" +
      "  node tools/prep-data.js <from YYYY-MM-DD> <to YYYY-MM-DD> [outFile]\n" +
      "  node tools/prep-data.js --from-csv <path.csv> [outFile]",
  );
  process.exit(1);
}

/**
 * The tool's own version, read from tools/package.json. Kept independent of the optional
 * dukascopy-node dependency so `--from-csv` (which needs no network) reports a real version
 * even when `node_modules` is absent.
 */
function toolVersion() {
  try {
    return require("./package.json").version || "0.0.0";
  } catch {
    return "0.0.0";
  }
}

function parseDate(value) {
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) {
    console.error(`invalid date: ${value}`);
    process.exit(1);
  }
  return d;
}

/** Quantise a price to the 2-decimal grid real XAUUSD quotes use. */
function price2(x) {
  return Math.round(x * 100) / 100;
}

/** Parse a timestamp cell: epoch ms, epoch seconds, or an ISO-8601 date-time string. */
function parseTs(value) {
  if (value === undefined || value === null) return NaN;
  const s = String(value).trim();
  if (/^\d+$/.test(s)) {
    const n = Number(s);
    // 10-digit values are epoch seconds; 13-digit values are epoch ms.
    return n < 1e11 ? n * 1000 : n;
  }
  const parsed = Date.parse(s);
  return Number.isNaN(parsed) ? NaN : parsed;
}

/** Recognised column names; used to map cells when a header row is present. */
const CANDLE_NAMES = ["ts", "o", "h", "l", "c", "v"];

function parseRowCells(header, cells) {
  // When a header carries the canonical names, map by name; otherwise go positional. This
  // tolerates both `ts,o,h,l,c,v` files and raw downloads with ISO dates and no header.
  const named = header && CANDLE_NAMES.every((n) => header.includes(n));
  const cell = (name, pos) =>
    named ? cells[header.indexOf(name)] : cells[pos];
  const ts = parseTs(cell("ts", 0));
  const o = Number(cell("o", 1));
  const h = Number(cell("h", 2));
  const l = Number(cell("l", 3));
  const c = Number(cell("c", 4));
  const rawV = cell("v", 5);
  const v = Number(rawV);
  if (![ts, o, h, l, c].every((n) => Number.isFinite(n))) return null;
  return { ts, o, h, l, c, v: Number.isFinite(v) ? v : 0 };
}

/** True when the first row is a header rather than data. */
function looksLikeHeader(cells) {
  const first = cells[0];
  if (first === undefined) return false;
  if (/^\d+$/.test(first.trim())) return false;
  if (!Number.isNaN(Date.parse(first.trim()))) return false;
  return true;
}

/** Read a CSV file (Dukascopy raw or our canonical shape) into row objects. */
function readCsvFile(file) {
  const text = fs.readFileSync(file, "utf8");
  const lines = text.split(/\r?\n/).filter((l) => l.trim() !== "");
  if (lines.length === 0) return [];
  let header = null;
  let start = 0;
  const firstCells = lines[0].split(",").map((s) => s.trim().toLowerCase());
  if (looksLikeHeader(lines[0].split(","))) {
    header = firstCells;
    start = 1;
  }
  const rows = [];
  for (let i = start; i < lines.length; i++) {
    const row = parseRowCells(header, lines[i].split(",").map((s) => s.trim()));
    if (row) rows.push(row);
  }
  return rows;
}

/** Sort ascending by ts and drop duplicate timestamps (keep the first). */
function normalise(rows) {
  const sorted = [...rows].sort((a, b) => a.ts - b.ts);
  const seen = new Set();
  const unique = [];
  for (const row of sorted) {
    if (seen.has(row.ts)) continue;
    seen.add(row.ts);
    unique.push(row);
  }
  return unique;
}

function toCsvLine(row) {
  return [
    row.ts,
    price2(row.o),
    price2(row.h),
    price2(row.l),
    price2(row.c),
    row.v ?? 0,
  ].join(",");
}

/** Serialise rows to the canonical CSV payload, with a header line. */
function toPayload(rows) {
  const lines = ["ts,o,h,l,c,v", ...rows.map(toCsvLine)];
  return lines.join("\n") + "\n";
}

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

/** Retry an async call on 429/rate-limit with exponential backoff. */
async function withRetry(label, fn, attempts = 6, baseMs = 2000) {
  let lastErr;
  for (let attempt = 1; attempt <= attempts; attempt++) {
    try {
      return await fn();
    } catch (err) {
      lastErr = err;
      const msg = String((err && err.message) || err);
      const rate = /429|too many|rate ?limit/i.test(msg);
      if (attempt === attempts) break;
      const wait = baseMs * 2 ** (attempt - 1) + Math.floor(Math.random() * 500);
      console.error(
        `[prep-data] ${label} failed (${rate ? "rate-limited" : "error"}): ${msg}. ` +
          `retry ${attempt}/${attempts - 1} in ${wait}ms`,
      );
      await sleep(wait);
    }
  }
  throw lastErr;
}

/** Download one weekly chunk via dukascopy-node 1.50 (dates: { from, to }). */
async function fetchChunk(lib, from, to) {
  const data = await withRetry(`chunk ${from.toISOString().slice(0, 10)}`, () =>
    lib.getHistoricalRates({
      instrument: "xauusd",
      dates: { from, to },
      timeframe: "m1",
      format: "json",
      volumes: true,
      ignoreFlats: true,
    }),
  );
  return (data || []).map((r) => ({
    ts: Number(r.timestamp),
    o: Number(r.open),
    h: Number(r.high),
    l: Number(r.low),
    c: Number(r.close),
    v: r.volume === undefined ? 0 : Number(r.volume),
  }));
}

async function downloadWeekly(from, to) {
  const lib = require("dukascopy-node");
  const version = `${toolVersion()} (dukascopy-node ${require("dukascopy-node/package.json").version})`;
  const rows = [];
  // Walk forward in 7-day chunks; the last chunk is clipped to `to`.
  let cursor = from;
  while (cursor < to) {
    const chunkEnd = new Date(Math.min(cursor.getTime() + WEEK_MS, to.getTime()));
    console.error(
      `[prep-data] downloading ${cursor.toISOString()} .. ${chunkEnd.toISOString()} ...`,
    );
    rows.push(...(await fetchChunk(lib, cursor, chunkEnd)));
    cursor = chunkEnd;
  }
  return { rows, version, source: "dukascopy" };
}

function writeMeta(outFile, meta) {
  const metaPath = path.join(path.dirname(path.resolve(outFile)), META_NAME);
  fs.writeFileSync(metaPath, JSON.stringify(meta, null, 2) + "\n");
  console.error(`[prep-data] wrote ${metaPath}`);
}

function writeOutput(outFile, rawRows, ctx) {
  const unique = normalise(rawRows);
  if (unique.length === 0) {
    console.error("[prep-data] no rows; refusing to write an empty dataset");
    process.exit(1);
  }

  let gaps = 0;
  for (let i = 1; i < unique.length; i++) {
    if (unique[i].ts - unique[i - 1].ts > 5 * 60 * 1000) gaps++;
  }

  const payload = toPayload(unique);
  const payloadBytes = Buffer.from(payload, "utf8");

  fs.mkdirSync(path.dirname(path.resolve(outFile)), { recursive: true });
  if (outFile.endsWith(".gz")) {
    fs.writeFileSync(outFile, zlib.gzipSync(payloadBytes, { level: 6 }));
  } else {
    fs.writeFileSync(outFile, payloadBytes);
  }

  // sha256 over the canonical CSV content, and rowCount over data rows (header excluded).
  const sha256 = crypto.createHash("sha256").update(payloadBytes).digest("hex");
  const from = ctx.fromIso || new Date(unique[0].ts).toISOString();
  const to = ctx.toIso || new Date(unique[unique.length - 1].ts + 60_000).toISOString();

  writeMeta(outFile, {
    source: ctx.source,
    tool: "tools/prep-data.js",
    version: ctx.version,
    fetchedAt: new Date().toISOString(),
    from,
    to,
    rowCount: unique.length,
    sha256,
  });

  console.log(`wrote ${outFile}`);
  console.log(`rows:          ${unique.length}`);
  console.log(`first ts:      ${unique[0].ts}`);
  console.log(`last ts:       ${unique[unique.length - 1].ts}`);
  console.log(`gaps >5min:    ${gaps}`);
  console.log(`sha256:        ${sha256}`);
}

async function main() {
  const args = process.argv.slice(2);
  const fromCsvIdx = args.indexOf("--from-csv");

  if (fromCsvIdx >= 0) {
    const csvPath = args[fromCsvIdx + 1];
    if (!csvPath) usage();
    const outArg = args[fromCsvIdx + 2] || "app/src/main/assets/xauusd_m1.csv.gz";
    const rows = readCsvFile(csvPath);
    console.error(`[prep-data] read ${rows.length} raw rows from ${csvPath}`);
    writeOutput(outArg, rows, {
      source: `csv:${csvPath}`,
      version: `prep-data.js ${toolVersion()} (--from-csv)`,
    });
    return;
  }

  const [fromArg, toArg, outArg = "app/src/main/assets/xauusd_m1.csv.gz"] = args;
  if (!fromArg || !toArg) usage();
  const from = parseDate(fromArg);
  const to = parseDate(toArg);
  if (from >= to) {
    console.error("`from` must be before `to`");
    process.exit(1);
  }
  const dl = await downloadWeekly(from, to);
  writeOutput(outArg, dl.rows, {
    source: dl.source,
    version: dl.version,
    fromIso: from.toISOString(),
    toIso: to.toISOString(),
  });
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
