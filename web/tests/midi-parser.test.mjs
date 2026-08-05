import assert from "node:assert/strict";
import test from "node:test";
import { parseMidi } from "../src/midi-parser.js";

test("Type 0 MIDIのテンポ、ノート、Program Changeを解析する", () => {
  const track = [
    ...event(0, [0xff, 0x03, 0x04, ...ascii("Test")]),
    ...event(0, [0xff, 0x51, 0x03, 0x07, 0xa1, 0x20]),
    ...event(0, [0xc0, 24]),
    ...event(0, [0x90, 60, 100]),
    ...event(480, [0x80, 60, 0]),
    ...event(0, [0xff, 0x2f, 0]),
  ];
  const midi = parseMidi(makeMidi(track), "fixture.mid");
  assert.equal(midi.format, 0);
  assert.equal(midi.ppq, 480);
  assert.equal(midi.title, "Test");
  assert.equal(midi.notes.length, 1);
  assert.equal(midi.notes[0].midi, 60);
  assert.equal(midi.notes[0].program, 24);
  assert.equal(Math.round(midi.notes[0].durationMs), 500);
  assert.equal(Math.round(midi.tempos[0].bpm), 120);
});

test("テンポ変更を区間ごとに積算する", () => {
  const track = [
    ...event(0, [0xff, 0x51, 0x03, 0x07, 0xa1, 0x20]),
    ...event(480, [0xff, 0x51, 0x03, 0x0f, 0x42, 0x40]),
    ...event(480, [0x90, 66, 90]),
    ...event(0, [0xff, 0x2f, 0]),
  ];
  const midi = parseMidi(makeMidi(track), "tempo.mid");
  assert.equal(midi.notes.length, 1);
  assert.equal(Math.round(midi.notes[0].startMs), 1500);
});

function makeMidi(track) {
  const bytes = [
    ...ascii("MThd"), ...u32(6), 0, 0, 0, 1, 0x01, 0xe0,
    ...ascii("MTrk"), ...u32(track.length), ...track,
  ];
  return Uint8Array.from(bytes).buffer;
}

function event(delta, bytes) {
  return [...varInt(delta), ...bytes];
}

function ascii(value) {
  return [...value].map((character) => character.charCodeAt(0));
}

function u32(value) {
  return [(value >>> 24) & 0xff, (value >>> 16) & 0xff, (value >>> 8) & 0xff, value & 0xff];
}

function varInt(value) {
  let buffer = value & 0x7f;
  const result = [];
  while ((value >>= 7) > 0) {
    buffer <<= 8;
    buffer |= (value & 0x7f) | 0x80;
  }
  while (true) {
    result.push(buffer & 0xff);
    if (buffer & 0x80) buffer >>= 8;
    else break;
  }
  return result;
}
