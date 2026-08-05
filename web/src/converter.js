import {
  PART_COLORS,
  defaultInstrumentForProgram,
  drumMapping,
  gmFamilyName,
} from "./instruments.js";

export const DEFAULT_SETTINGS = Object.freeze({
  globalTranspose: 0,
  pitchPolicy: "fold",
  removeLeadingSilence: true,
  deduplicate: true,
  preservePan: true,
});

export function createInitialParts(midi) {
  const grouped = new Map();
  for (const note of midi.notes) {
    const groupKey = `${note.trackIndex}:${note.channel}:${note.percussion ? "drums" : note.program}`;
    if (!grouped.has(groupKey)) {
      const suffix = note.percussion
        ? "ドラム"
        : `${gmFamilyName(note.program)} / Ch.${note.channel + 1}`;
      grouped.set(groupKey, {
        sourceKey: groupKey,
        name: `${note.trackName} · ${suffix}`,
        instrumentKey: note.percussion ? "sticks" : defaultInstrumentForProgram(note.program),
        transpose: 0,
        volume: 100,
        muted: false,
        percussion: note.percussion,
        noteIds: [],
      });
    }
    grouped.get(groupKey).noteIds.push(note.id);
  }

  const parts = [...grouped.values()].map((group, index) => ({
    id: `part-${index + 1}`,
    name: group.name,
    instrumentKey: group.instrumentKey,
    transpose: group.transpose,
    volume: group.volume,
    muted: group.muted,
    percussion: group.percussion,
    color: PART_COLORS[index % PART_COLORS.length],
  }));
  const assignments = new Array(midi.notes.length);
  [...grouped.values()].forEach((group, index) => {
    for (const noteId of group.noteIds) assignments[noteId] = parts[index].id;
  });
  return { parts, assignments };
}

export function splitSelectionIntoPart({
  parts,
  assignments,
  selectedIds,
  name,
  instrumentKey,
}) {
  if (selectedIds.size === 0) return { parts, assignments, createdPart: null };
  const createdPart = {
    id: `part-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 7)}`,
    name: String(name || "新しいパート").trim().slice(0, 80) || "新しいパート",
    instrumentKey: instrumentKey || "piano",
    transpose: 0,
    volume: 100,
    muted: false,
    percussion: false,
    color: PART_COLORS[parts.length % PART_COLORS.length],
  };
  const nextAssignments = assignments.slice();
  for (const noteId of selectedIds) nextAssignments[noteId] = createdPart.id;
  return { parts: [...parts, createdPart], assignments: nextAssignments, createdPart };
}

export function moveSelectionToPart(assignments, selectedIds, partId) {
  const next = assignments.slice();
  for (const noteId of selectedIds) next[noteId] = partId;
  return next;
}

export function deleteEmptyParts(parts, assignments) {
  const used = new Set(assignments);
  return parts.filter((part) => used.has(part.id));
}

export function convertMidi(midi, parts, assignments, userSettings = {}, automation = {}) {
  const settings = { ...DEFAULT_SETTINGS, ...userSettings };
  const partById = new Map(parts.map((part) => [part.id, part]));
  const automationByPart = normalizeAutomation(automation);
  const converted = [];
  const dropped = { outOfRange: 0, muted: 0, invalidPart: 0, duplicate: 0 };
  let folded = 0;
  let clamped = 0;
  const firstAudibleMs = settings.removeLeadingSilence
    ? midi.notes.reduce((minimum, note) => {
        const part = partById.get(assignments[note.id]);
        return part && !part.muted ? Math.min(minimum, note.startMs) : minimum;
      }, Number.POSITIVE_INFINITY)
    : 0;
  const offsetMs = Number.isFinite(firstAudibleMs) ? firstAudibleMs : 0;

  for (const source of midi.notes) {
    const part = partById.get(assignments[source.id]);
    if (!part) {
      dropped.invalidPart += 1;
      continue;
    }
    if (part.muted) {
      dropped.muted += 1;
      continue;
    }

    let instrumentKey = part.instrumentKey;
    let pitch;
    let flags = [];
    if (source.percussion && part.percussion) {
      const drum = drumMapping(source.midi);
      instrumentKey = part.instrumentKey === "sticks" ? drum.instrumentKey : part.instrumentKey;
      pitch = Math.max(0, Math.min(24, drum.pitch + part.transpose + settings.globalTranspose));
      flags.push("percussion");
    } else {
      const rawPitch = source.midi + part.transpose + settings.globalTranspose - 54;
      const pitchResult = normalizePitch(rawPitch, settings.pitchPolicy);
      if (pitchResult.pitch === null) {
        dropped.outOfRange += 1;
        continue;
      }
      pitch = pitchResult.pitch;
      if (pitchResult.flag) {
        flags.push(pitchResult.flag);
        if (pitchResult.flag === "octave-folded") folded += 1;
        if (pitchResult.flag === "clamped") clamped += 1;
      }
    }

    const partAutomation = automationByPart.get(part.id) || {};
    const velocity = automationValueSorted(partAutomation.velocity, source.startMs, source.velocity);
    const channelVolume = automationValueSorted(partAutomation.volume, source.startMs, source.channelVolume);
    const expression = automationValueSorted(partAutomation.expression, source.startMs, source.expression);
    const sourcePan = automationValueSorted(partAutomation.pan, source.startMs, source.pan);
    const channelScale = (velocity / 127)
      * (channelVolume / 127)
      * (expression / 127)
      * (Math.max(0, part.volume) / 100);
    const volume = Math.max(0, Math.min(100, Math.round(channelScale * 100)));
    const pan = settings.preservePan
      ? Math.max(-100, Math.min(100, Math.round(((sourcePan - 64) / 63) * 100)))
      : 0;
    const note = {
      timeMs: Math.max(0, Math.round(source.startMs - offsetMs)),
      instrumentKey,
      pitch,
      volume,
      pan,
      sourceNoteId: source.id,
      sourceMidi: source.midi,
      partId: part.id,
      flags,
    };

    converted.push(note);
  }

  converted.sort(
    (a, b) =>
      a.timeMs - b.timeMs ||
      a.instrumentKey.localeCompare(b.instrumentKey) ||
      a.pitch - b.pitch ||
      a.pan - b.pan,
  );
  if (settings.deduplicate && converted.length > 1) {
    let writeIndex = 0;
    for (const note of converted) {
      const previous = writeIndex > 0 ? converted[writeIndex - 1] : null;
      if (previous &&
          previous.timeMs === note.timeMs &&
          previous.instrumentKey === note.instrumentKey &&
          previous.pitch === note.pitch &&
          previous.pan === note.pan) {
        dropped.duplicate += 1;
        if (previous.volume < note.volume) converted[writeIndex - 1] = note;
      } else {
        converted[writeIndex] = note;
        writeIndex += 1;
      }
    }
    converted.length = writeIndex;
  }
  const metrics = calculateMetrics(converted);
  return {
    notes: converted,
    metrics: {
      ...metrics,
      inputNotes: midi.notes.length,
      folded,
      clamped,
      dropped,
      offsetMs,
    },
  };
}

export function automationValue(points, timeMs, fallback) {
  if (!Array.isArray(points) || points.length === 0) return clampMidiControl(fallback);
  const sorted = [...points].sort((a, b) => a.timeMs - b.timeMs);
  return automationValueSorted(sorted, timeMs, fallback);
}

function automationValueSorted(sorted, timeMs, fallback) {
  if (!Array.isArray(sorted) || sorted.length === 0) return clampMidiControl(fallback);
  if (timeMs <= sorted[0].timeMs) return clampMidiControl(sorted[0].value);
  if (timeMs >= sorted.at(-1).timeMs) return clampMidiControl(sorted.at(-1).value);
  let low = 0;
  let high = sorted.length - 1;
  while (low + 1 < high) {
    const middle = Math.floor((low + high) / 2);
    if (sorted[middle].timeMs <= timeMs) low = middle;
    else high = middle;
  }
  const before = sorted[low];
  const after = sorted[high];
  const ratio = (timeMs - before.timeMs) / Math.max(1, after.timeMs - before.timeMs);
  return clampMidiControl(before.value + (after.value - before.value) * ratio);
}

function normalizeAutomation(automation) {
  const normalized = new Map();
  for (const [partId, lanes] of Object.entries(automation || {})) {
    const part = {};
    for (const lane of ["velocity", "volume", "pan", "expression"]) {
      part[lane] = Array.isArray(lanes?.[lane])
        ? [...lanes[lane]].sort((a, b) => a.timeMs - b.timeMs)
        : [];
    }
    normalized.set(partId, part);
  }
  return normalized;
}

function clampMidiControl(value) {
  return Math.max(0, Math.min(127, Math.round(Number(value) || 0)));
}

export function recommendGlobalTranspose(notes) {
  if (!notes.length) return 0;
  const weightedPitches = new Float64Array(128);
  for (const note of notes) {
    if (!note.percussion) weightedPitches[note.midi] += Math.max(1, note.velocity);
  }
  const prefix = new Float64Array(129);
  for (let index = 0; index < 128; index += 1) prefix[index + 1] = prefix[index] + weightedPitches[index];
  let best = { transpose: 0, score: -1, distance: Number.POSITIVE_INFINITY };
  for (let transpose = -24; transpose <= 24; transpose += 1) {
    const minimum = Math.max(0, 54 - transpose);
    const maximum = Math.min(127, 78 - transpose);
    const score = minimum <= maximum ? prefix[maximum + 1] - prefix[minimum] : 0;
    const distance = Math.abs(transpose);
    if (score > best.score || (score === best.score && distance < best.distance)) {
      best = { transpose, score, distance };
    }
  }
  return best.transpose;
}

function normalizePitch(rawPitch, policy) {
  if (rawPitch >= 0 && rawPitch <= 24) return { pitch: Math.round(rawPitch), flag: null };
  if (policy === "drop") return { pitch: null, flag: "dropped-out-of-range" };
  if (policy === "clamp") {
    return { pitch: Math.max(0, Math.min(24, Math.round(rawPitch))), flag: "clamped" };
  }
  let pitch = Math.round(rawPitch);
  while (pitch < 0) pitch += 12;
  while (pitch > 24) pitch -= 12;
  return { pitch, flag: "octave-folded" };
}

function calculateMetrics(notes) {
  if (notes.length === 0) {
    return { durationMs: 0, maxChord: 0, maxNotesPerSecond: 0, positionalNotes: 0 };
  }
  let maxChord = 0;
  let currentTime = -1;
  let chordSize = 0;
  let maxNotesPerSecond = 0;
  let windowStart = 0;
  let positionalNotes = 0;
  for (let index = 0; index < notes.length; index += 1) {
    const note = notes[index];
    if (note.timeMs === currentTime) chordSize += 1;
    else {
      currentTime = note.timeMs;
      chordSize = 1;
    }
    maxChord = Math.max(maxChord, chordSize);
    while (notes[windowStart].timeMs < note.timeMs - 1000) windowStart += 1;
    maxNotesPerSecond = Math.max(maxNotesPerSecond, index - windowStart + 1);
    if (note.pan !== 0) positionalNotes += 1;
  }
  return {
    durationMs: notes.at(-1).timeMs,
    maxChord,
    maxNotesPerSecond,
    positionalNotes,
  };
}
