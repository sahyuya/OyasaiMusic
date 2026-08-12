import assert from "node:assert/strict";
import test from "node:test";
import { gunzipSync } from "node:zlib";
import { encodePasteTransfer } from "../src/paste-format.js";

test(".oyasaiバイト列をPaperダイアログ用の検証付き文字列へ変換する", async () => {
  const source = Uint8Array.from({ length: 2048 }, (_, index) => index % 251);
  const result = await encodePasteTransfer(source, { segmentSize: 256 });
  assert.equal(result.segments.length, result.segmentCount);
  assert.equal(result.text, result.segments.join("\n"));
  const payload = result.segments.map((segment, index) => {
    const match = segment.match(/^OMMT1:([0-9a-f]{32}):(\d+):(\d+):([0-9a-f]{64}):([A-Za-z0-9_-]+)$/);
    assert.ok(match);
    assert.equal(Number(match[2]), index + 1);
    assert.equal(Number(match[3]), result.segmentCount);
    assert.equal(match[4], result.checksum);
    assert.ok(match[5].length <= 256);
    return match[5];
  }).join("");
  const compressed = Buffer.from(payload.replace(/-/g, "+").replace(/_/g, "/"), "base64");
  assert.deepEqual(gunzipSync(compressed), Buffer.from(source));
});

test("通常サイズのデータは1回のダイアログ送信に収まる", async () => {
  const source = Uint8Array.from({ length: 4096 }, (_, index) => index % 19);
  const result = await encodePasteTransfer(source);
  assert.equal(result.segmentCount, 1);
  assert.ok(result.segments[0].length < 24_000);
});

test("大きなデータも各ダイアログの安全な文字数以内に分割する", async () => {
  let value = 0x12345678;
  const source = Uint8Array.from({ length: 100_000 }, () => {
    value ^= value << 13;
    value ^= value >>> 17;
    value ^= value << 5;
    return value & 0xff;
  });
  const result = await encodePasteTransfer(source);
  assert.ok(result.segmentCount > 1);
  assert.ok(result.segments.every((segment) => segment.length <= 24_000));
});
