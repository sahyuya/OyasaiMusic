const decoder = new TextDecoder("utf-8", { fatal: false });

class MidiReader {
  constructor(buffer) {
    this.view = new DataView(buffer);
    this.bytes = new Uint8Array(buffer);
    this.offset = 0;
  }

  ensure(length, context = "MIDIデータ") {
    if (this.offset + length > this.view.byteLength) {
      throw new Error(`${context}が途中で切れています（位置: ${this.offset}）。`);
    }
  }

  u8() {
    this.ensure(1);
    return this.view.getUint8(this.offset++);
  }

  u16() {
    this.ensure(2);
    const value = this.view.getUint16(this.offset, false);
    this.offset += 2;
    return value;
  }

  u32() {
    this.ensure(4);
    const value = this.view.getUint32(this.offset, false);
    this.offset += 4;
    return value;
  }

  ascii(length) {
    this.ensure(length);
    let value = "";
    for (let i = 0; i < length; i += 1) value += String.fromCharCode(this.bytes[this.offset + i]);
    this.offset += length;
    return value;
  }

  text(length) {
    this.ensure(length);
    const value = decoder.decode(this.bytes.subarray(this.offset, this.offset + length));
    this.offset += length;
    return value.replace(/\0/g, "").trim();
  }

  skip(length) {
    this.ensure(length);
    this.offset += length;
  }

  varInt(limit = this.view.byteLength) {
    let value = 0;
    for (let count = 0; count < 4; count += 1) {
      if (this.offset >= limit) throw new Error("可変長数値がトラック終端を越えています。");
      const byte = this.u8();
      value = value * 128 + (byte & 0x7f);
      if ((byte & 0x80) === 0) return value;
    }
    throw new Error("4バイトを超える不正なMIDI可変長数値です。");
  }
}

function makeChannelState() {
  return Array.from({ length: 16 }, () => ({
    program: 0,
    volume: 127,
    expression: 127,
    pan: 64,
  }));
}

function readDataByte(reader, firstData) {
  if (firstData !== null) return firstData;
  const value = reader.u8();
  if (value >= 0x80) throw new Error(`データバイトとして不正な値 0x${value.toString(16)} です。`);
  return value;
}

function sanitizeName(name, fallback) {
  const normalized = String(name || "").replace(/[\u0000-\u001f\u007f]/g, " ").trim();
  return normalized || fallback;
}

export function parseMidi(arrayBuffer, sourceName = "untitled.mid", onProgress = () => {}) {
  const reader = new MidiReader(arrayBuffer);
  if (reader.ascii(4) !== "MThd") throw new Error("MThdヘッダーが見つからないため、MIDIファイルとして認識できません。");
  const headerLength = reader.u32();
  if (headerLength < 6) throw new Error("MIDIヘッダーの長さが不正です。");
  const format = reader.u16();
  const declaredTrackCount = reader.u16();
  const division = reader.u16();
  if (format > 2) throw new Error(`未対応のStandard MIDI File形式です（Type ${format}）。`);
  if ((division & 0x8000) !== 0) throw new Error("SMPTE時間形式のMIDIは現在未対応です。PPQ形式で書き出し直してください。");
  if (division === 0) throw new Error("PPQが0のため、発音時刻を計算できません。");
  if (headerLength > 6) reader.skip(headerLength - 6);

  const rawNotes = [];
  const tempos = [];
  const timeSignatures = [];
  const tracks = [];
  const warnings = { sysex: 0, pitchBend: 0, unsupportedMeta: 0, orphanNoteOff: 0 };
  let sequenceOrder = 0;

  for (let trackIndex = 0; trackIndex < declaredTrackCount; trackIndex += 1) {
    reader.ensure(8, `トラック${trackIndex + 1}`);
    const chunk = reader.ascii(4);
    const length = reader.u32();
    const trackEnd = reader.offset + length;
    if (chunk !== "MTrk") throw new Error(`トラック${trackIndex + 1}にMTrkチャンクがありません。`);
    if (trackEnd > reader.view.byteLength) throw new Error(`トラック${trackIndex + 1}の宣言長がファイル末尾を越えています。`);

    const state = makeChannelState();
    const activeNotes = new Map();
    const trackNoteIndexes = [];
    const channels = new Set();
    const programs = new Set();
    let trackName = `トラック ${trackIndex + 1}`;
    let absoluteTick = 0;
    let runningStatus = null;
    let eventCount = 0;

    while (reader.offset < trackEnd) {
      absoluteTick += reader.varInt(trackEnd);
      if (reader.offset >= trackEnd) throw new Error(`トラック${trackIndex + 1}のイベントが途中で切れています。`);
      const eventByte = reader.u8();
      let status;
      let firstData = null;

      if (eventByte < 0x80) {
        if (runningStatus === null) throw new Error(`トラック${trackIndex + 1}でRunning Statusの参照先がありません。`);
        status = runningStatus;
        firstData = eventByte;
      } else {
        status = eventByte;
      }

      sequenceOrder += 1;
      eventCount += 1;

      if (status === 0xff) {
        runningStatus = null;
        const type = reader.u8();
        const metaLength = reader.varInt(trackEnd);
        const metaEnd = reader.offset + metaLength;
        if (metaEnd > trackEnd) throw new Error(`トラック${trackIndex + 1}のMetaイベントが終端を越えています。`);

        if (type === 0x03) {
          trackName = sanitizeName(reader.text(metaLength), trackName);
        } else if (type === 0x51 && metaLength === 3) {
          const tempo = (reader.u8() << 16) | (reader.u8() << 8) | reader.u8();
          if (tempo > 0) tempos.push({ tick: absoluteTick, tempo, order: sequenceOrder, trackIndex });
        } else if (type === 0x58 && metaLength >= 4) {
          const numerator = reader.u8();
          const denominatorPower = reader.u8();
          const clocks = reader.u8();
          const thirtySeconds = reader.u8();
          if (metaLength > 4) reader.skip(metaLength - 4);
          timeSignatures.push({
            tick: absoluteTick,
            numerator,
            denominator: 2 ** denominatorPower,
            clocks,
            thirtySeconds,
            order: sequenceOrder,
          });
        } else {
          if (![0x00, 0x01, 0x02, 0x04, 0x05, 0x06, 0x07, 0x20, 0x21, 0x2f, 0x54, 0x59, 0x7f].includes(type)) {
            warnings.unsupportedMeta += 1;
          }
          reader.skip(metaLength);
        }
        reader.offset = metaEnd;
        if (type === 0x2f) break;
        continue;
      }

      if (status === 0xf0 || status === 0xf7) {
        runningStatus = null;
        const sysexLength = reader.varInt(trackEnd);
        reader.skip(sysexLength);
        warnings.sysex += 1;
        continue;
      }

      if (status >= 0xf0) throw new Error(`未対応のSystemイベント 0x${status.toString(16)} です。`);
      runningStatus = status;
      const eventType = status & 0xf0;
      const channel = status & 0x0f;
      channels.add(channel);
      const data1 = readDataByte(reader, firstData);
      const needsSecond = ![0xc0, 0xd0].includes(eventType);
      const data2 = needsSecond ? readDataByte(reader, null) : null;
      const channelState = state[channel];

      if (eventType === 0x80 || (eventType === 0x90 && data2 === 0)) {
        const key = `${channel}:${data1}`;
        const queue = activeNotes.get(key);
        const noteIndex = queue?.shift();
        if (noteIndex === undefined) {
          warnings.orphanNoteOff += 1;
        } else {
          rawNotes[noteIndex].endTick = absoluteTick;
          if (queue.length === 0) activeNotes.delete(key);
        }
      } else if (eventType === 0x90) {
        const noteIndex = rawNotes.length;
        const note = {
          trackIndex,
          trackName,
          channel,
          midi: data1,
          velocity: data2,
          startTick: absoluteTick,
          endTick: null,
          program: channelState.program,
          channelVolume: channelState.volume,
          expression: channelState.expression,
          pan: channelState.pan,
          percussion: channel === 9,
          order: sequenceOrder,
        };
        rawNotes.push(note);
        trackNoteIndexes.push(noteIndex);
        programs.add(channelState.program);
        const key = `${channel}:${data1}`;
        const queue = activeNotes.get(key) || [];
        queue.push(noteIndex);
        activeNotes.set(key, queue);
      } else if (eventType === 0xb0) {
        if (data1 === 7) channelState.volume = data2;
        else if (data1 === 10) channelState.pan = data2;
        else if (data1 === 11) channelState.expression = data2;
        else if (data1 === 121) {
          channelState.volume = 127;
          channelState.expression = 127;
          channelState.pan = 64;
        }
      } else if (eventType === 0xc0) {
        channelState.program = data1;
        programs.add(data1);
      } else if (eventType === 0xe0) {
        warnings.pitchBend += 1;
      }
    }

    reader.offset = trackEnd;
    for (const noteIndex of trackNoteIndexes) rawNotes[noteIndex].trackName = trackName;
    tracks.push({
      index: trackIndex,
      name: trackName,
      eventCount,
      noteCount: trackNoteIndexes.length,
      channels: [...channels].sort((a, b) => a - b),
      programs: [...programs].sort((a, b) => a - b),
    });
    onProgress({ track: trackIndex + 1, totalTracks: declaredTrackCount, notes: rawNotes.length });
  }

  if (format === 2) {
    throw new Error("Type 2 MIDIは独立した複数シーケンスを含むため、現在は変換できません。Type 0またはType 1で書き出してください。");
  }

  const normalizedTempos = normalizeTempoMap(tempos, division);
  const tickToUs = createTickConverter(normalizedTempos, division);
  const notes = rawNotes
    .map((note) => {
      const startUs = tickToUs(note.startTick);
      const endUs = note.endTick === null ? null : tickToUs(note.endTick);
      return {
        ...note,
        startMs: startUs / 1000,
        durationMs: endUs === null ? null : Math.max(0, (endUs - startUs) / 1000),
      };
    })
    .sort((a, b) => a.startMs - b.startMs || a.order - b.order)
    .map((note, id) => ({ ...note, id }));

  const durationMs = notes.reduce(
    (maximum, note) => Math.max(maximum, note.startMs + (note.durationMs || 0)),
    0,
  );
  const sourceBase = sourceName.replace(/\.(midi?|smf)$/i, "");
  const namedTrack = tracks.find((track) => !/^トラック \d+$/.test(track.name));
  const title = sanitizeName(namedTrack?.name, sanitizeName(sourceBase, "無題の楽曲"));

  return {
    format,
    ppq: division,
    title,
    sourceName,
    durationMs,
    notes,
    tracks,
    tempos: normalizedTempos.map((tempo) => ({
      tick: tempo.tick,
      timeMs: tempo.usAtTick / 1000,
      tempo: tempo.tempo,
      bpm: 60_000_000 / tempo.tempo,
    })),
    timeSignatures: timeSignatures
      .sort((a, b) => a.tick - b.tick || a.order - b.order)
      .map((signature) => ({ ...signature, timeMs: tickToUs(signature.tick) / 1000 })),
    warnings,
  };
}

function normalizeTempoMap(events, ppq) {
  const sorted = [...events].sort((a, b) => a.tick - b.tick || a.order - b.order);
  const deduplicated = [];
  for (const event of sorted) {
    const last = deduplicated.at(-1);
    if (last && last.tick === event.tick) deduplicated[deduplicated.length - 1] = event;
    else deduplicated.push(event);
  }
  if (deduplicated.length === 0 || deduplicated[0].tick !== 0) {
    deduplicated.unshift({ tick: 0, tempo: 500_000, order: -1, trackIndex: -1 });
  }

  let previousTick = deduplicated[0].tick;
  let previousTempo = deduplicated[0].tempo;
  let accumulatedUs = 0;
  return deduplicated.map((event, index) => {
    if (index > 0) {
      accumulatedUs += ((event.tick - previousTick) * previousTempo) / ppq;
      previousTick = event.tick;
      previousTempo = event.tempo;
    }
    return { ...event, usAtTick: accumulatedUs };
  });
}

function createTickConverter(tempoMap, ppq) {
  return (tick) => {
    let low = 0;
    let high = tempoMap.length - 1;
    while (low < high) {
      const middle = Math.ceil((low + high) / 2);
      if (tempoMap[middle].tick <= tick) low = middle;
      else high = middle - 1;
    }
    const segment = tempoMap[low];
    return segment.usAtTick + ((tick - segment.tick) * segment.tempo) / ppq;
  };
}
