import test from "node:test";
import assert from "node:assert/strict";
import { buildTimeGrid, isMinecraftBoundaryPitch, nearestGridTime } from "../src/grid.js";

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
