import assert from "node:assert/strict";
import test from "node:test";
import {
  convertMidi,
  createInitialParts,
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

test("1音だけを新しいパートへ分け、既存パートへ戻せる", () => {
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
