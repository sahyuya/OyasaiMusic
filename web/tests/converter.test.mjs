import assert from "node:assert/strict";
import test from "node:test";
import {
  automationValue,
  convertMidi,
  createInitialParts,
  deleteEmptyParts,
  moveSelectionToPart,
  splitSelectionIntoPart,
} from "../src/converter.js";

const midi = {
  notes: [
    note(0, 54, 0),
    note(1, 66, 100),
    note(2, 90, 200),
  ],
};

test("MIDI 54/66を音ブロックpitch 0/12へ変換する", () => {
  const { parts, assignments } = createInitialParts(midi);
  const result = convertMidi(midi, parts, assignments, { pitchPolicy: "fold", removeLeadingSilence: false });
  assert.equal(result.notes[0].pitch, 0);
  assert.equal(result.notes[1].pitch, 12);
  assert.equal(result.notes[2].pitch, 24);
  assert.equal(result.metrics.folded, 1);
});

test("選択ノートを新しいパートへ分けた後、既存の別パートへ移動できる", () => {
  const initial = createInitialParts(midi);
  const selected = new Set([1]);
  const split = splitSelectionIntoPart({
    parts: initial.parts,
    assignments: initial.assignments,
    selectedIds: selected,
    name: "メロディー",
    instrumentKey: "flute",
  });
  assert.ok(split.createdPart);
  assert.equal(split.assignments[1], split.createdPart.id);
  const moved = moveSelectionToPart(split.assignments, selected, initial.parts[0].id);
  assert.equal(moved[1], initial.parts[0].id);
  assert.equal(split.assignments[1], split.createdPart.id);
  assert.deepEqual(deleteEmptyParts(split.parts, moved), initial.parts);
});

test("コントロールレーンを線形補間して音量とPanへ反映する", () => {
  const source = { notes: [note(0, 60, 500)] };
  const initial = createInitialParts(source);
  const partId = initial.parts[0].id;
  const automation = {
    [partId]: {
      volume: [{ timeMs: 0, value: 0 }, { timeMs: 1000, value: 127 }],
      pan: [{ timeMs: 0, value: 0 }, { timeMs: 1000, value: 127 }],
    },
  };
  const result = convertMidi(source, initial.parts, initial.assignments, {
    removeLeadingSilence: false,
    preservePan: true,
  }, automation);
  assert.equal(automationValue(automation[partId].volume, 500, 127), 64);
  assert.equal(result.notes[0].volume, 40);
  assert.equal(result.notes[0].pan, 0);
});

function note(id, midiNumber, startMs) {
  return {
    id,
    trackIndex: 0,
    trackName: "All Notes",
    channel: 0,
    midi: midiNumber,
    velocity: 100,
    startMs,
    durationMs: 100,
    program: 0,
    channelVolume: 127,
    expression: 127,
    pan: 64,
    percussion: false,
  };
}
