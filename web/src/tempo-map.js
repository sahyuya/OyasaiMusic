const DEFAULT_BPM = 120;

/** 絶対時刻で並ぶテンポイベントへ、編集で維持する音楽tick位置を付ける。 */
export function canonicalizeTempoMap(tempos = [], ppq = 480) {
  const safePpq = Math.max(1, Number(ppq) || 480);
  const sorted = (tempos || [])
    .map((tempo, index) => ({
      ...tempo,
      id: String(tempo.id || `tempo-${index + 1}`),
      timeMs: Math.max(0, Number(tempo.timeMs) || 0),
      bpm: normalizeBpm(tempo.bpm || (60_000_000 / Math.max(1, Number(tempo.tempo) || 500_000))),
    }))
    .sort((a, b) => a.timeMs - b.timeMs);
  const deduplicated = [];
  for (const event of sorted) {
    const last = deduplicated.at(-1);
    if (last && Math.abs(last.timeMs - event.timeMs) < 0.001) deduplicated[deduplicated.length - 1] = event;
    else deduplicated.push(event);
  }
  if (!deduplicated.length || deduplicated[0].timeMs > 0.001) {
    deduplicated.unshift({ id: uniqueTempoId(deduplicated), timeMs: 0, bpm: DEFAULT_BPM });
  }
  deduplicated[0].timeMs = 0;
  let tick = 0;
  let previousTime = 0;
  let previousBpm = deduplicated[0].bpm;
  return deduplicated.map((event, index) => {
    if (index > 0) {
      tick += ((event.timeMs - previousTime) * previousBpm * safePpq) / 60_000;
      previousTime = event.timeMs;
      previousBpm = event.bpm;
    }
    return {
      ...event,
      tick,
      tempo: 60_000_000 / event.bpm,
    };
  });
}

/** tick位置とBPMから、各イベントの新しい絶対時刻を再計算する。 */
export function rebuildTempoMap(events = [], ppq = 480) {
  const safePpq = Math.max(1, Number(ppq) || 480);
  const sorted = (events || [])
    .map((event, index) => ({
      ...event,
      id: String(event.id || `tempo-${index + 1}`),
      tick: Math.max(0, Number(event.tick) || 0),
      bpm: normalizeBpm(event.bpm),
    }))
    .sort((a, b) => a.tick - b.tick);
  const deduplicated = [];
  for (const event of sorted) {
    const last = deduplicated.at(-1);
    if (last && Math.abs(last.tick - event.tick) < 0.001) deduplicated[deduplicated.length - 1] = event;
    else deduplicated.push(event);
  }
  if (!deduplicated.length || deduplicated[0].tick > 0.001) {
    deduplicated.unshift({ id: uniqueTempoId(deduplicated), tick: 0, bpm: DEFAULT_BPM });
  }
  deduplicated[0].tick = 0;
  let timeMs = 0;
  let previousTick = 0;
  let previousBpm = deduplicated[0].bpm;
  return deduplicated.map((event, index) => {
    if (index > 0) {
      timeMs += ((event.tick - previousTick) * 60_000) / (previousBpm * safePpq);
      previousTick = event.tick;
      previousBpm = event.bpm;
    }
    return {
      ...event,
      timeMs,
      tempo: 60_000_000 / event.bpm,
    };
  });
}

export function tempoAtTime(tempos, timeMs, ppq = 480) {
  const map = canonicalizeTempoMap(tempos, ppq);
  const target = Math.max(0, Number(timeMs) || 0);
  let active = map[0];
  for (const event of map) {
    if (event.timeMs > target) break;
    active = event;
  }
  return active;
}

export function timeToTempoTick(timeMs, tempos, ppq = 480) {
  const map = canonicalizeTempoMap(tempos, ppq);
  const target = Math.max(0, Number(timeMs) || 0);
  let active = map[0];
  for (const event of map) {
    if (event.timeMs > target) break;
    active = event;
  }
  return active.tick + ((target - active.timeMs) * active.bpm * Math.max(1, Number(ppq) || 480)) / 60_000;
}

export function tempoTickToTime(tick, tempos, ppq = 480) {
  const map = rebuildTempoMap(tempos, ppq);
  const target = Math.max(0, Number(tick) || 0);
  let active = map[0];
  for (const event of map) {
    if (event.tick > target) break;
    active = event;
  }
  return active.timeMs + ((target - active.tick) * 60_000) / (active.bpm * Math.max(1, Number(ppq) || 480));
}

/** テンポ変更前の絶対時刻を音楽tickへ戻し、新しいテンポマップの時刻へ写像する。 */
export function retimeForTempoChange({
  notes = [],
  timeSignatures = [],
  automation = {},
  view = null,
  previewPositionMs = 0,
  oldTempos = [],
  nextTempoEvents = [],
  ppq = 480,
}) {
  const oldMap = canonicalizeTempoMap(oldTempos, ppq);
  const nextMap = rebuildTempoMap(nextTempoEvents, ppq);
  const mapTime = (timeMs) => tempoTickToTime(timeToTempoTick(timeMs, oldMap, ppq), nextMap, ppq);
  const nextNotes = notes.map((note) => {
    const startMs = mapTime(note.startMs);
    const endMs = mapTime(note.startMs + Math.max(0, Number(note.durationMs) || 0));
    return {
      ...note,
      startMs,
      durationMs: Math.max(0, endMs - startMs),
      startTick: timeToTempoTick(note.startMs, oldMap, ppq),
      endTick: timeToTempoTick(note.startMs + Math.max(0, Number(note.durationMs) || 0), oldMap, ppq),
    };
  });
  const nextSignatures = timeSignatures.map((signature) => {
    const tick = timeToTempoTick(signature.timeMs, oldMap, ppq);
    return { ...signature, tick, timeMs: tempoTickToTime(tick, nextMap, ppq) };
  });
  const nextAutomation = {};
  for (const [partId, lanes] of Object.entries(automation || {})) {
    nextAutomation[partId] = {};
    for (const [lane, points] of Object.entries(lanes || {})) {
      nextAutomation[partId][lane] = (points || []).map((point) => ({ ...point, timeMs: mapTime(point.timeMs) }));
    }
  }
  const durationMs = nextNotes.reduce(
    (maximum, note) => Math.max(maximum, note.startMs + Math.max(0, note.durationMs || 0)),
    0,
  );
  return {
    tempos: nextMap,
    notes: nextNotes,
    timeSignatures: nextSignatures,
    automation: nextAutomation,
    durationMs,
    previewPositionMs: Math.min(durationMs, mapTime(previewPositionMs)),
    view: view ? {
      ...view,
      startMs: Math.min(durationMs, mapTime(view.startMs)),
      endMs: Math.min(durationMs, Math.max(mapTime(view.endMs), mapTime(view.startMs) + 1)),
    } : null,
  };
}

export function normalizeBpm(value) {
  return Math.max(1, Math.min(60_000, Math.round(Number(value) || DEFAULT_BPM)));
}

function uniqueTempoId(events) {
  const used = new Set(events.map((event) => event.id));
  let counter = events.length + 1;
  while (used.has(`tempo-${counter}`)) counter += 1;
  return `tempo-${counter}`;
}
