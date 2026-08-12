import assert from "node:assert/strict";
import test from "node:test";
import { normalizePreviewStart, selectPreviewNotes } from "../src/preview-selection.js";

const notes = [
  { partId: "lead", timeMs: 1_000 },
  { partId: "bass", timeMs: 2_000 },
  { partId: "lead", timeMs: 4_000 },
  { partId: "bass", timeMs: 9_000 },
];

test("全パート編集では全ノート、パート編集では選択パートだけを試聴する", () => {
  assert.equal(selectPreviewNotes(notes, "all", "lead").length, 4);
  assert.deepEqual(selectPreviewNotes(notes, "part", "lead").map((note) => note.timeMs), [1_000, 4_000]);
});

test("切替先パートの末尾を越えた再生位置は、そのパートの先頭へ戻す", () => {
  const lead = selectPreviewNotes(notes, "part", "lead");
  assert.equal(normalizePreviewStart(lead, 8_000), 1_000);
  assert.equal(normalizePreviewStart(lead, 3_000), 3_000);
});

test("切替先パートより前の再生位置も、最初の発音位置へ合わせる", () => {
  const bass = selectPreviewNotes(notes, "part", "bass");
  assert.equal(normalizePreviewStart(bass, 0), 2_000);
  assert.equal(normalizePreviewStart([], 5_000), 0);
});
