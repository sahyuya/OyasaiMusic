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
