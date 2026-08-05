import test from "node:test";
import assert from "node:assert/strict";
import { buildRulerGrid, buildTimeGrid, isMinecraftBoundaryPitch, nearestGridTime } from "../src/grid.js";

test("120 BPMの16分音符グリッドを125ms間隔で作る", () => {
  const grid = buildTimeGrid({
    tempos: [{ tick: 0, timeMs: 0, tempo: 500_000, bpm: 120 }],
    ppq: 480,
    startMs: 0,
    endMs: 500,
    subdivision: 4,
  });
  assert.deepEqual(grid, [0, 125, 250, 375, 500]);
  assert.equal(nearestGridTime(238, grid), 250);
});

test("テンポ変更後のtickグリッド間隔を反映する", () => {
  const grid = buildTimeGrid({
    tempos: [
      { tick: 0, timeMs: 0, tempo: 500_000, bpm: 120 },
      { tick: 480, timeMs: 500, tempo: 1_000_000, bpm: 60 },
    ],
    ppq: 480,
    startMs: 0,
    endMs: 1500,
    subdivision: 1,
  });
  assert.deepEqual(grid, [0, 500, 1500]);
});

test("Minecraft音ブロックの音域境界をF♯として判定する", () => {
  assert.equal(isMinecraftBoundaryPitch(54), true);
  assert.equal(isMinecraftBoundaryPitch(66), true);
  assert.equal(isMinecraftBoundaryPitch(60), false);
});

test("4/4拍子のルーラーへ小節番号と拍を付ける", () => {
  const marks = buildRulerGrid({
    tempos: [{ tick: 0, timeMs: 0, tempo: 500_000, bpm: 120 }],
    timeSignatures: [{ tick: 0, numerator: 4, denominator: 4 }],
    ppq: 480,
    startMs: 0,
    endMs: 2500,
    subdivision: 1,
  });
  assert.deepEqual(marks.slice(0, 5).map(({ timeMs, bar, beat, isBar }) => ({ timeMs, bar, beat, isBar })), [
    { timeMs: 0, bar: 1, beat: 1, isBar: true },
    { timeMs: 500, bar: 1, beat: 2, isBar: false },
    { timeMs: 1000, bar: 1, beat: 3, isBar: false },
    { timeMs: 1500, bar: 1, beat: 4, isBar: false },
    { timeMs: 2000, bar: 2, beat: 1, isBar: true },
  ]);
});
