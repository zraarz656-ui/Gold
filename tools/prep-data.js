#!/usr/bin/env node
/**
 * Download XAUUSD 1-minute candles with dukascopy-node and write xauusd_m1.csv.
 *
 *   node tools/prep-data.js <from> <to> [outFile]
 *
 * `<from>` / `<to>` accept `YYYY-MM-DD` (inclusive) or ISO-8601 date-times.
 * Output columns: ts,o,h,l,c,v with `ts` as UTC epoch milliseconds.
 */

const fs = require("fs");
const path = require("path");
const { getHistoricalRates } = require("dukascopy-node");

const GAP_MS = 5 * 60 * 1000;

function usage() {
  console.error("usage: node tools/prep-data.js <from YYYY-MM-DD> <to YYYY-MM-DD> [outFile]");
  process.exit(1);
}

function parseDate(value) {
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) {
    console.error(`invalid date: ${value}`);
    process.exit(1);
  }
  return d;
}

function toCsvLine(row) {
  // row: { timestamp, open, high, low, close, volume }
  return [row.timestamp, row.open, row.high, row.low, row.close, row.volume].join(",");
}

async function main() {
  const [fromArg, toArg, outArg] = process.argv.slice(2);
  if (!fromArg || !toArg) usage();

  const from = parseDate(fromArg);
  const to = parseDate(toArg);
  if (from > to) {
    console.error("`from` must be on or before `to`");
    process.exit(1);
  }

  const outFile = outArg || "xauusd_m1.csv";

  console.error(`downloading XAUUSD m1 ${from.toISOString()} .. ${to.toISOString()} ...`);
  const rows = await getHistoricalRates({
    instrument: "xauusd",
    from,
    to,
    timeframe: "m1",
    format: "json",
    volumes: true,
    ignoreFlats: true,
  });

  // Sort ascending, then drop duplicate timestamps (keep the first).
  const sorted = [...rows].sort((a, b) => a.timestamp - b.timestamp);
  const seen = new Set();
  const unique = [];
  for (const row of sorted) {
    if (seen.has(row.timestamp)) continue;
    seen.add(row.timestamp);
    unique.push(row);
  }

  let gaps = 0;
  for (let i = 1; i < unique.length; i++) {
    if (unique[i].timestamp - unique[i - 1].timestamp > GAP_MS) gaps++;
  }

  const lines = ["ts,o,h,l,c,v", ...unique.map(toCsvLine)];
  fs.mkdirSync(path.dirname(path.resolve(outFile)), { recursive: true });
  const payload = lines.join("\n") + "\n";
  if (outFile.endsWith(".gz")) {
    // Bundle into the app as assets/xauusd_m1.csv.gz
    const zlib = require("zlib");
    fs.writeFileSync(outFile, zlib.gzipSync(Buffer.from(payload), { level: 6 }));
  } else {
    fs.writeFileSync(outFile, payload);
  }

  const first = unique.length ? unique[0].timestamp : "n/a";
  const last = unique.length ? unique[unique.length - 1].timestamp : "n/a";
  console.log(`wrote ${outFile}`);
  console.log(`rows:          ${unique.length}`);
  console.log(`first ts:      ${first}`);
  console.log(`last ts:       ${last}`);
  console.log(`gaps >5min:    ${gaps}`);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
