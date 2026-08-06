import assert from "node:assert/strict";
import test from "node:test";
import { gunzipSync } from "node:zlib";
import {
  buildSpongeSchematicNbt,
  encodeSpongeSchematic,
  planGridSchematic,
} from "../src/schematic.js";

const notes = [
  note(0, "piano", 12, 80, -50),
  note(125, "bell", 18, 64, 25),
  note(375, "flute", 7, 100, 0),
  note(0, "bass_guitar", 4, 90, 0),
];

test("最小発音間隔に合わせてBPMとグリッド全体を拡大する", () => {
  const plan = planGridSchematic(notes, 120);
  assert.equal(plan.baseBpm, 120);
  assert.equal(plan.targetBpm, 480);
  assert.equal(plan.bpm, 480);
  assert.equal(plan.bpmRaised, true);
  assert.equal(plan.bpmReducedForSize, false);
  assert.equal(plan.minimumIntervalMs, 125);
  assert.equal(plan.width, 4);
  assert.equal(plan.height, 1);
  assert.equal(plan.length, 2);
  assert.equal(plan.noteCount, 4);
  assert.equal(plan.blockCount, 4);
  assert.deepEqual(plan.placements.map(({ x, y, z }) => [x, y, z]), [
    [0, 0, 0],
    [1, 0, 0],
    [3, 0, 0],
    [0, 0, 1],
  ]);
  assert.equal(plan.collapsedOnsetCount, 0);
  assert.equal(plan.maxTimingErrorMs, 0);

  const irregular = planGridSchematic([
    note(0, "piano", 12, 100, 0),
    note(166, "piano", 12, 100, 0),
    note(333, "piano", 12, 100, 0),
  ], 120);
  assert.equal(irregular.minimumIntervalMs, 166);
  assert.equal(irregular.bpm, 362);
  assert.deepEqual(irregular.placements.map(({ x }) => x), [0, 1, 2]);
  assert.equal(irregular.maxTimingErrorMs, 2);
});

test("長い曲はSchematicの横幅上限に収まるBPMへ調整する", () => {
  const plan = planGridSchematic([
    note(0, "piano", 12, 100, 0),
    note(1, "piano", 12, 100, 0),
    note(120_000, "piano", 12, 100, 0),
  ], 120);
  assert.equal(plan.targetBpm, 60_000);
  assert.equal(plan.bpmReducedForSize, true);
  assert.ok(plan.bpm < plan.targetBpm);
  assert.ok(plan.width <= 0xffff);
});

test("Sponge schematic v3には音ブロックだけを書き込み看板NBTを含めない", () => {
  const createdAt = 1_700_000_000_000;
  const { bytes, plan, palette } = buildSpongeSchematicNbt({ notes, baseBpm: 120, title: "Test Grid", createdAt });
  const root = parseNbt(bytes).value.Schematic;
  assert.equal(root.Version, 3);
  assert.equal(root.DataVersion, 4671);
  assert.equal(root.Width, plan.width);
  assert.equal(root.Height, 1);
  assert.equal(root.Length, plan.length);
  assert.equal(root.Metadata.OMMT.GridBPM, 480);
  assert.equal(root.Metadata.OMMT.BaseBPM, 120);
  assert.equal(root.Metadata.OMMT.MinimumIntervalMs, 125);
  assert.equal(root.Metadata.OMMT.TimingMode, "minimum-interval-grid");
  assert.equal(root.Metadata.OMMT.DiscardedControls, "volume,pan");
  assert.equal(root.Blocks.Data.length, plan.cellCount);
  assert.equal(root.Blocks.BlockEntities, undefined);
  assert.ok([...palette.keys()].every((key) => key === "minecraft:air" || key.startsWith("minecraft:note_block[")));
  assert.ok([...palette.keys()].every((key) => !key.includes("sign")));
  assert.ok([...palette.keys()].some((key) => key.includes("minecraft:note_block[instrument=bell,note=18")));

  const changedControls = notes.map((source) => ({ ...source, volume: source.volume === 100 ? 0 : 100, pan: source.pan === 100 ? -100 : 100 }));
  const withoutControls = buildSpongeSchematicNbt({ notes: changedControls, baseBpm: 120, title: "Test Grid", createdAt });
  assert.deepEqual(withoutControls.bytes, bytes);
});

test(".schemをgzip圧縮して復元できる", async () => {
  const createdAt = 1_700_000_000_000;
  const raw = buildSpongeSchematicNbt({ notes, baseBpm: 120, title: "Compressed", createdAt });
  const encoded = await encodeSpongeSchematic({ notes, baseBpm: 120, title: "Compressed", createdAt });
  const compressed = new Uint8Array(await encoded.blob.arrayBuffer());
  assert.deepEqual([...compressed.slice(0, 2)], [0x1f, 0x8b]);
  assert.ok(gunzipSync(compressed).equals(Buffer.from(raw.bytes)));
});

function note(timeMs, instrumentKey, pitch, volume, pan) {
  return { timeMs, instrumentKey, pitch, volume, pan };
}

function parseNbt(bytes) {
  const reader = new NbtReader(bytes);
  const type = reader.u8();
  const name = reader.string();
  return { name, value: reader.payload(type) };
}

class NbtReader {
  constructor(bytes) {
    this.bytes = bytes;
    this.view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    this.offset = 0;
    this.decoder = new TextDecoder();
  }

  u8() {
    return this.bytes[this.offset++];
  }

  i16() {
    const value = this.view.getInt16(this.offset, false);
    this.offset += 2;
    return value;
  }

  i32() {
    const value = this.view.getInt32(this.offset, false);
    this.offset += 4;
    return value;
  }

  i64() {
    const value = this.view.getBigInt64(this.offset, false);
    this.offset += 8;
    return value;
  }

  string() {
    const length = this.view.getUint16(this.offset, false);
    this.offset += 2;
    const value = this.decoder.decode(this.bytes.subarray(this.offset, this.offset + length));
    this.offset += length;
    return value;
  }

  payload(type) {
    if (type === 1) return this.u8();
    if (type === 2) return this.i16();
    if (type === 3) return this.i32();
    if (type === 4) return this.i64();
    if (type === 7) {
      const length = this.i32();
      const value = this.bytes.slice(this.offset, this.offset + length);
      this.offset += length;
      return value;
    }
    if (type === 8) return this.string();
    if (type === 9) {
      const elementType = this.u8();
      const length = this.i32();
      return Array.from({ length }, () => this.payload(elementType));
    }
    if (type === 10) {
      const value = {};
      while (true) {
        const childType = this.u8();
        if (childType === 0) return value;
        const name = this.string();
        value[name] = this.payload(childType);
      }
    }
    if (type === 11) {
      const length = this.i32();
      return Array.from({ length }, () => this.i32());
    }
    throw new Error(`unsupported NBT type ${type}`);
  }
}
