import assert from "node:assert/strict";
import test from "node:test";
import {
  canonicalizeTempoMap,
  rebuildTempoMap,
  retimeForTempoChange,
  tempoAtTime,
} from "../src/tempo-map.js";

test("途中テンポ変更を維持しながら先頭BPMを変更する", () => {
  const original = canonicalizeTempoMap([
    { id: "a", timeMs: 0, bpm: 120 },
    { id: "b", timeMs: 2000, bpm: 60 },
  ], 480);
  assert.equal(original[1].tick, 1920);
  const changed = rebuildTempoMap(original.map((event) => event.id === "a" ? { ...event, bpm: 240 } : event), 480);
  assert.equal(changed[1].timeMs, 1000);
  assert.equal(tempoAtTime(changed, 999, 480).bpm, 240);
  assert.equal(tempoAtTime(changed, 1000, 480).bpm, 60);
});

test("テンポマップ編集時にノート、音価、オートメーション、表示位置を再配置する", () => {
  const oldTempos = canonicalizeTempoMap([
    { id: "a", timeMs: 0, bpm: 120 },
    { id: "b", timeMs: 2000, bpm: 60 },
  ], 480);
  const result = retimeForTempoChange({
    notes: [{ id: 0, startMs: 2500, durationMs: 500 }],
    timeSignatures: [{ tick: 0, timeMs: 0, numerator: 4, denominator: 4 }],
    automation: { "part-1": { volume: [{ timeMs: 2500, value: 100 }] } },
    view: { startMs: 2000, endMs: 3000, minPitch: 54, maxPitch: 78 },
    previewPositionMs: 2500,
    oldTempos,
    nextTempoEvents: oldTempos.map((event) => event.id === "a" ? { ...event, bpm: 240 } : event),
    ppq: 480,
  });
  assert.equal(result.tempos[1].timeMs, 1000);
  assert.equal(result.notes[0].startMs, 1500);
  assert.equal(result.notes[0].durationMs, 500);
  assert.equal(result.automation["part-1"].volume[0].timeMs, 1500);
  assert.equal(result.previewPositionMs, 1500);
  assert.equal(result.view.startMs, 1000);
  assert.equal(result.view.endMs, 2000);
});
