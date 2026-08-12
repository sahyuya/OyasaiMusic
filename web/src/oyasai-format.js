import { INSTRUMENT_BY_ID, INSTRUMENT_BY_KEY } from "./instruments.js";

const MAGIC = [0x4f, 0x59, 0x4d, 0x49]; // OYMI
const VERSION = 1;
const HEADER_SIZE = 20;
const NOTE_SIZE = 8;
const encoder = new TextEncoder();
const decoder = new TextDecoder();

export function encodeOyasaiPackage({ midi, conversion, parts, settings, title }) {
  const notes = conversion.notes;
  const metadata = {
    format: "oyasai-midi-import",
    version: VERSION,
    createdBy: "OyasaiMusicMidiTranslator",
    createdByVersion: "0.2.0",
    song: {
      title: String(title || midi.title || "無題の楽曲").trim().slice(0, 120) || "無題の楽曲",
      displayBpm: Math.max(1, Math.min(60_000, Math.round(midi.tempos[0]?.bpm || 120))),
      durationMs: conversion.metrics.durationMs,
      tempoMapPreserved: true,
      tempoMap: (midi.tempos || []).map((tempo) => ({
        timeMs: Math.max(0, Math.round(tempo.timeMs || 0)),
        tick: Math.max(0, Math.round(tempo.tick || 0)),
        bpm: Math.max(1, Math.min(60_000, Math.round(tempo.bpm || 120))),
      })),
    },
    source: {
      fileName: midi.sourceName,
      midiFormat: midi.format,
      ppq: midi.ppq,
      trackCount: midi.tracks.length,
      files: midi.sources || [{ fileName: midi.sourceName, title: midi.title }],
    },
    conversion: {
      globalTranspose: settings.globalTranspose,
      pitchPolicy: settings.pitchPolicy,
      removeLeadingSilence: settings.removeLeadingSilence,
      preservePan: settings.preservePan,
      mappingRevision: "ommt-v1",
    },
    parts: parts.map((part) => ({
      id: part.id,
      name: part.name,
      instrument: part.instrumentKey,
      transpose: part.transpose,
      volume: part.volume,
      muted: part.muted,
    })),
    metrics: conversion.metrics,
  };
  const metadataBytes = encoder.encode(JSON.stringify(metadata));
  const buffer = new ArrayBuffer(HEADER_SIZE + metadataBytes.length + notes.length * NOTE_SIZE);
  const view = new DataView(buffer);
  const bytes = new Uint8Array(buffer);
  bytes.set(MAGIC, 0);
  view.setUint16(4, VERSION, false);
  view.setUint16(6, 0, false);
  view.setUint32(8, metadataBytes.length, false);
  view.setUint32(12, notes.length, false);
  view.setUint32(16, Math.max(0, Math.min(0xffffffff, conversion.metrics.durationMs)), false);
  bytes.set(metadataBytes, HEADER_SIZE);

  let offset = HEADER_SIZE + metadataBytes.length;
  for (const note of notes) {
    const instrument = INSTRUMENT_BY_KEY.get(note.instrumentKey);
    if (!instrument) throw new Error(`未対応の楽器キーです: ${note.instrumentKey}`);
    view.setUint32(offset, Math.max(0, Math.min(0xffffffff, note.timeMs)), false);
    view.setUint8(offset + 4, instrument.id);
    view.setUint8(offset + 5, note.pitch);
    view.setUint8(offset + 6, note.volume);
    view.setInt8(offset + 7, note.pan);
    offset += NOTE_SIZE;
  }
  return new Blob([buffer], { type: "application/octet-stream" });
}

export function decodeOyasaiPackage(arrayBuffer) {
  if (arrayBuffer.byteLength < HEADER_SIZE) throw new Error("ヘッダーが不足しています。");
  const view = new DataView(arrayBuffer);
  const bytes = new Uint8Array(arrayBuffer);
  if (!MAGIC.every((value, index) => bytes[index] === value)) throw new Error("OYMIマジックが一致しません。");
  const version = view.getUint16(4, false);
  if (version !== VERSION) throw new Error(`未対応バージョンです: ${version}`);
  const metadataLength = view.getUint32(8, false);
  const noteCount = view.getUint32(12, false);
  const durationMs = view.getUint32(16, false);
  const expected = HEADER_SIZE + metadataLength + noteCount * NOTE_SIZE;
  if (expected !== arrayBuffer.byteLength) throw new Error("パッケージ長が一致しません。");
  const metadata = JSON.parse(decoder.decode(bytes.subarray(HEADER_SIZE, HEADER_SIZE + metadataLength)));
  const notes = [];
  let offset = HEADER_SIZE + metadataLength;
  for (let index = 0; index < noteCount; index += 1) {
    const instrumentId = view.getUint8(offset + 4);
    const instrument = INSTRUMENT_BY_ID.get(instrumentId);
    if (!instrument) throw new Error(`未対応の楽器IDです: ${instrumentId}`);
    notes.push({
      timeMs: view.getUint32(offset, false),
      instrumentKey: instrument.key,
      pitch: view.getUint8(offset + 5),
      volume: view.getUint8(offset + 6),
      pan: view.getInt8(offset + 7),
    });
    offset += NOTE_SIZE;
  }
  return { version, durationMs, metadata, notes };
}

export function downloadBlob(blob, fileName) {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = sanitizeFileName(fileName);
  document.body.append(anchor);
  anchor.click();
  anchor.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

export function sanitizeFileName(name) {
  return String(name || "ommt-export.oyasai")
    .replace(/[<>:"/\\|?*\u0000-\u001f]/g, "_")
    .replace(/\.+$/g, "")
    .slice(0, 160) || "ommt-export.oyasai";
}
