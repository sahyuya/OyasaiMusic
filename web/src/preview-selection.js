/** 編集モードに応じて、試聴対象にする変換済みノートを返す。 */
export function selectPreviewNotes(notes, editorMode, activePartId) {
  const source = Array.isArray(notes) ? notes : [];
  if (editorMode !== "part" || !activePartId) return source;
  return source.filter((note) => note.partId === activePartId);
}

/**
 * パート試聴の開始位置を、そのパートに実際のノートが存在する範囲へ補正する。
 *
 * 別パートで曲の後半まで再生してから短いパートへ切り替えた場合など、現在位置より
 * 後ろにノートが無ければ、そのパートの先頭ノートへ戻して無音のまま終了するのを防ぐ。
 */
export function normalizePreviewStart(notes, requestedTimeMs) {
  if (!Array.isArray(notes) || notes.length === 0) return 0;
  let firstTimeMs = Number.POSITIVE_INFINITY;
  let lastTimeMs = 0;
  for (const note of notes) {
    const timeMs = Math.max(0, Number(note?.timeMs) || 0);
    firstTimeMs = Math.min(firstTimeMs, timeMs);
    lastTimeMs = Math.max(lastTimeMs, timeMs);
  }
  const requested = Math.max(0, Number(requestedTimeMs) || 0);
  if (requested < firstTimeMs || requested > lastTimeMs) return firstTimeMs;
  return requested;
}
