/** 複数の解析済みMIDIを、同時開始または末尾連結で1つの編集用MIDIへまとめる。 */
export function mergeMidiDocuments(
  midis,
  { mode = "overlay", gapMs = 250, baseStartMs = 0, trackIndexOffset = 0 } = {},
) {
  if (!Array.isArray(midis) || midis.length === 0) throw new Error("結合するMIDIがありません。");
  if (!midis.every((midi) => midi && Array.isArray(midi.notes) && Array.isArray(midi.tracks))) {
    throw new Error("解析済みMIDIの形式が不正です。");
  }

  const notes = [];
  const tracks = [];
  const sources = [];
  const warnings = { pitchBend: 0, sysex: 0, unterminatedNotes: 0 };
  const tempos = [];
  let appendCursorMs = Math.max(0, baseStartMs);
  let nextTrackIndex = Math.max(0, trackIndexOffset);

  midis.forEach((midi, midiIndex) => {
    const startOffsetMs = mode === "append" ? appendCursorMs : Math.max(0, baseStartMs);
    const sourceLabel = sourceBaseName(midi.sourceName || `MIDI ${midiIndex + 1}`);
    const prefixTrackNames = midis.length > 1 || trackIndexOffset > 0;
    const trackMap = new Map();

    for (const track of midi.tracks) {
      const mappedIndex = nextTrackIndex++;
      trackMap.set(track.index, mappedIndex);
      tracks.push({
        ...track,
        index: mappedIndex,
        name: prefixTrackNames ? `${sourceLabel} · ${track.name}` : track.name,
        sourceName: midi.sourceName,
      });
    }

    for (const note of midi.notes) {
      const mappedTrackIndex = trackMap.get(note.trackIndex) ?? nextTrackIndex++;
      notes.push({
        ...note,
        id: -1,
        order: notes.length,
        trackIndex: mappedTrackIndex,
        trackName: prefixTrackNames ? `${sourceLabel} · ${note.trackName}` : note.trackName,
        startMs: Math.max(0, note.startMs + startOffsetMs),
        sourceName: midi.sourceName,
      });
    }

    for (const key of Object.keys(warnings)) warnings[key] += Number(midi.warnings?.[key] || 0);
    sources.push({
      fileName: midi.sourceName,
      title: midi.title,
      noteCount: midi.notes.length,
      trackCount: midi.tracks.length,
      startOffsetMs,
      durationMs: midi.durationMs,
    });

    if (mode === "append") {
      for (const tempo of midi.tempos || []) {
        tempos.push({ ...tempo, timeMs: tempo.timeMs + startOffsetMs, sourceName: midi.sourceName });
      }
      appendCursorMs = startOffsetMs + Math.max(0, midi.durationMs) + Math.max(0, gapMs);
    } else if (midiIndex === 0) {
      tempos.push(...(midi.tempos || []).map((tempo) => ({ ...tempo, timeMs: tempo.timeMs + startOffsetMs })));
    }
  });

  notes.sort((a, b) => a.startMs - b.startMs || a.order - b.order);
  notes.forEach((note, id) => { note.id = id; });
  const durationMs = notes.reduce(
    (maximum, note) => Math.max(maximum, note.startMs + Math.max(0, note.durationMs || 0)),
    0,
  );
  const first = midis[0];
  const title = midis.length === 1 ? first.title : midis.map((midi) => midi.title).join(" + ").slice(0, 120);
  return {
    format: midis.length === 1 ? first.format : 1,
    ppq: first.ppq,
    title,
    sourceName: midis.length === 1 ? first.sourceName : `${midis.length} MIDI merged.mid`,
    durationMs,
    notes,
    tracks,
    tempos: normalizeTempos(tempos, first.tempos),
    timeSignatures: first.timeSignatures || [],
    warnings,
    sources,
  };
}

function normalizeTempos(tempos, fallback) {
  const sorted = [...tempos].sort((a, b) => a.timeMs - b.timeMs);
  if (sorted.length > 0) return sorted;
  return fallback?.length ? fallback : [{ tick: 0, timeMs: 0, tempo: 500_000, bpm: 120 }];
}

function sourceBaseName(name) {
  return String(name || "MIDI").replace(/\.(midi?|smf)$/i, "").slice(0, 80) || "MIDI";
}
