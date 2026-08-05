/** 表示範囲の拍グリッド時刻を作る。テンポ変更付き単一MIDIではtick位置を維持する。 */
export function buildTimeGrid({ tempos = [], ppq = 480, startMs = 0, endMs = 1000, subdivision = 4 }) {
  const safeStart = Math.max(0, Number(startMs) || 0);
  const safeEnd = Math.max(safeStart, Number(endMs) || safeStart);
  const safePpq = Math.max(1, Number(ppq) || 480);
  const safeSubdivision = Math.max(1, Math.min(16, Number(subdivision) || 4));
  const normalized = normalizeTempos(tempos);
  const monotonicTicks = normalized.every((tempo, index) => index === 0 || tempo.tick >= normalized[index - 1].tick);
  return monotonicTicks
    ? buildTickGrid(normalized, safePpq, safeStart, safeEnd, safeSubdivision)
    : buildMillisecondGrid(normalized, safeStart, safeEnd, safeSubdivision);
}

/** ピアノロール上部へ表示する小節・拍付きの時間目盛りを作る。 */
export function buildRulerGrid({
  tempos = [],
  timeSignatures = [],
  ppq = 480,
  startMs = 0,
  endMs = 1000,
  subdivision = 4,
}) {
  const safeStart = Math.max(0, Number(startMs) || 0);
  const safeEnd = Math.max(safeStart, Number(endMs) || safeStart);
  const safePpq = Math.max(1, Number(ppq) || 480);
  const safeSubdivision = Math.max(1, Math.min(16, Number(subdivision) || 4));
  const normalizedTempos = normalizeTempos(tempos);
  const signatures = normalizeTimeSignatures(timeSignatures);
  const monotonicTicks = normalizedTempos.every((tempo, index) => index === 0 || tempo.tick >= normalizedTempos[index - 1].tick);
  if (!monotonicTicks) {
    return buildTimeBasedRuler(normalizedTempos, signatures[0], safeStart, safeEnd, safeSubdivision);
  }
  const startTick = timeToTick(safeStart, normalizedTempos, safePpq);
  const endTick = timeToTick(safeEnd, normalizedTempos, safePpq);
  const quarterStep = safePpq / safeSubdivision;
  const firstTick = Math.floor(startTick / quarterStep) * quarterStep;
  const estimated = Math.max(1, Math.ceil((endTick - firstTick) / quarterStep));
  const stride = Math.max(1, Math.ceil(estimated / 5000));
  const marks = [];
  for (let tick = firstTick; tick <= endTick + quarterStep; tick += quarterStep * stride) {
    const timeMs = tickToTime(tick, normalizedTempos, safePpq);
    if (timeMs < safeStart - 1 || timeMs > safeEnd + 1) continue;
    const musical = musicalPosition(tick, signatures, safePpq);
    marks.push({
      timeMs: Math.max(0, Math.round(timeMs * 1000) / 1000),
      tick,
      bar: musical.bar,
      beat: musical.beat,
      isBar: musical.isBar,
      isBeat: musical.isBeat,
    });
  }
  return marks;
}

function buildTimeBasedRuler(tempos, signature, startMs, endMs, subdivision) {
  const times = buildMillisecondGrid(tempos, startMs, endMs, subdivision);
  const quarterPerBar = signature.numerator * 4 / signature.denominator;
  return times.map((timeMs) => {
    const quarter = musicalQuartersAtTime(timeMs, tempos);
    const roundedSubdivision = Math.round(quarter * subdivision) / subdivision;
    const withinBar = ((roundedSubdivision % quarterPerBar) + quarterPerBar) % quarterPerBar;
    const withinBeat = ((roundedSubdivision % 1) + 1) % 1;
    return {
      timeMs,
      tick: null,
      bar: Math.floor(roundedSubdivision / quarterPerBar) + 1,
      beat: Math.floor(withinBar) + 1,
      isBar: withinBar < 0.001 || quarterPerBar - withinBar < 0.001,
      isBeat: withinBeat < 0.001 || 1 - withinBeat < 0.001,
    };
  });
}

function musicalQuartersAtTime(timeMs, tempos) {
  let quarters = 0;
  for (let index = 0; index < tempos.length; index += 1) {
    const tempo = tempos[index];
    const segmentStart = tempo.timeMs;
    const segmentEnd = Math.min(timeMs, tempos[index + 1]?.timeMs ?? timeMs);
    if (segmentEnd > segmentStart) quarters += (segmentEnd - segmentStart) / (60_000 / tempo.bpm);
    if (timeMs < (tempos[index + 1]?.timeMs ?? Number.POSITIVE_INFINITY)) break;
  }
  return Math.max(0, quarters);
}

export function nearestGridTime(timeMs, gridTimes) {
  if (!gridTimes?.length) return Math.max(0, timeMs);
  let low = 0;
  let high = gridTimes.length - 1;
  while (low < high) {
    const middle = Math.floor((low + high) / 2);
    if (gridTimes[middle] < timeMs) low = middle + 1;
    else high = middle;
  }
  const after = gridTimes[low];
  const before = gridTimes[Math.max(0, low - 1)];
  return Math.abs(after - timeMs) < Math.abs(timeMs - before) ? after : before;
}

export function isMinecraftBoundaryPitch(pitch) {
  return ((Math.round(Number(pitch) || 0) % 12) + 12) % 12 === 6;
}

function buildTickGrid(tempos, ppq, startMs, endMs, subdivision) {
  const stepTicks = ppq / subdivision;
  const startTick = timeToTick(startMs, tempos, ppq);
  const endTick = timeToTick(endMs, tempos, ppq);
  const firstTick = Math.floor(startTick / stepTicks) * stepTicks;
  const result = [];
  const maximumLines = 5000;
  const stride = Math.max(1, Math.ceil(((endTick - firstTick) / stepTicks) / maximumLines));
  for (let tick = firstTick; tick <= endTick + stepTicks; tick += stepTicks * stride) {
    const timeMs = tickToTime(tick, tempos, ppq);
    if (timeMs >= startMs - 1 && timeMs <= endMs + 1) result.push(timeMs);
  }
  return ensureBounds(result, startMs, endMs);
}

function buildMillisecondGrid(tempos, startMs, endMs, subdivision) {
  const result = [];
  const maximumLines = 5000;
  for (let index = 0; index < tempos.length; index += 1) {
    const tempo = tempos[index];
    const segmentStart = Math.max(startMs, tempo.timeMs);
    const segmentEnd = Math.min(endMs, tempos[index + 1]?.timeMs ?? endMs);
    if (segmentEnd < segmentStart) continue;
    const stepMs = 60_000 / tempo.bpm / subdivision;
    const estimated = Math.max(1, Math.ceil((segmentEnd - segmentStart) / stepMs));
    const stride = Math.max(1, Math.ceil(estimated / maximumLines));
    const origin = tempo.timeMs;
    let cursor = origin + Math.floor((segmentStart - origin) / stepMs) * stepMs;
    while (cursor < segmentStart - 0.5) cursor += stepMs * stride;
    for (; cursor <= segmentEnd + 0.5 && result.length < maximumLines; cursor += stepMs * stride) result.push(cursor);
  }
  return ensureBounds(result, startMs, endMs);
}

function normalizeTempos(tempos) {
  const normalized = (tempos || [])
    .map((tempo) => ({
      tick: Math.max(0, Number(tempo.tick) || 0),
      timeMs: Math.max(0, Number(tempo.timeMs) || 0),
      tempo: Math.max(1, Number(tempo.tempo) || (60_000_000 / Math.max(1, Number(tempo.bpm) || 120))),
      bpm: Math.max(1, Number(tempo.bpm) || (60_000_000 / Math.max(1, Number(tempo.tempo) || 500_000))),
    }))
    .sort((a, b) => a.timeMs - b.timeMs);
  return normalized.length ? normalized : [{ tick: 0, timeMs: 0, tempo: 500_000, bpm: 120 }];
}

function normalizeTimeSignatures(timeSignatures) {
  const rows = (timeSignatures || [])
    .map((signature) => ({
      tick: Math.max(0, Number(signature.tick) || 0),
      numerator: Math.max(1, Number(signature.numerator) || 4),
      denominator: [1, 2, 4, 8, 16, 32].includes(Number(signature.denominator))
        ? Number(signature.denominator)
        : 4,
    }))
    .sort((a, b) => a.tick - b.tick);
  if (!rows.length || rows[0].tick !== 0) rows.unshift({ tick: 0, numerator: 4, denominator: 4 });
  return rows.filter((row, index) => index === 0 || row.tick !== rows[index - 1].tick);
}

function musicalPosition(tick, signatures, ppq) {
  let barOffset = 0;
  let active = signatures[0];
  for (let index = 0; index < signatures.length; index += 1) {
    const signature = signatures[index];
    const next = signatures[index + 1];
    if (tick < signature.tick) break;
    active = signature;
    if (!next || tick < next.tick) break;
    const ticksPerBeat = ppq * 4 / signature.denominator;
    const ticksPerBar = ticksPerBeat * signature.numerator;
    barOffset += Math.max(0, Math.round((next.tick - signature.tick) / ticksPerBar));
  }
  const ticksPerBeat = ppq * 4 / active.denominator;
  const ticksPerBar = ticksPerBeat * active.numerator;
  const local = Math.max(0, tick - active.tick);
  const withinBar = ((local % ticksPerBar) + ticksPerBar) % ticksPerBar;
  const epsilon = 0.001;
  return {
    bar: barOffset + Math.floor(local / ticksPerBar) + 1,
    beat: Math.floor(withinBar / ticksPerBeat) + 1,
    isBar: withinBar < epsilon || ticksPerBar - withinBar < epsilon,
    isBeat: (withinBar % ticksPerBeat) < epsilon || ticksPerBeat - (withinBar % ticksPerBeat) < epsilon,
  };
}

function tickToTime(tick, tempos, ppq) {
  let segment = tempos[0];
  for (const tempo of tempos) {
    if (tempo.tick > tick) break;
    segment = tempo;
  }
  return segment.timeMs + ((tick - segment.tick) * segment.tempo) / ppq / 1000;
}

function timeToTick(timeMs, tempos, ppq) {
  let segment = tempos[0];
  for (const tempo of tempos) {
    if (tempo.timeMs > timeMs) break;
    segment = tempo;
  }
  return segment.tick + ((timeMs - segment.timeMs) * 1000 * ppq) / segment.tempo;
}

function ensureBounds(times, startMs, endMs) {
  const result = [...new Set(times.map((time) => Math.max(0, Math.round(time * 1000) / 1000)))].sort((a, b) => a - b);
  if (!result.length || result[0] > startMs) result.unshift(startMs);
  if (result.at(-1) < endMs) result.push(endMs);
  return result;
}
