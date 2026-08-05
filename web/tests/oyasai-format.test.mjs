import assert from "node:assert/strict";
import test from "node:test";
import { decodeOyasaiPackage, encodeOyasaiPackage } from "../src/oyasai-format.js";

test(".oyasaiバイナリを往復変換できる", async () => {
  const midi = {
    title: "試験曲",
    sourceName: "test.mid",
    format: 1,
    ppq: 480,
    tracks: [{ name: "Track" }],
    tempos: [{ bpm: 120 }],
  };
  const conversion = {
    notes: [{ timeMs: 250, instrumentKey: "flute", pitch: 12, volume: 80, pan: -25 }],
    metrics: { durationMs: 250, maxChord: 1 },
  };
  const blob = encodeOyasaiPackage({
    midi,
    conversion,
    parts: [],
    settings: { globalTranspose: 0, pitchPolicy: "fold", removeLeadingSilence: true, preservePan: true },
    title: "試験曲",
  });
  const decoded = decodeOyasaiPackage(await blob.arrayBuffer());
  assert.equal(decoded.metadata.song.title, "試験曲");
  assert.equal(decoded.notes.length, 1);
  assert.deepEqual(decoded.notes[0], {
    timeMs: 250,
    instrumentKey: "flute",
    pitch: 12,
    volume: 80,
    pan: -25,
  });
});
