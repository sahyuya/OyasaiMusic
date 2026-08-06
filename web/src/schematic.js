const SCHEMATIC_VERSION = 3;
const MINECRAFT_DATA_VERSION = 4671; // Minecraft Java 1.21.11
const MAX_DIMENSION = 0xffff;
const MAX_NBT_ARRAY_LENGTH = 0x7fffffff;
const MAX_GRID_BPM = 60_000;

const NBT = Object.freeze({
  END: 0,
  BYTE: 1,
  SHORT: 2,
  INT: 3,
  LONG: 4,
  BYTE_ARRAY: 7,
  STRING: 8,
  LIST: 9,
  COMPOUND: 10,
  INT_ARRAY: 11,
});

const NOTE_BLOCK_INSTRUMENT = Object.freeze({
  piano: "harp",
  bass_guitar: "bass",
  bass_drum: "basedrum",
  snare_drum: "snare",
  sticks: "hat",
  flute: "flute",
  bell: "bell",
  guitar: "guitar",
  chime: "chime",
  xylophone: "xylophone",
  iron_xylophone: "iron_xylophone",
  cow_bell: "cow_bell",
  didgeridoo: "didgeridoo",
  bit: "bit",
  banjo: "banjo",
  pling: "pling",
});

/** 看板を使わず、最小の発音間隔を1列以上へ広げるグリッド配置を計算する。 */
export function planGridSchematic(notes, baseBpm = 120) {
  if (!Array.isArray(notes) || notes.length === 0) throw new Error("書き出せるノートがありません。");
  const normalizedBaseBpm = Math.max(1, Math.min(MAX_GRID_BPM, Math.round(Number(baseBpm) || 120)));
  const onsetTimes = [...new Set(notes.map((note) => normalizedTime(note.timeMs)))].sort((a, b) => a - b);
  let maximumTime = 0;
  let minimumIntervalMs = null;
  for (let index = 0; index < onsetTimes.length; index += 1) {
    maximumTime = Math.max(maximumTime, onsetTimes[index]);
    if (index > 0) {
      const interval = onsetTimes[index] - onsetTimes[index - 1];
      if (interval > 0) minimumIntervalMs = Math.min(minimumIntervalMs ?? interval, interval);
    }
  }

  const intervalBpm = minimumIntervalMs == null
    ? normalizedBaseBpm
    : Math.min(MAX_GRID_BPM, Math.max(1, Math.ceil(60_000 / minimumIntervalMs)));
  const targetBpm = Math.max(normalizedBaseBpm, intervalBpm);
  let bpm = targetBpm;
  if (maximumTime > 0) {
    const maximumBpmForWidth = Math.max(1, Math.floor(((MAX_DIMENSION - 0.5) * 60_000) / maximumTime));
    bpm = Math.min(bpm, maximumBpmForWidth);
    while (Math.round((maximumTime * bpm) / 60_000) >= MAX_DIMENSION && bpm > 1) bpm -= 1;
  }

  const laneAtX = new Map();
  let maximumX = 0;
  const placements = notes.map((note) => {
    const sourceTimeMs = normalizedTime(note.timeMs);
    const x = timeColumn(sourceTimeMs, bpm);
    maximumX = Math.max(maximumX, x);
    const z = laneAtX.get(x) || 0;
    laneAtX.set(x, z + 1);
    return {
      note,
      x,
      y: 0,
      z,
      timingErrorMs: Math.abs(recordedTimeMs(x, bpm) - sourceTimeMs),
    };
  });
  const width = maximumX + 1;
  const height = 1;
  let length = 1;
  for (const laneCount of laneAtX.values()) length = Math.max(length, laneCount);
  if (width > MAX_DIMENSION || height > MAX_DIMENSION || length > MAX_DIMENSION) {
    throw new Error(`Sponge schematicの寸法上限を超えています: ${width}×${height}×${length}`);
  }
  const cellCount = width * height * length;
  if (!Number.isSafeInteger(cellCount) || cellCount > MAX_NBT_ARRAY_LENGTH) {
    throw new Error("Sponge schematicのBlockData配列上限を超えています。");
  }
  const usedOnsetColumns = new Set();
  let collapsedOnsetCount = 0;
  for (const onsetTime of onsetTimes) {
    const column = timeColumn(onsetTime, bpm);
    if (usedOnsetColumns.has(column)) collapsedOnsetCount += 1;
    else usedOnsetColumns.add(column);
  }
  return {
    bpm,
    baseBpm: normalizedBaseBpm,
    targetBpm,
    bpmRaised: targetBpm > normalizedBaseBpm,
    bpmReducedForSize: bpm < targetBpm,
    minimumIntervalMs,
    collapsedOnsetCount,
    width,
    height,
    length,
    cellCount,
    noteCount: placements.length,
    blockCount: placements.length,
    estimatedBytes: cellCount + placements.length * 24,
    maxTimingErrorMs: placements.reduce((maximum, placement) => Math.max(maximum, placement.timingErrorMs), 0),
    placements,
  };
}

/** Sponge schematic v3の非圧縮NBTを作る。テストとgzip前処理で共用する。 */
export function buildSpongeSchematicNbt({ notes, baseBpm, bpm, title = "OMMT Grid", createdAt = Date.now() }) {
  const plan = planGridSchematic(notes, baseBpm ?? bpm ?? 120);
  const palette = new Map([["minecraft:air", 0]]);
  for (const placement of plan.placements) {
    const state = noteBlockState(placement.note);
    if (!palette.has(state)) palette.set(state, palette.size);
  }

  const blocks = new Map();
  for (const placement of plan.placements) {
    const noteIndex = blockIndex(placement.x, placement.y, placement.z, plan.width, plan.length);
    blocks.set(noteIndex, palette.get(noteBlockState(placement.note)));
  }

  const blockDataWriter = new ByteWriter(Math.min(Math.max(1024, plan.cellCount), 16 * 1024 * 1024));
  for (let index = 0; index < plan.cellCount; index += 1) writeVarInt(blockDataWriter, blocks.get(index) || 0);
  const paletteEntries = [...palette.entries()].map(([state, id]) => [state, NBT.INT, id]);
  const schematic = [
    ["Version", NBT.INT, SCHEMATIC_VERSION],
    ["DataVersion", NBT.INT, MINECRAFT_DATA_VERSION],
    ["Metadata", NBT.COMPOUND, [
      ["Name", NBT.STRING, String(title || "OMMT Grid").slice(0, 120)],
      ["Author", NBT.STRING, "OyasaiMusicMidiTranslator"],
      ["Date", NBT.LONG, BigInt(createdAt)],
      ["RequiredMods", NBT.LIST, { elementType: NBT.STRING, items: [] }],
      ["OMMT", NBT.COMPOUND, [
        ["GridBPM", NBT.INT, plan.bpm],
        ["BaseBPM", NBT.INT, plan.baseBpm],
        ["MinimumIntervalMs", NBT.INT, plan.minimumIntervalMs ?? 0],
        ["TimeAxis", NBT.STRING, "EAST_X_POSITIVE"],
        ["NoteCount", NBT.INT, plan.noteCount],
        ["TimingMode", NBT.STRING, "minimum-interval-grid"],
        ["DiscardedControls", NBT.STRING, "volume,pan"],
      ]],
    ]],
    ["Width", NBT.SHORT, plan.width],
    ["Height", NBT.SHORT, plan.height],
    ["Length", NBT.SHORT, plan.length],
    ["Offset", NBT.INT_ARRAY, [0, 0, 0]],
    ["Blocks", NBT.COMPOUND, [
      ["Palette", NBT.COMPOUND, paletteEntries],
      ["Data", NBT.BYTE_ARRAY, blockDataWriter.toUint8Array()],
    ]],
  ];

  const writer = new ByteWriter(Math.min(Math.max(4096, plan.cellCount), 16 * 1024 * 1024));
  writer.u8(NBT.COMPOUND);
  writer.string("");
  writeCompoundPayload(writer, [["Schematic", NBT.COMPOUND, schematic]]);
  return { bytes: writer.toUint8Array(), plan, palette };
}

/** ブラウザ内でgzip圧縮し、そのままダウンロード可能な.schem Blobを返す。 */
export async function encodeSpongeSchematic(options) {
  const { bytes, plan } = buildSpongeSchematicNbt(options);
  if (typeof CompressionStream !== "function") {
    throw new Error("このブラウザは.schemのgzip圧縮に対応していません。Chrome・Edge・Firefoxの最新版を使用してください。");
  }
  const compressed = new Blob([bytes]).stream().pipeThrough(new CompressionStream("gzip"));
  const buffer = await new Response(compressed).arrayBuffer();
  return {
    blob: new Blob([buffer], { type: "application/gzip" }),
    plan,
    uncompressedBytes: bytes.byteLength,
    compressedBytes: buffer.byteLength,
  };
}

function noteBlockState(note) {
  const instrument = NOTE_BLOCK_INSTRUMENT[note.instrumentKey] || "harp";
  const pitch = Math.max(0, Math.min(24, Math.round(Number(note.pitch) || 0)));
  return `minecraft:note_block[instrument=${instrument},note=${pitch},powered=false]`;
}

function normalizedTime(value) {
  return Math.max(0, Math.round(Number(value) || 0));
}

function timeColumn(timeMs, bpm) {
  return Math.max(0, Math.round((timeMs * bpm) / 60_000));
}

/** GridRecorderの`(timeIndex * stepMs).toInt()`と同じ基準時刻。 */
function recordedTimeMs(column, bpm) {
  return Math.trunc((column * 60_000) / bpm);
}

function blockIndex(x, y, z, width, length) {
  return x + z * width + y * width * length;
}

function writeVarInt(writer, value) {
  let remaining = value >>> 0;
  do {
    let byte = remaining & 0x7f;
    remaining >>>= 7;
    if (remaining !== 0) byte |= 0x80;
    writer.u8(byte);
  } while (remaining !== 0);
}

function writeCompoundPayload(writer, entries) {
  for (const [name, type, value] of entries) {
    writer.u8(type);
    writer.string(name);
    writePayload(writer, type, value);
  }
  writer.u8(NBT.END);
}

function writePayload(writer, type, value) {
  if (type === NBT.BYTE) writer.u8(Number(value) & 0xff);
  else if (type === NBT.SHORT) writer.i16(Number(value) > 0x7fff ? Number(value) - 0x10000 : Number(value));
  else if (type === NBT.INT) writer.i32(Number(value));
  else if (type === NBT.LONG) writer.i64(BigInt(value));
  else if (type === NBT.STRING) writer.string(String(value));
  else if (type === NBT.BYTE_ARRAY) {
    writer.i32(value.length);
    writer.bytes(value);
  } else if (type === NBT.INT_ARRAY) {
    writer.i32(value.length);
    for (const number of value) writer.i32(number);
  } else if (type === NBT.LIST) {
    writer.u8(value.elementType);
    writer.i32(value.items.length);
    for (const item of value.items) writePayload(writer, value.elementType, item);
  } else if (type === NBT.COMPOUND) writeCompoundPayload(writer, value);
  else throw new Error(`未対応のNBT型です: ${type}`);
}

class ByteWriter {
  constructor(initialCapacity = 1024) {
    this.buffer = new Uint8Array(Math.max(16, initialCapacity));
    this.length = 0;
  }

  ensure(size) {
    if (this.length + size <= this.buffer.length) return;
    let capacity = this.buffer.length;
    while (capacity < this.length + size) capacity = Math.max(capacity * 2, this.length + size);
    const next = new Uint8Array(capacity);
    next.set(this.buffer);
    this.buffer = next;
  }

  u8(value) {
    this.ensure(1);
    this.buffer[this.length] = value;
    this.length += 1;
  }

  i16(value) {
    this.ensure(2);
    new DataView(this.buffer.buffer).setInt16(this.length, value, false);
    this.length += 2;
  }

  i32(value) {
    this.ensure(4);
    new DataView(this.buffer.buffer).setInt32(this.length, value, false);
    this.length += 4;
  }

  i64(value) {
    this.ensure(8);
    new DataView(this.buffer.buffer).setBigInt64(this.length, value, false);
    this.length += 8;
  }

  string(value) {
    const bytes = encodeModifiedUtf8(String(value));
    if (bytes.length > 0xffff) throw new Error("NBT文字列が65,535バイトを超えています。");
    this.ensure(2 + bytes.length);
    new DataView(this.buffer.buffer).setUint16(this.length, bytes.length, false);
    this.length += 2;
    this.buffer.set(bytes, this.length);
    this.length += bytes.length;
  }

  bytes(value) {
    this.ensure(value.length);
    this.buffer.set(value, this.length);
    this.length += value.length;
  }

  toUint8Array() {
    return this.buffer.slice(0, this.length);
  }
}

/** Java NBTのDataOutput.writeUTFと同じModified UTF-8（UTF-16コード単位）で符号化する。 */
function encodeModifiedUtf8(value) {
  const bytes = [];
  for (let index = 0; index < value.length; index += 1) {
    const code = value.charCodeAt(index);
    if (code >= 0x0001 && code <= 0x007f) {
      bytes.push(code);
    } else if (code <= 0x07ff) {
      bytes.push(0xc0 | ((code >> 6) & 0x1f), 0x80 | (code & 0x3f));
    } else {
      bytes.push(
        0xe0 | ((code >> 12) & 0x0f),
        0x80 | ((code >> 6) & 0x3f),
        0x80 | (code & 0x3f),
      );
    }
  }
  return Uint8Array.from(bytes);
}
