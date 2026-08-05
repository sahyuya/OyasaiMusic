import test from "node:test";
import assert from "node:assert/strict";
import { createInitialParts } from "../src/converter.js";
import { mergeMidiDocuments } from "../src/midi-merge.js";

function midi(name, startMs, durationMs = 1000) {
  return {
    format: 1,
    ppq: 480,
    title: name,
    sourceName: `${name}.mid`,
    durationMs,
    notes: [{ id: 0, order: 0, trackIndex: 0, trackName: "Piano", startMs, durationMs: 100, midi: 60, channel: 0, program: 0, percussion: false }],
    tracks: [{ index: 0, name: "Piano", eventCount: 2, noteCount: 1, channels: [0], programs: [0] }],
    tempos: [{ tick: 0, timeMs: 0, tempo: 500_000, bpm: 120 }],
    timeSignatures: [],
    warnings: {},
  };
}

test("複数MIDIを同じ開始位置へ重ね、トラックを維持する", () => {
  const merged = mergeMidiDocuments([midi("Lead", 0), midi("Bass", 250)]);
  assert.deepEqual(merged.notes.map((note) => note.startMs), [0, 250]);
  assert.equal(merged.tracks.length, 2);
  assert.notEqual(merged.notes[0].trackIndex, merged.notes[1].trackIndex);
  assert.match(merged.notes[0].trackName, /Lead/);
  assert.match(merged.notes[1].trackName, /Bass/);
  assert.equal(createInitialParts(merged).parts.length, 2);
});

test("複数MIDIを指定間隔で末尾へ連結する", () => {
  const merged = mergeMidiDocuments([midi("A", 0, 1000), midi("B", 0, 500)], { mode: "append", gapMs: 200 });
  assert.deepEqual(merged.notes.map((note) => note.startMs), [0, 1200]);
  assert.equal(merged.sources[1].startOffsetMs, 1200);
});
