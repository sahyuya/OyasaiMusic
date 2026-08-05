import { PreviewPlayer } from "./audio-preview.js";
import {
  DEFAULT_SETTINGS,
  convertMidi,
  createInitialParts,
  deleteEmptyParts,
  moveSelectionToPart,
  recommendGlobalTranspose,
  splitSelectionIntoPart,
} from "./converter.js";
import { NOTE_BLOCK_INSTRUMENTS, midiNoteName } from "./instruments.js";
import { buildTimeGrid, nearestGridTime } from "./grid.js";
import { mergeMidiDocuments } from "./midi-merge.js";
import { encodeOyasaiPackage, downloadBlob } from "./oyasai-format.js";
import { PianoRoll } from "./piano-roll.js";
import { clearLatestSession, loadLatestSession, saveLatestSession } from "./session-store.js";

const workspace = document.querySelector("#workspace");
const state = {
  midi: null,
  parts: [],
  assignments: [],
  selectedIds: new Set(),
  settings: { ...DEFAULT_SETTINGS },
  conversion: null,
  title: "",
  roll: null,
  view: { startMs: 0, endMs: 1000, minPitch: 36, maxPitch: 84 },
  sourcePitchRange: { min: 36, max: 84 },
  editorMode: "all",
  activePartId: null,
  gridSubdivision: 4,
  snapToGrid: true,
  horizontalZoom: 1,
  verticalZoom: 1,
  previewPositionMs: 0,
  worker: null,
  conversionTimer: null,
  sessionTimer: null,
  sessionSavingEnabled: true,
  suspendedSession: null,
};

const preview = new PreviewPlayer({
  onTime(current, duration) {
    const progress = document.querySelector("#preview-progress");
    const time = document.querySelector("#preview-time");
    const seek = document.querySelector("#preview-seek");
    state.previewPositionMs = Math.max(0, Math.min(duration, current));
    if (progress) progress.value = duration > 0 ? Math.min(100, (current / duration) * 100) : 0;
    if (time) time.textContent = `${formatTime(current)} / ${formatTime(duration)}`;
    if (seek && !seek.matches(":active")) seek.value = state.previewPositionMs;
    state.roll?.setPlayhead(state.previewPositionMs);
  },
  onStop() {
    updatePreviewButton(false);
    scheduleSessionSave();
  },
});

boot();

async function boot() {
  try {
    const saved = await loadLatestSession();
    if (saved) {
      restoreSession(saved);
      return;
    }
  } catch {
    // IndexedDBを利用できない場合も変換機能は継続する。
  }
  renderUpload();
}

function renderUpload() {
  if (state.midi) state.suspendedSession = createSessionSnapshot();
  state.worker?.terminate();
  preview.stop(false);
  state.midi = null;
  state.roll = null;
  workspace.innerHTML = `
    <section class="upload-card" aria-labelledby="upload-title">
      <div class="upload-copy">
        <span class="step-label">STEP 01 · MIDIを読み込む</span>
        <h2 id="upload-title">変換したい曲をここへ</h2>
        <p>
          Standard MIDI File Type 0 / 1に対応しています。ファイルはアップロードされず、
          解析から書き出しまですべてこの端末内で処理されます。
        </p>
        <div class="format-notes" aria-label="対応情報">
          <span>.mid / .midi</span><span>テンポ変更対応</span><span>トラック再編成対応</span>
        </div>
      </div>
      <div id="drop-zone" class="drop-zone" tabindex="0" role="button" aria-describedby="drop-help">
        <div class="drop-icon" aria-hidden="true">↥</div>
        <strong>1個または複数のMIDIをドロップ</strong>
        <span>または</span>
        <button type="button" id="choose-file" class="primary-button">ファイルを選ぶ</button>
        <small id="drop-help">複数選択時は開始位置を合わせ、パートを保ったまま重ねます</small>
        <input id="file-input" type="file" accept=".mid,.midi,audio/midi,audio/x-midi" multiple hidden />
      </div>
      ${state.suspendedSession ? '<button type="button" id="resume-session" class="secondary-button resume-button">直前の編集へ戻る</button>' : ""}
    </section>

    <section class="how-it-works" aria-labelledby="flow-title">
      <div>
        <span class="step-label">WORKFLOW</span>
        <h2 id="flow-title">3つの工程で、ゲーム内へ。</h2>
      </div>
      <ol class="flow-list">
        <li><b>01</b><span><strong>解析する</strong><small>テンポ、音域、楽器、ノートを読み取る</small></span></li>
        <li><b>02</b><span><strong>パートを整える</strong><small>範囲・音域・単音から自由に分割</small></span></li>
        <li><b>03</b><span><strong>OyasaiMusicへ</strong><small>.oyasaiを出力し、非公開下書きとして取り込む</small></span></li>
      </ol>
    </section>
  `;

  const fileInput = document.querySelector("#file-input");
  const dropZone = document.querySelector("#drop-zone");
  document.querySelector("#choose-file").addEventListener("click", (event) => {
    event.stopPropagation();
    fileInput.click();
  });
  fileInput.addEventListener("change", () => fileInput.files?.length && loadFiles([...fileInput.files]));
  dropZone.addEventListener("click", (event) => {
    if (event.target.closest("button")) return;
    fileInput.click();
  });
  dropZone.addEventListener("keydown", (event) => {
    if (event.key === "Enter" || event.key === " ") {
      event.preventDefault();
      fileInput.click();
    }
  });
  for (const type of ["dragenter", "dragover"]) {
    dropZone.addEventListener(type, (event) => {
      event.preventDefault();
      dropZone.classList.add("is-dragging");
    });
  }
  for (const type of ["dragleave", "drop"]) {
    dropZone.addEventListener(type, (event) => {
      event.preventDefault();
      dropZone.classList.remove("is-dragging");
    });
  }
  dropZone.addEventListener("drop", (event) => {
    const files = [...(event.dataTransfer?.files || [])];
    if (files.length) loadFiles(files);
  });
  document.querySelector("#resume-session")?.addEventListener("click", () => restoreSession(state.suspendedSession));
}

async function loadFiles(files, { addToCurrent = false, mergeMode = "overlay" } = {}) {
  const midiFiles = files.filter((file) => /\.(mid|midi)$/i.test(file.name));
  if (midiFiles.length !== files.length || midiFiles.length === 0) {
    if (addToCurrent) showEditorNotice(".mid または .midi ファイルだけを選んでください。", "error");
    else showUploadError(".mid または .midi ファイルを選んでください。");
    return;
  }
  const previousSnapshot = addToCurrent ? createSessionSnapshot() : null;
  workspace.innerHTML = `
    <section class="processing-card" aria-labelledby="processing-title">
      <span class="step-label">ANALYZING LOCALLY</span>
      <div class="processing-visual" aria-hidden="true"><i></i><i></i><i></i><i></i><i></i><i></i><i></i></div>
      <h2 id="processing-title">${midiFiles.length === 1 ? escapeHtml(midiFiles[0].name) : `${midiFiles.length}個のMIDI`}を解析中</h2>
      <p id="processing-status">MIDIヘッダーを確認しています…</p>
      <button type="button" id="cancel-processing" class="text-button">キャンセル</button>
    </section>
  `;

  try {
    document.querySelector("#cancel-processing").addEventListener("click", () => {
      state.worker?.terminate();
      state.worker = null;
      if (previousSnapshot) restoreSession(previousSnapshot);
      else renderUpload();
    });
    const parsed = [];
    for (let index = 0; index < midiFiles.length; index += 1) {
      const file = midiFiles[index];
      parsed.push(await parseMidiFile(file, index, midiFiles.length));
    }
    if (addToCurrent && previousSnapshot) {
      restoreSession(previousSnapshot, false);
      addParsedMidis(parsed, mergeMode);
    } else {
      initializeEditor(mergeMidiDocuments(parsed, { mode: mergeMode }));
    }
  } catch (error) {
    state.worker?.terminate();
    state.worker = null;
    if (previousSnapshot) {
      restoreSession(previousSnapshot);
      showEditorNotice(error instanceof Error ? error.message : "追加MIDIを読み取れませんでした。", "error");
    } else {
      renderUpload();
      showUploadError(error instanceof Error ? error.message : "ファイルを読み取れませんでした。");
    }
  }
}

async function parseMidiFile(file, fileIndex, totalFiles) {
  const buffer = await file.arrayBuffer();
  return new Promise((resolve, reject) => {
    const worker = new Worker(new URL("./midi-worker.js", import.meta.url), { type: "module" });
    state.worker = worker;
    worker.addEventListener("message", (event) => {
      if (event.data.type === "progress") {
        const { track, totalTracks, notes } = event.data.progress;
        const status = document.querySelector("#processing-status");
        if (status) status.textContent = `ファイル ${fileIndex + 1} / ${totalFiles} · トラック ${track} / ${totalTracks} · ${formatNumber(notes)}ノート`;
      } else if (event.data.type === "result") {
        worker.terminate();
        state.worker = null;
        resolve(event.data.midi);
      } else if (event.data.type === "error") {
        worker.terminate();
        state.worker = null;
        reject(new Error(`${file.name}: ${event.data.message}`));
      }
    });
    worker.addEventListener("error", () => {
      worker.terminate();
      state.worker = null;
      reject(new Error(`${file.name}: 解析処理を開始できませんでした。`));
    });
    worker.postMessage({ buffer, sourceName: file.name }, [buffer]);
  });
}

function showUploadError(message) {
  const uploadCard = workspace.querySelector(".upload-card");
  if (!uploadCard) return;
  uploadCard.querySelector(".inline-error")?.remove();
  uploadCard.insertAdjacentHTML(
    "afterbegin",
    `<div class="inline-error" role="alert"><strong>読み込めませんでした</strong><span>${escapeHtml(message)}</span></div>`,
  );
}

function addParsedMidis(midis, mergeMode) {
  const baseStartMs = mergeMode === "append" ? state.midi.durationMs + 250 : 0;
  const addedMidi = mergeMidiDocuments(midis, {
    mode: mergeMode,
    baseStartMs,
    trackIndexOffset: state.midi.tracks.length,
  });
  const addedInitial = createInitialParts(addedMidi);
  const partPrefix = `import-${Date.now().toString(36)}`;
  const partIdMap = new Map();
  const addedParts = addedInitial.parts.map((part, index) => {
    const id = `${partPrefix}-${index + 1}`;
    partIdMap.set(part.id, id);
    return { ...part, id };
  });
  const rows = [
    ...state.midi.notes.map((note) => ({ note, partId: state.assignments[note.id] })),
    ...addedMidi.notes.map((note) => ({ note, partId: partIdMap.get(addedInitial.assignments[note.id]) })),
  ].sort((a, b) => a.note.startMs - b.note.startMs || a.note.order - b.note.order);
  rows.forEach((row, id) => { row.note.id = id; });
  state.midi = {
    ...state.midi,
    sourceName: `${(state.midi.sources?.length || 1) + midis.length} MIDI merged.mid`,
    notes: rows.map((row) => row.note),
    tracks: [...state.midi.tracks, ...addedMidi.tracks],
    durationMs: rows.reduce(
      (maximum, row) => Math.max(maximum, row.note.startMs + Math.max(0, row.note.durationMs || 0)),
      0,
    ),
    tempos: mergeMode === "append" ? [...state.midi.tempos, ...addedMidi.tempos].sort((a, b) => a.timeMs - b.timeMs) : state.midi.tempos,
    warnings: sumWarnings(state.midi.warnings, addedMidi.warnings),
    sources: [...(state.midi.sources || [{ fileName: state.midi.sourceName }]), ...(addedMidi.sources || [])],
  };
  state.parts = [...state.parts, ...addedParts];
  state.assignments = rows.map((row) => row.partId);
  state.selectedIds = new Set();
  state.activePartId = addedParts[0]?.id || state.activePartId;
  updateSourcePitchRange();
  state.view.endMs = Math.max(state.view.endMs, state.midi.durationMs);
  recalculate(false);
  renderEditor();
  scheduleSessionSave();
  showEditorNotice(`${midis.length}個のMIDIを${mergeMode === "append" ? "曲の末尾へ連結" : "同じ開始位置へ重ねて"}追加しました。`);
}

function restoreSession(snapshot, notify = true) {
  if (!Array.isArray(snapshot?.midi?.notes) || snapshot.assignments?.length !== snapshot.midi.notes.length) {
    renderUpload();
    return;
  }
  state.midi = snapshot.midi;
  state.parts = snapshot.parts || [];
  state.assignments = snapshot.assignments || [];
  state.selectedIds = new Set((snapshot.selectedIds || []).filter((id) => state.midi.notes[id]));
  state.settings = { ...DEFAULT_SETTINGS, ...(snapshot.settings || {}) };
  state.title = snapshot.title || state.midi.title;
  state.view = snapshot.view || { startMs: 0, endMs: Math.max(1000, state.midi.durationMs), minPitch: 36, maxPitch: 84 };
  state.sourcePitchRange = snapshot.sourcePitchRange || { min: 36, max: 84 };
  state.editorMode = snapshot.editorMode === "part" ? "part" : "all";
  state.activePartId = snapshot.activePartId || state.parts[0]?.id || null;
  state.gridSubdivision = clampNumber(snapshot.gridSubdivision, 1, 16, 4);
  state.snapToGrid = snapshot.snapToGrid !== false;
  state.horizontalZoom = clampNumber(snapshot.horizontalZoom, 1, 32, 1);
  state.verticalZoom = clampNumber(snapshot.verticalZoom, 1, 8, 1);
  state.previewPositionMs = clampNumber(snapshot.previewPositionMs, 0, state.midi.durationMs, 0);
  state.sessionSavingEnabled = true;
  state.suspendedSession = null;
  updateSourcePitchRange();
  recalculate(false);
  renderEditor();
  if (notify) showEditorNotice("このブラウザに保存されていた前回の編集状態を復元しました。");
}

function createSessionSnapshot() {
  if (!state.midi) return null;
  return {
    midi: state.midi,
    parts: state.parts,
    assignments: state.assignments,
    selectedIds: [...state.selectedIds],
    settings: state.settings,
    title: state.title,
    view: state.view,
    sourcePitchRange: state.sourcePitchRange,
    editorMode: state.editorMode,
    activePartId: state.activePartId,
    gridSubdivision: state.gridSubdivision,
    snapToGrid: state.snapToGrid,
    horizontalZoom: state.horizontalZoom,
    verticalZoom: state.verticalZoom,
    previewPositionMs: state.previewPositionMs,
  };
}

function scheduleSessionSave() {
  if (!state.midi || !state.sessionSavingEnabled) return;
  const status = document.querySelector("#session-status");
  if (status) status.textContent = "保存中…";
  window.clearTimeout(state.sessionTimer);
  state.sessionTimer = window.setTimeout(async () => {
    try {
      await saveLatestSession(createSessionSnapshot());
      const currentStatus = document.querySelector("#session-status");
      if (currentStatus) currentStatus.textContent = "このブラウザに保存済み";
    } catch (error) {
      const currentStatus = document.querySelector("#session-status");
      if (currentStatus) currentStatus.textContent = "保存できませんでした";
      console.warn("OMMT session save failed", error);
    }
  }, 1200);
}

function showEditorNotice(message, type = "success") {
  document.querySelector(".editor-notice")?.remove();
  const summary = document.querySelector(".file-summary");
  if (!summary) return;
  summary.insertAdjacentHTML("afterend", `<div class="editor-notice ${type === "error" ? "is-error" : ""}" role="status">${escapeHtml(message)}</div>`);
  window.setTimeout(() => document.querySelector(".editor-notice")?.remove(), 6000);
}

function sumWarnings(first = {}, second = {}) {
  const result = {};
  for (const key of new Set([...Object.keys(first), ...Object.keys(second)])) result[key] = Number(first[key] || 0) + Number(second[key] || 0);
  return result;
}

function initializeEditor(midi) {
  state.midi = midi;
  state.suspendedSession = null;
  const initial = createInitialParts(midi);
  state.parts = initial.parts;
  state.assignments = initial.assignments;
  state.selectedIds = new Set();
  state.settings = { ...DEFAULT_SETTINGS };
  state.title = midi.title;
  state.editorMode = "all";
  state.activePartId = state.parts[0]?.id || null;
  state.gridSubdivision = 4;
  state.snapToGrid = true;
  state.horizontalZoom = 1;
  state.verticalZoom = 1;
  state.previewPositionMs = 0;
  state.sessionSavingEnabled = true;
  let minPitch = 127;
  let maxPitch = 0;
  for (const note of midi.notes) {
    minPitch = Math.min(minPitch, note.midi);
    maxPitch = Math.max(maxPitch, note.midi);
  }
  if (midi.notes.length === 0) {
    minPitch = 36;
    maxPitch = 84;
  }
  state.view = {
    startMs: 0,
    endMs: Math.max(1000, midi.durationMs),
    minPitch: Math.max(0, minPitch - 2),
    maxPitch: Math.min(127, maxPitch + 2),
  };
  state.sourcePitchRange = { min: state.view.minPitch, max: state.view.maxPitch };
  recalculate(false);
  renderEditor();
  scheduleSessionSave();
}

function updateSourcePitchRange() {
  if (!state.midi?.notes?.length) {
    state.sourcePitchRange = { min: 36, max: 84 };
    return;
  }
  let minimum = 127;
  let maximum = 0;
  for (const note of state.midi.notes) {
    minimum = Math.min(minimum, note.midi);
    maximum = Math.max(maximum, note.midi);
  }
  state.sourcePitchRange = { min: Math.max(0, minimum - 2), max: Math.min(127, maximum + 2) };
}

function renderEditor() {
  preview.stop(false);
  const midi = state.midi;
  const recommendedTranspose = recommendGlobalTranspose(midi.notes);
  workspace.innerHTML = `
    <section class="file-summary">
      <div class="file-heading">
        <span class="step-label">LOADED MIDI</span>
        <h2>${escapeHtml(midi.sourceName)}</h2>
        <p>${escapeHtml(midi.title)}</p>
      </div>
      <div class="summary-stats">
        ${summaryStat("曲長", formatTime(midi.durationMs))}
        ${summaryStat("ノート", formatNumber(midi.notes.length))}
        ${summaryStat("元トラック", formatNumber(midi.tracks.length))}
        ${summaryStat("結合MIDI", formatNumber(midi.sources?.length || 1))}
        ${summaryStat("テンポ", `${formatDecimal(midi.tempos[0]?.bpm || 120)} BPM`)}
      </div>
      <div class="file-actions">
        <label>追加方法<select id="merge-mode"><option value="overlay">同じ開始位置へ重ねる</option><option value="append">曲の末尾へ連結</option></select></label>
        <button type="button" id="add-midi" class="secondary-button">MIDIを追加</button>
        <input id="add-midi-input" type="file" accept=".mid,.midi,audio/midi,audio/x-midi" multiple hidden />
        <button type="button" id="replace-file" class="text-button">別のMIDIを開く</button>
        <span id="session-status" class="session-status">このブラウザに自動保存</span>
        <button type="button" id="clear-session" class="text-button danger-text">保存を削除</button>
      </div>
    </section>

    <nav class="stage-nav" aria-label="編集工程">
      <a href="#arrange"><b>01</b><span>音を選ぶ</span></a>
      <a href="#parts"><b>02</b><span>パートを整える</span></a>
      <a href="#export"><b>03</b><span>変換・出力</span></a>
    </nav>

    <section id="arrange" class="editor-section">
      <div class="section-heading">
        <div><span class="step-label">01 · ARRANGE</span><h2>音を選んで、パートに分ける</h2></div>
        <p>ドラッグで範囲選択、クリックで1音選択。Shiftを押すと選択へ追加できます。</p>
      </div>
      <div class="editor-modebar" role="group" aria-label="パート表示モード">
        <button type="button" data-editor-mode="all" class="mode-button ${state.editorMode === "all" ? "is-active" : ""}">全パートを重ねて編集</button>
        <button type="button" data-editor-mode="part" class="mode-button ${state.editorMode === "part" ? "is-active" : ""}">パートごとに編集</button>
        <span>${state.editorMode === "all" ? "すべてのパートを同じグリッドへ表示しています" : "選択したパートのノートだけを表示しています"}</span>
      </div>
      <div id="part-editor-tabs" class="part-editor-tabs" ${state.editorMode === "part" ? "" : "hidden"}>${partEditorTabs()}</div>
      <div class="piano-panel">
        <div class="piano-toolbar">
          <div class="view-fields">
            <label>表示開始 <input id="view-start" type="number" min="0" step="0.1" value="${round(state.view.startMs / 1000, 2)}" /> 秒</label>
            <label>表示終了 <input id="view-end" type="number" min="0" step="0.1" value="${round(state.view.endMs / 1000, 2)}" /> 秒</label>
            <button type="button" id="apply-view" class="compact-button">表示を更新</button>
            <button type="button" id="show-all" class="compact-button">曲全体</button>
          </div>
          <div class="zoom-fields">
            <label>横方向<input id="horizontal-zoom" type="range" min="1" max="32" step="0.25" value="${state.horizontalZoom}" /><output>${formatDecimal(state.horizontalZoom)}×</output></label>
            <label>縦方向<input id="vertical-zoom" type="range" min="1" max="8" step="0.25" value="${state.verticalZoom}" /><output>${formatDecimal(state.verticalZoom)}×</output></label>
            <label>縦位置<input id="pitch-center" type="range" min="0" max="127" step="1" value="${Math.round((state.view.minPitch + state.view.maxPitch) / 2)}" /></label>
          </div>
          <div class="grid-fields">
            <label>時間グリッド<select id="grid-subdivision">
              <option value="1" ${state.gridSubdivision === 1 ? "selected" : ""}>4分音符</option>
              <option value="2" ${state.gridSubdivision === 2 ? "selected" : ""}>8分音符</option>
              <option value="4" ${state.gridSubdivision === 4 ? "selected" : ""}>16分音符</option>
              <option value="8" ${state.gridSubdivision === 8 ? "selected" : ""}>32分音符</option>
            </select></label>
            <label class="snap-check"><input id="snap-to-grid" type="checkbox" ${state.snapToGrid ? "checked" : ""} />範囲をグリッドへ補正</label>
          </div>
          <div class="legend"><span></span>横線は全音階 · 太線と目盛りはMinecraft音域のF♯</div>
        </div>
        <canvas id="piano-roll" class="piano-roll" height="430" aria-label="MIDIピアノロール。ドラッグまたはクリックでノートを選択できます"></canvas>
      </div>

      <div class="selection-grid">
        <section class="selection-card">
          <div class="card-heading"><h3>条件で選択</h3><span>時刻と音域を組み合わせられます</span></div>
          <div class="range-form">
            <label>開始秒<input id="select-start" type="number" min="0" step="0.1" value="${round(state.view.startMs / 1000, 2)}" /></label>
            <label>終了秒<input id="select-end" type="number" min="0" step="0.1" value="${round(state.view.endMs / 1000, 2)}" /></label>
            <label>最低音<input id="select-min-pitch" type="number" min="0" max="127" value="${state.view.minPitch}" /></label>
            <label>最高音<input id="select-max-pitch" type="number" min="0" max="127" value="${state.view.maxPitch}" /></label>
          </div>
          <div class="button-row">
            <button type="button" id="select-range" class="primary-button small">条件内を選択</button>
            <button type="button" id="add-range" class="secondary-button small">選択へ追加</button>
            <button type="button" id="clear-selection" class="text-button">解除</button>
          </div>
        </section>
        <section class="selection-card selected-card">
          <div class="selected-count"><strong id="selected-count">0</strong><span>ノート選択中</span></div>
          <p id="selected-detail">ピアノロールまたは条件を使って音を選んでください。</p>
          <div class="new-part-form">
            <label>新しいパート名<input id="new-part-name" type="text" maxlength="80" value="新しいパート" /></label>
            <label>楽器<select id="new-part-instrument">${instrumentOptions("piano")}</select></label>
          </div>
          <button type="button" id="create-part" class="primary-button" disabled>選択音を新しいパートへ分ける</button>
        </section>
        <section class="selection-card note-edit-card">
          <div class="card-heading"><h3>選択ノートを編集</h3><span>現在の時間グリッド単位で移動できます</span></div>
          <div class="range-form compact-grid">
            <label>時間移動<input id="note-shift-grid" type="number" min="-256" max="256" step="1" value="0" /> マス</label>
            <label>音程移動<input id="note-shift-pitch" type="number" min="-127" max="127" step="1" value="0" /> 半音</label>
          </div>
          <div id="single-note-editor" class="single-note-editor" hidden>
            <label>開始位置<input id="single-note-time" type="number" min="0" step="1" /> ms</label>
            <label>MIDI音程<input id="single-note-pitch" type="number" min="0" max="127" step="1" /></label>
            <button type="button" id="apply-single-note" class="compact-button">1音へ正確に適用</button>
          </div>
          <div class="button-row">
            <button type="button" id="apply-note-shift" class="secondary-button small" disabled>移動を適用</button>
            <button type="button" id="delete-notes" class="text-button danger-text" disabled>選択音を削除</button>
          </div>
        </section>
      </div>
    </section>

    <section id="parts" class="editor-section">
      <div class="section-heading">
        <div><span class="step-label">02 · PARTS</span><h2>出力パートを整える</h2></div>
        <p>MIDIの元トラックに関係なく、各ノートはここで指定した音ブロック楽器へ変換されます。</p>
      </div>
      <div id="part-list" class="part-list"></div>
    </section>

    <section id="export" class="editor-section export-section">
      <div class="section-heading">
        <div><span class="step-label">03 · TRANSLATE</span><h2>音ブロックへ変換する</h2></div>
        <p>出力後の曲名や参考URL、公開設定はMinecraft内のOyasaiMusicで変更できます。</p>
      </div>
      <div class="export-grid">
        <section class="settings-card">
          <h3>変換設定</h3>
          <label class="field-row"><span>曲名</span><input id="song-title" type="text" maxlength="120" value="${escapeAttribute(state.title)}" /></label>
          <label class="field-row"><span>全体移調</span><span class="inline-field"><input id="global-transpose" type="number" min="-48" max="48" value="${state.settings.globalTranspose}" /> 半音</span></label>
          <button type="button" id="use-recommended-transpose" class="recommend-button">推奨 ${signed(recommendedTranspose)} 半音を使う</button>
          <label class="field-row"><span>音域外</span><select id="pitch-policy">
            <option value="fold" ${state.settings.pitchPolicy === "fold" ? "selected" : ""}>オクターブ移動</option>
            <option value="drop" ${state.settings.pitchPolicy === "drop" ? "selected" : ""}>削除</option>
            <option value="clamp" ${state.settings.pitchPolicy === "clamp" ? "selected" : ""}>端の音へ固定</option>
          </select></label>
          ${toggleRow("remove-leading", "先頭の無音を削除", state.settings.removeLeadingSilence)}
          ${toggleRow("preserve-pan", "Panを保持", state.settings.preservePan)}
          ${toggleRow("deduplicate", "完全な重複音を整理", state.settings.deduplicate)}
        </section>
        <section class="result-card">
          <div class="result-heading"><h3>変換結果</h3><span id="conversion-status">計算済み</span></div>
          <div id="result-metrics" class="result-metrics"></div>
          <div id="result-warnings" class="result-warnings"></div>
          <div class="preview-controls">
            <button type="button" id="preview-button" class="secondary-button">▶ 変換後を試聴</button>
            <div class="preview-readout">
              <input id="preview-seek" class="preview-seek" type="range" min="0" max="${Math.max(0, state.conversion?.metrics.durationMs || 0)}" step="1" value="${Math.min(state.previewPositionMs, state.conversion?.metrics.durationMs || 0)}" aria-label="試聴開始位置" />
              <progress id="preview-progress" max="100" value="0"></progress><span id="preview-time">${formatTime(state.previewPositionMs)} / ${formatTime(state.conversion?.metrics.durationMs || 0)}</span>
            </div>
          </div>
          <button type="button" id="download-button" class="download-button">.oyasai をダウンロード <span>↓</span></button>
        </section>
      </div>

      <details class="minecraft-steps">
        <summary>Minecraftへ取り込む手順</summary>
        <ol>
          <li>ダウンロードしたファイルをサーバーの<code>plugins/OyasaiMusic/import</code>へ入れます。</li>
          <li>Minecraft内で<code>/mm import &lt;ファイル名&gt;</code>を実行します。</li>
          <li>曲は非公開の下書きとして登録されます。曲名、参考URL、公開状態をゲーム内で設定します。</li>
        </ol>
      </details>
    </section>
  `;

  bindEditorEvents(recommendedTranspose);
  renderParts();
  renderConversion();
  updateSelectionUi();
  initializeRoll();
}

function bindEditorEvents(recommendedTranspose) {
  document.querySelector("#replace-file").addEventListener("click", renderUpload);
  const addMidiInput = document.querySelector("#add-midi-input");
  document.querySelector("#add-midi").addEventListener("click", () => addMidiInput.click());
  addMidiInput.addEventListener("change", () => {
    if (!addMidiInput.files?.length) return;
    loadFiles([...addMidiInput.files], {
      addToCurrent: true,
      mergeMode: document.querySelector("#merge-mode").value,
    });
  });
  document.querySelector("#clear-session").addEventListener("click", async () => {
    window.clearTimeout(state.sessionTimer);
    state.sessionSavingEnabled = false;
    await clearLatestSession().catch(() => {});
    document.querySelector("#session-status").textContent = "自動保存オフ";
    document.querySelector("#clear-session").disabled = true;
    showEditorNotice("このブラウザに保存した編集状態を削除し、自動保存を停止しました。新しいMIDIを開くと再開します。");
  });
  document.querySelectorAll("[data-editor-mode]").forEach((button) => {
    button.addEventListener("click", () => setEditorMode(button.dataset.editorMode));
  });
  bindPartEditorTabs();
  document.querySelector("#apply-view").addEventListener("click", applyViewInputs);
  document.querySelector("#show-all").addEventListener("click", () => {
    state.view.startMs = 0;
    state.view.endMs = Math.max(1000, state.midi.durationMs);
    state.view.minPitch = state.sourcePitchRange.min;
    state.view.maxPitch = state.sourcePitchRange.max;
    state.horizontalZoom = 1;
    state.verticalZoom = 1;
    document.querySelector("#view-start").value = 0;
    document.querySelector("#view-end").value = round(state.view.endMs / 1000, 2);
    document.querySelector("#horizontal-zoom").value = 1;
    document.querySelector("#vertical-zoom").value = 1;
    document.querySelector("#horizontal-zoom").nextElementSibling.textContent = "1×";
    document.querySelector("#vertical-zoom").nextElementSibling.textContent = "1×";
    refreshRoll();
    scheduleSessionSave();
  });
  document.querySelector("#horizontal-zoom").addEventListener("input", (event) => applyHorizontalZoom(event.target));
  document.querySelector("#vertical-zoom").addEventListener("input", (event) => applyVerticalZoom(event.target));
  document.querySelector("#pitch-center").addEventListener("input", (event) => applyVerticalZoom(document.querySelector("#vertical-zoom"), Number(event.target.value)));
  document.querySelector("#grid-subdivision").addEventListener("change", (event) => {
    state.gridSubdivision = Number(event.target.value);
    refreshRoll();
    scheduleSessionSave();
  });
  document.querySelector("#snap-to-grid").addEventListener("change", (event) => {
    state.snapToGrid = event.target.checked;
    refreshRoll();
    scheduleSessionSave();
  });
  document.querySelector("#select-range").addEventListener("click", () => selectByInputs(false));
  document.querySelector("#add-range").addEventListener("click", () => selectByInputs(true));
  document.querySelector("#clear-selection").addEventListener("click", () => setSelection(new Set()));
  document.querySelector("#create-part").addEventListener("click", createPartFromSelection);
  document.querySelector("#apply-note-shift").addEventListener("click", applySelectedNoteShift);
  document.querySelector("#apply-single-note").addEventListener("click", applySingleNoteEdit);
  document.querySelector("#delete-notes").addEventListener("click", deleteSelectedNotes);
  document.querySelector("#song-title").addEventListener("input", (event) => {
    state.title = event.target.value;
    scheduleSessionSave();
  });
  document.querySelector("#global-transpose").addEventListener("change", (event) => {
    state.settings.globalTranspose = clampNumber(event.target.value, -48, 48, 0);
    event.target.value = state.settings.globalTranspose;
    scheduleRecalculation();
  });
  document.querySelector("#use-recommended-transpose").addEventListener("click", () => {
    state.settings.globalTranspose = recommendedTranspose;
    document.querySelector("#global-transpose").value = recommendedTranspose;
    scheduleRecalculation();
  });
  document.querySelector("#pitch-policy").addEventListener("change", (event) => {
    state.settings.pitchPolicy = event.target.value;
    scheduleRecalculation();
  });
  for (const [id, key] of [
    ["remove-leading", "removeLeadingSilence"],
    ["preserve-pan", "preservePan"],
    ["deduplicate", "deduplicate"],
  ]) {
    document.querySelector(`#${id}`).addEventListener("change", (event) => {
      state.settings[key] = event.target.checked;
      scheduleRecalculation();
    });
  }
  document.querySelector("#preview-button").addEventListener("click", togglePreview);
  const seek = document.querySelector("#preview-seek");
  seek.addEventListener("input", (event) => setPreviewPosition(Number(event.target.value)));
  seek.addEventListener("change", async (event) => {
    setPreviewPosition(Number(event.target.value));
    if (preview.playing) await preview.play(state.conversion.notes, state.previewPositionMs);
  });
  document.querySelector("#download-button").addEventListener("click", downloadPackage);
}

function initializeRoll() {
  const canvas = document.querySelector("#piano-roll");
  state.roll = new PianoRoll(canvas, {
    onSelectionChange: setSelection,
    getSelected: () => state.selectedIds,
    getColor(noteId) {
      const part = state.parts.find((candidate) => candidate.id === state.assignments[noteId]);
      return part?.color || "#777";
    },
  });
  refreshRoll();
  state.roll.setPlayhead(state.previewPositionMs);
}

function refreshRoll() {
  if (!state.roll) return;
  const gridTimes = buildTimeGrid({
    tempos: state.midi.tempos,
    ppq: state.midi.ppq,
    startMs: state.view.startMs,
    endMs: state.view.endMs,
    subdivision: state.gridSubdivision,
  });
  state.roll.setData(editableNotes(), state.view, { gridTimes, snapToGrid: state.snapToGrid });
  state.roll.setPlayhead(state.previewPositionMs);
}

function editableNotes() {
  if (state.editorMode !== "part" || !state.activePartId) return state.midi.notes;
  return state.midi.notes.filter((note) => state.assignments[note.id] === state.activePartId);
}

function applyViewInputs() {
  const start = clampNumber(document.querySelector("#view-start").value, 0, state.midi.durationMs / 1000, 0);
  const end = clampNumber(document.querySelector("#view-end").value, start + 0.01, Math.max(start + 0.01, state.midi.durationMs / 1000), state.midi.durationMs / 1000);
  state.view.startMs = start * 1000;
  state.view.endMs = end * 1000;
  state.horizontalZoom = Math.max(1, Math.min(32, Math.max(1000, state.midi.durationMs) / Math.max(1, state.view.endMs - state.view.startMs)));
  document.querySelector("#view-start").value = round(start, 2);
  document.querySelector("#view-end").value = round(end, 2);
  document.querySelector("#horizontal-zoom").value = state.horizontalZoom;
  document.querySelector("#horizontal-zoom").nextElementSibling.textContent = `${formatDecimal(state.horizontalZoom)}×`;
  refreshRoll();
  scheduleSessionSave();
}

function applyHorizontalZoom(input) {
  state.horizontalZoom = clampNumber(input.value, 1, 32, 1);
  input.nextElementSibling.textContent = `${formatDecimal(state.horizontalZoom)}×`;
  const fullDuration = Math.max(1000, state.midi.durationMs);
  const visibleDuration = Math.max(100, fullDuration / state.horizontalZoom);
  const currentCenter = (state.view.startMs + state.view.endMs) / 2;
  const maximumStart = Math.max(0, fullDuration - visibleDuration);
  state.view.startMs = Math.max(0, Math.min(maximumStart, currentCenter - visibleDuration / 2));
  state.view.endMs = Math.min(fullDuration, state.view.startMs + visibleDuration);
  document.querySelector("#view-start").value = round(state.view.startMs / 1000, 3);
  document.querySelector("#view-end").value = round(state.view.endMs / 1000, 3);
  refreshRoll();
  scheduleSessionSave();
}

function applyVerticalZoom(input, requestedCenter = null) {
  state.verticalZoom = clampNumber(input.value, 1, 8, 1);
  input.nextElementSibling.textContent = `${formatDecimal(state.verticalZoom)}×`;
  const fullMinimum = state.sourcePitchRange.min;
  const fullMaximum = state.sourcePitchRange.max;
  const fullSpan = Math.max(1, fullMaximum - fullMinimum + 1);
  const visibleSpan = Math.max(1, Math.min(fullSpan, Math.round(fullSpan / state.verticalZoom)));
  const centerControl = document.querySelector("#pitch-center");
  const center = requestedCenter ?? (Number(centerControl.value) || Math.round((state.view.minPitch + state.view.maxPitch) / 2));
  let minimum = Math.round(center - visibleSpan / 2);
  minimum = Math.max(fullMinimum, Math.min(fullMaximum - visibleSpan + 1, minimum));
  state.view.minPitch = Math.max(0, minimum);
  state.view.maxPitch = Math.min(127, state.view.minPitch + visibleSpan - 1);
  centerControl.value = Math.round((state.view.minPitch + state.view.maxPitch) / 2);
  refreshRoll();
  scheduleSessionSave();
}

function setEditorMode(mode) {
  state.editorMode = mode === "part" ? "part" : "all";
  if (!state.parts.some((part) => part.id === state.activePartId)) state.activePartId = state.parts[0]?.id || null;
  state.selectedIds = new Set();
  renderEditor();
  scheduleSessionSave();
}

function setActivePart(partId) {
  if (!state.parts.some((part) => part.id === partId)) return;
  state.activePartId = partId;
  state.editorMode = "part";
  state.selectedIds = new Set();
  renderEditor();
  scheduleSessionSave();
}

function bindPartEditorTabs() {
  document.querySelectorAll("[data-edit-part]").forEach((button) => {
    button.addEventListener("click", () => setActivePart(button.dataset.editPart));
  });
}

function renderPartTabs() {
  const tabs = document.querySelector("#part-editor-tabs");
  if (!tabs) return;
  tabs.hidden = state.editorMode !== "part";
  tabs.innerHTML = partEditorTabs();
  bindPartEditorTabs();
}

function selectByInputs(additive) {
  let startMs = clampNumber(document.querySelector("#select-start").value, 0, Number.MAX_SAFE_INTEGER, 0) * 1000;
  let endMs = clampNumber(document.querySelector("#select-end").value, 0, Number.MAX_SAFE_INTEGER, state.midi.durationMs / 1000) * 1000;
  const minPitch = clampNumber(document.querySelector("#select-min-pitch").value, 0, 127, 0);
  const maxPitch = clampNumber(document.querySelector("#select-max-pitch").value, 0, 127, 127);
  if (state.snapToGrid) {
    const grid = buildTimeGrid({
      tempos: state.midi.tempos,
      ppq: state.midi.ppq,
      startMs: Math.max(0, Math.min(startMs, endMs) - gridStepMsAt(Math.min(startMs, endMs))),
      endMs: Math.max(startMs, endMs) + gridStepMsAt(Math.max(startMs, endMs)),
      subdivision: state.gridSubdivision,
    });
    startMs = nearestGridTime(startMs, grid);
    endMs = nearestGridTime(endMs, grid);
    document.querySelector("#select-start").value = round(startMs / 1000, 3);
    document.querySelector("#select-end").value = round(endMs / 1000, 3);
  }
  const next = additive ? new Set(state.selectedIds) : new Set();
  for (const note of editableNotes()) {
    if (note.startMs >= Math.min(startMs, endMs)
      && note.startMs <= Math.max(startMs, endMs)
      && note.midi >= Math.min(minPitch, maxPitch)
      && note.midi <= Math.max(minPitch, maxPitch)) {
      next.add(note.id);
    }
  }
  setSelection(next);
}

function setSelection(selection) {
  const visibleIds = new Set(editableNotes().map((note) => note.id));
  state.selectedIds = state.editorMode === "part"
    ? new Set([...selection].filter((id) => visibleIds.has(id)))
    : selection;
  updateSelectionUi();
  refreshRoll();
  scheduleSessionSave();
}

function updateSelectionUi() {
  const count = document.querySelector("#selected-count");
  if (!count) return;
  count.textContent = formatNumber(state.selectedIds.size);
  const detail = document.querySelector("#selected-detail");
  const create = document.querySelector("#create-part");
  const applyShift = document.querySelector("#apply-note-shift");
  const deleteNotes = document.querySelector("#delete-notes");
  const singleEditor = document.querySelector("#single-note-editor");
  create.disabled = state.selectedIds.size === 0;
  if (applyShift) applyShift.disabled = state.selectedIds.size === 0;
  if (deleteNotes) deleteNotes.disabled = state.selectedIds.size === 0;
  if (singleEditor) singleEditor.hidden = state.selectedIds.size !== 1;
  if (state.selectedIds.size === 0) {
    detail.textContent = "ピアノロールまたは条件を使って音を選んでください。";
  } else if (state.selectedIds.size === 1) {
    const note = state.midi.notes[[...state.selectedIds][0]];
    detail.textContent = `${midiNoteName(note.midi)} · ${formatTime(note.startMs)} · Velocity ${note.velocity}`;
    document.querySelector("#single-note-time").value = Math.round(note.startMs);
    document.querySelector("#single-note-pitch").value = note.midi;
  } else {
    let min = 127;
    let max = 0;
    let first = Number.POSITIVE_INFINITY;
    let last = 0;
    for (const id of state.selectedIds) {
      const note = state.midi.notes[id];
      min = Math.min(min, note.midi);
      max = Math.max(max, note.midi);
      first = Math.min(first, note.startMs);
      last = Math.max(last, note.startMs);
    }
    detail.textContent = `${midiNoteName(min)}〜${midiNoteName(max)} · ${formatTime(first)}〜${formatTime(last)}`;
  }
}

function createPartFromSelection() {
  const result = splitSelectionIntoPart({
    parts: state.parts,
    assignments: state.assignments,
    selectedIds: state.selectedIds,
    name: document.querySelector("#new-part-name").value,
    instrumentKey: document.querySelector("#new-part-instrument").value,
  });
  if (!result.createdPart) return;
  state.parts = result.parts;
  state.assignments = result.assignments;
  state.activePartId = result.createdPart.id;
  state.selectedIds = new Set();
  renderParts();
  renderPartTabs();
  updateSelectionUi();
  refreshRoll();
  scheduleRecalculation();
  document.querySelector("#parts").scrollIntoView({ behavior: "smooth", block: "start" });
}

function applySelectedNoteShift() {
  if (state.selectedIds.size === 0) return;
  const gridSteps = clampNumber(document.querySelector("#note-shift-grid").value, -256, 256, 0);
  const pitchSteps = clampNumber(document.querySelector("#note-shift-pitch").value, -127, 127, 0);
  if (gridSteps === 0 && pitchSteps === 0) {
    showEditorNotice("時間移動または音程移動を指定してください。", "error");
    return;
  }
  rebuildMidiNotes((note) => ({
    ...note,
    startMs: Math.max(0, note.startMs + gridSteps * gridStepMsAt(note.startMs)),
    midi: Math.max(0, Math.min(127, note.midi + pitchSteps)),
  }));
  showEditorNotice(`${formatNumber(state.selectedIds.size)}音を移動しました。`);
}

function applySingleNoteEdit() {
  if (state.selectedIds.size !== 1) return;
  const timeMs = clampNumber(document.querySelector("#single-note-time").value, 0, Number.MAX_SAFE_INTEGER, 0);
  const midiPitch = clampNumber(document.querySelector("#single-note-pitch").value, 0, 127, 60);
  rebuildMidiNotes((note) => ({ ...note, startMs: timeMs, midi: midiPitch }));
  showEditorNotice(`選択した1音を${Math.round(timeMs)} ms・${midiNoteName(midiPitch)}へ変更しました。`);
}

function deleteSelectedNotes() {
  const count = state.selectedIds.size;
  if (count === 0) return;
  if (!window.confirm(`${formatNumber(count)}音を編集データから削除しますか？`)) return;
  rebuildMidiNotes(() => null);
  showEditorNotice(`${formatNumber(count)}音を削除しました。`);
}

function rebuildMidiNotes(transformSelected) {
  preview.stop();
  const rows = [];
  for (const source of state.midi.notes) {
    const selected = state.selectedIds.has(source.id);
    const note = selected ? transformSelected({ ...source }) : { ...source };
    if (!note) continue;
    rows.push({ note, partId: state.assignments[source.id], selected });
  }
  rows.sort((a, b) => a.note.startMs - b.note.startMs || a.note.order - b.note.order);
  const nextSelection = new Set();
  rows.forEach((row, id) => {
    row.note.id = id;
    row.note.order = id;
    if (row.selected) nextSelection.add(id);
  });
  state.midi.notes = rows.map((row) => row.note);
  state.assignments = rows.map((row) => row.partId);
  state.selectedIds = nextSelection;
  state.parts = deleteEmptyParts(state.parts, state.assignments);
  if (!state.parts.some((part) => part.id === state.activePartId)) state.activePartId = state.parts[0]?.id || null;
  state.midi.durationMs = state.midi.notes.reduce(
    (maximum, note) => Math.max(maximum, note.startMs + Math.max(0, note.durationMs || 0)),
    0,
  );
  const trackCounts = new Map();
  for (const note of state.midi.notes) trackCounts.set(note.trackIndex, (trackCounts.get(note.trackIndex) || 0) + 1);
  state.midi.tracks = state.midi.tracks.map((track) => ({ ...track, noteCount: trackCounts.get(track.index) || 0 }));
  state.previewPositionMs = Math.min(state.previewPositionMs, state.midi.durationMs);
  updateSourcePitchRange();
  recalculate(false);
  renderEditor();
  scheduleSessionSave();
}

function gridStepMsAt(timeMs) {
  let tempo = state.midi.tempos?.[0] || { bpm: 120 };
  for (const candidate of state.midi.tempos || []) {
    if (candidate.timeMs > timeMs) break;
    tempo = candidate;
  }
  return 60_000 / Math.max(1, tempo.bpm || 120) / Math.max(1, state.gridSubdivision);
}

function renderParts() {
  const partList = document.querySelector("#part-list");
  if (!partList) return;
  const counts = new Map();
  for (const partId of state.assignments) counts.set(partId, (counts.get(partId) || 0) + 1);
  partList.innerHTML = state.parts.map((part, index) => `
    <article class="part-card ${part.muted ? "is-muted" : ""}" data-part-id="${escapeAttribute(part.id)}">
      <span class="part-color" style="--part-color:${part.color}"></span>
      <div class="part-index">${String(index + 1).padStart(2, "0")}</div>
      <div class="part-main">
        <input class="part-name" data-action="name" type="text" maxlength="80" value="${escapeAttribute(part.name)}" aria-label="パート名" />
        <span>${formatNumber(counts.get(part.id) || 0)} ノート</span>
      </div>
      <label class="part-control"><span>音ブロック楽器</span><select data-action="instrument">${instrumentOptions(part.instrumentKey)}</select></label>
      <label class="part-control"><span>移調</span><span class="inline-field"><input data-action="transpose" type="number" min="-48" max="48" value="${part.transpose}" /> 半音</span></label>
      <label class="part-control"><span>音量</span><span class="inline-field"><input data-action="volume" type="number" min="0" max="200" value="${part.volume}" /> %</span></label>
      <label class="part-check"><input data-action="percussion" type="checkbox" ${part.percussion ? "checked" : ""} /><span>打楽器マップ</span></label>
      <label class="part-check"><input data-action="mute" type="checkbox" ${part.muted ? "checked" : ""} /><span>ミュート</span></label>
      <button type="button" data-action="edit-part" class="compact-button ${state.editorMode === "part" && state.activePartId === part.id ? "is-active" : ""}">このパートを編集</button>
      <button type="button" data-action="move-selection" class="compact-button" ${state.selectedIds.size === 0 ? "disabled" : ""}>選択音をここへ</button>
    </article>
  `).join("");

  partList.querySelectorAll(".part-card").forEach((card) => {
    const partId = card.dataset.partId;
    card.addEventListener("input", (event) => updatePart(partId, event.target));
    card.addEventListener("change", (event) => updatePart(partId, event.target));
    card.querySelector('[data-action="edit-part"]').addEventListener("click", () => setActivePart(partId));
    card.querySelector('[data-action="move-selection"]').addEventListener("click", () => {
      if (state.selectedIds.size === 0) return;
      state.assignments = moveSelectionToPart(state.assignments, state.selectedIds, partId);
      state.parts = deleteEmptyParts(state.parts, state.assignments);
      if (!state.parts.some((part) => part.id === state.activePartId)) state.activePartId = state.parts[0]?.id || null;
      state.selectedIds = new Set();
      renderParts();
      renderPartTabs();
      updateSelectionUi();
      refreshRoll();
      scheduleRecalculation();
    });
  });
}

function updatePart(partId, target) {
  const action = target.dataset.action;
  if (!action || action === "move-selection" || action === "edit-part") return;
  const part = state.parts.find((candidate) => candidate.id === partId);
  if (!part) return;
  if (action === "name") part.name = target.value.slice(0, 80);
  else if (action === "instrument") part.instrumentKey = target.value;
  else if (action === "transpose") part.transpose = clampNumber(target.value, -48, 48, 0);
  else if (action === "volume") part.volume = clampNumber(target.value, 0, 200, 100);
  else if (action === "mute") part.muted = target.checked;
  else if (action === "percussion") part.percussion = target.checked;
  if (action !== "name") {
    refreshRoll();
    scheduleRecalculation();
  } else {
    renderPartTabs();
    scheduleSessionSave();
  }
}

function scheduleRecalculation() {
  preview.stop();
  const status = document.querySelector("#conversion-status");
  if (status) status.textContent = "再計算待ち";
  window.clearTimeout(state.conversionTimer);
  state.conversionTimer = window.setTimeout(() => {
    recalculate();
    renderConversion();
  }, 120);
  scheduleSessionSave();
}

function recalculate(updateDom = true) {
  state.conversion = convertMidi(state.midi, state.parts, state.assignments, state.settings);
  if (updateDom) renderConversion();
}

function renderConversion() {
  const metrics = state.conversion?.metrics;
  const resultMetrics = document.querySelector("#result-metrics");
  if (!metrics || !resultMetrics) return;
  document.querySelector("#conversion-status").textContent = "計算済み";
  resultMetrics.innerHTML = [
    metric("出力ノート", formatNumber(state.conversion.notes.length)),
    metric("曲長", formatTime(metrics.durationMs)),
    metric("最大和音", `${formatNumber(metrics.maxChord)} 音`),
    metric("最大密度", `${formatNumber(metrics.maxNotesPerSecond)} 音/秒`),
  ].join("");
  const warnings = [];
  if (metrics.folded > 0) warnings.push(`${formatNumber(metrics.folded)}音をオクターブ移動`);
  if (metrics.clamped > 0) warnings.push(`${formatNumber(metrics.clamped)}音を音域端へ固定`);
  if (metrics.dropped.outOfRange > 0) warnings.push(`${formatNumber(metrics.dropped.outOfRange)}音を音域外として削除`);
  if (metrics.dropped.muted > 0) warnings.push(`${formatNumber(metrics.dropped.muted)}音をミュート`);
  if (metrics.dropped.duplicate > 0) warnings.push(`${formatNumber(metrics.dropped.duplicate)}個の重複音を整理`);
  if (metrics.maxChord >= 12) warnings.push(`最大${metrics.maxChord}和音です。クライアント負荷を確認してください`);
  if (state.midi.warnings.pitchBend > 0) warnings.push("ピッチベンドは変換されません");
  if (state.midi.warnings.sysex > 0) warnings.push("SysExは変換されません");
  document.querySelector("#result-warnings").innerHTML = warnings.length
    ? warnings.map((warning) => `<span>${escapeHtml(warning)}</span>`).join("")
    : "<span class=\"is-good\">変換上の大きな注意点はありません</span>";
  document.querySelector("#download-button").disabled = state.conversion.notes.length === 0;
  document.querySelector("#preview-button").disabled = state.conversion.notes.length === 0;
  state.previewPositionMs = Math.min(state.previewPositionMs, metrics.durationMs);
  const seek = document.querySelector("#preview-seek");
  seek.max = Math.max(0, metrics.durationMs);
  seek.value = state.previewPositionMs;
  document.querySelector("#preview-progress").value = metrics.durationMs > 0 ? (state.previewPositionMs / metrics.durationMs) * 100 : 0;
  document.querySelector("#preview-time").textContent = `${formatTime(state.previewPositionMs)} / ${formatTime(metrics.durationMs)}`;
}

async function togglePreview() {
  if (preview.playing) {
    preview.stop();
    updatePreviewButton(false);
    return;
  }
  if (state.previewPositionMs >= state.conversion.metrics.durationMs) setPreviewPosition(0);
  await preview.play(state.conversion.notes, state.previewPositionMs);
  updatePreviewButton(true);
}

function setPreviewPosition(timeMs) {
  const duration = state.conversion?.metrics.durationMs || 0;
  state.previewPositionMs = Math.max(0, Math.min(duration, Number(timeMs) || 0));
  const seek = document.querySelector("#preview-seek");
  const progress = document.querySelector("#preview-progress");
  const time = document.querySelector("#preview-time");
  if (seek) seek.value = state.previewPositionMs;
  if (progress) progress.value = duration > 0 ? (state.previewPositionMs / duration) * 100 : 0;
  if (time) time.textContent = `${formatTime(state.previewPositionMs)} / ${formatTime(duration)}`;
  state.roll?.setPlayhead(state.previewPositionMs);
  scheduleSessionSave();
}

function updatePreviewButton(playing) {
  const button = document.querySelector("#preview-button");
  if (button) button.textContent = playing ? "■ 試聴を停止" : "▶ 変換後を試聴";
}

function downloadPackage() {
  const blob = encodeOyasaiPackage({
    midi: state.midi,
    conversion: state.conversion,
    parts: state.parts,
    settings: state.settings,
    title: state.title,
  });
  const baseName = (state.title || state.midi.title || "ommt-export").trim();
  downloadBlob(blob, `${baseName}.oyasai`);
}

function instrumentOptions(selected) {
  return NOTE_BLOCK_INSTRUMENTS.map((instrument) => `
    <option value="${instrument.key}" ${instrument.key === selected ? "selected" : ""}>
      ${escapeHtml(instrument.label)} · ${escapeHtml(instrument.block)}
    </option>
  `).join("");
}

function partEditorTabs() {
  const counts = new Map();
  for (const partId of state.assignments) counts.set(partId, (counts.get(partId) || 0) + 1);
  if (state.parts.length === 0) return '<span class="empty-part-tabs">編集できるパートがありません</span>';
  return state.parts.map((part) => `
    <button type="button" data-edit-part="${escapeAttribute(part.id)}" class="part-tab ${state.activePartId === part.id ? "is-active" : ""}" style="--part-color:${part.color}">
      <span></span><strong>${escapeHtml(part.name)}</strong><small>${formatNumber(counts.get(part.id) || 0)}音</small>
    </button>
  `).join("");
}

function summaryStat(label, value) {
  return `<div><span>${label}</span><strong>${value}</strong></div>`;
}

function metric(label, value) {
  return `<div><span>${label}</span><strong>${value}</strong></div>`;
}

function toggleRow(id, label, checked) {
  return `<label class="toggle-row"><span>${label}</span><input id="${id}" type="checkbox" ${checked ? "checked" : ""} /><i aria-hidden="true"></i></label>`;
}

function formatNumber(value) {
  return new Intl.NumberFormat("ja-JP").format(Number(value) || 0);
}

function formatDecimal(value) {
  return new Intl.NumberFormat("ja-JP", { maximumFractionDigits: 1 }).format(Number(value) || 0);
}

function formatTime(ms) {
  const total = Math.max(0, Math.floor((Number(ms) || 0) / 1000));
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const seconds = String(total % 60).padStart(2, "0");
  return hours > 0 ? `${hours}:${String(minutes).padStart(2, "0")}:${seconds}` : `${minutes}:${seconds}`;
}

function clampNumber(value, min, max, fallback) {
  const number = Number(value);
  return Number.isFinite(number) ? Math.max(min, Math.min(max, number)) : fallback;
}

function round(value, digits) {
  const scale = 10 ** digits;
  return Math.round(value * scale) / scale;
}

function signed(value) {
  return value > 0 ? `+${value}` : String(value);
}

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

function escapeAttribute(value) {
  return escapeHtml(value).replaceAll("`", "&#096;");
}
