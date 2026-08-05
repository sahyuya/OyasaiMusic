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
import { buildRulerGrid, buildTimeGrid, nearestGridTime } from "./grid.js";
import { mergeMidiDocuments } from "./midi-merge.js";
import { encodeOyasaiPackage, downloadBlob } from "./oyasai-format.js";
import { encodeSpongeSchematic, planGridSchematic } from "./schematic.js";
import { PianoRoll } from "./piano-roll.js";
import { AUTOMATION_LANES, AutomationLane } from "./automation-lane.js";
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
  automationRoll: null,
  automation: {},
  automationLane: "velocity",
  automationLaneHeight: 170,
  schematicBpm: 120,
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
  keyHandler: null,
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
    state.roll?.setPlayhead(previewSourceTime());
    updateArrangeTransport();
  },
  onStop() {
    updatePreviewButton(false);
    updateArrangeTransport();
    scheduleSessionSave();
  },
});
const keyboardPreview = new PreviewPlayer();

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
  state.roll?.destroy();
  state.automationRoll?.destroy();
  state.roll = null;
  state.automationRoll = null;
  if (state.keyHandler) document.removeEventListener("keydown", state.keyHandler);
  state.keyHandler = null;
  state.midi = null;
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
  state.automation = snapshot.automation && typeof snapshot.automation === "object" ? snapshot.automation : {};
  state.automationLane = AUTOMATION_LANES[snapshot.automationLane] ? snapshot.automationLane : "velocity";
  state.automationLaneHeight = clampNumber(snapshot.automationLaneHeight, 110, 320, 170);
  state.schematicBpm = clampNumber(snapshot.schematicBpm, 1, 999, Math.round(state.midi.tempos?.[0]?.bpm || 120));
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
    automation: state.automation,
    automationLane: state.automationLane,
    automationLaneHeight: state.automationLaneHeight,
    schematicBpm: state.schematicBpm,
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
  state.automation = {};
  state.automationLane = "velocity";
  state.automationLaneHeight = 170;
  state.schematicBpm = clampNumber(Math.round(midi.tempos?.[0]?.bpm || 120), 1, 999, 120);
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
  state.roll?.destroy();
  state.automationRoll?.destroy();
  state.roll = null;
  state.automationRoll = null;
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
        <div><span class="step-label">01 · ARRANGE</span><h2>MIDIを演奏しながら整える</h2></div>
        <p>上の小節ルーラーをクリックして再生。中央は縦スクロール、ルーラーは横スクロール、Ctrl / Shift＋ホイールで拡大できます。</p>
      </div>
      <div class="editor-modebar" role="group" aria-label="パート表示モード">
        <button type="button" data-editor-mode="all" class="mode-button ${state.editorMode === "all" ? "is-active" : ""}">全パートを重ねて編集</button>
        <button type="button" data-editor-mode="part" class="mode-button ${state.editorMode === "part" ? "is-active" : ""}">パートごとに編集</button>
        <span>${state.editorMode === "all" ? "すべてのパートを同じグリッドへ表示しています" : "選択したパートのノートだけを表示しています"}</span>
      </div>
      <div id="part-editor-tabs" class="part-editor-tabs" ${state.editorMode === "part" ? "" : "hidden"}>${partEditorTabs()}</div>
      <div id="daw-workspace" class="piano-panel daw-workspace" tabindex="0" aria-label="MIDI編集ワークスペース">
        <header class="daw-commandbar">
          <div class="track-strip">
            <span class="track-color" style="--track-color:${escapeAttribute(activePart()?.color || "#7b8b6b")}"></span>
            <span class="track-number">01</span>
            <label><span>編集パート</span><select id="automation-part">${partControlOptions()}</select></label>
          </div>
          <div class="arrange-transport" aria-label="試聴操作">
            <button type="button" id="transport-start" class="transport-button" title="先頭へ移動 (Home)" aria-label="先頭へ移動">|◀</button>
            <button type="button" id="transport-play" class="transport-button is-primary" title="再生・一時停止 (Space)" aria-label="再生">▶</button>
            <button type="button" id="transport-stop" class="transport-button" title="停止 (Escape)" aria-label="停止">■</button>
            <output id="transport-time" class="transport-time">${formatTimeDetailed(state.previewPositionMs)}</output>
            <span class="transport-bpm"><small>BPM</small><b>${formatDecimal(midi.tempos[0]?.bpm || 120)}</b></span>
          </div>
          <div class="edit-tools">
            <button type="button" id="snap-button" class="tool-button ${state.snapToGrid ? "is-active" : ""}" title="グリッド吸着を切り替え (S)" aria-pressed="${state.snapToGrid}">⌁ <span>SNAP</span></button>
            <label class="grid-select"><span>GRID</span><select id="grid-subdivision">
              <option value="1" ${state.gridSubdivision === 1 ? "selected" : ""}>1/4</option>
              <option value="2" ${state.gridSubdivision === 2 ? "selected" : ""}>1/8</option>
              <option value="4" ${state.gridSubdivision === 4 ? "selected" : ""}>1/16</option>
              <option value="8" ${state.gridSubdivision === 8 ? "selected" : ""}>1/32</option>
            </select></label>
            <button type="button" id="fit-width" class="tool-button" title="曲全体を横表示 (W)">↔ <span>FIT</span></button>
            <button type="button" id="fit-height" class="tool-button" title="全音域を縦表示 (H)">↕ <span>FIT</span></button>
          </div>
        </header>
        <div class="roll-viewport">
          <canvas id="piano-roll" class="piano-roll" height="480" aria-label="鍵盤付きMIDIピアノロール。小節ルーラーをクリックするとその位置から再生します"></canvas>
          <input id="roll-vscroll" class="roll-vscroll" type="range" min="0" max="127" step="1" value="${Math.round((state.view.minPitch + state.view.maxPitch) / 2)}" aria-label="ピアノロールの縦位置" />
        </div>
        <div class="automation-header">
          <div class="automation-tabs" role="tablist" aria-label="MIDIコントロールレーン">${automationLaneTabs()}</div>
          <span id="automation-target">${escapeHtml(activePart()?.name || "パートなし")}へ適用 · Ctrl＋クリックで制御点追加 · 右クリックで削除</span>
        </div>
        <div id="automation-splitter" class="automation-splitter" title="ドラッグしてコントロールレーンの高さを変更"></div>
        <canvas id="automation-canvas" class="automation-canvas" height="${state.automationLaneHeight}" style="height:${state.automationLaneHeight}px" aria-label="MIDIコントロールレーン。Ctrlを押しながらクリックすると制御点を追加できます"></canvas>
        <div class="roll-horizontal-bar">
          <span aria-hidden="true">◀</span>
          <input id="roll-hscroll" type="range" min="0" max="1000" step="1" value="${horizontalScrollValue()}" aria-label="ピアノロールの横位置" />
          <span aria-hidden="true">▶</span>
        </div>
        <footer class="daw-shortcuts">
          <span><kbd>Space</kbd> 再生 / 停止</span><span><kbd>Wheel</kbd> 縦移動</span><span><kbd>Ctrl</kbd>＋<kbd>Wheel</kbd> 横拡大</span><span><kbd>Shift</kbd>＋<kbd>Wheel</kbd> 縦拡大</span><span><kbd>Middle Drag</kbd> 自由移動</span><span class="fsharp-key">F♯ Minecraft境界</span>
        </footer>
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

      <section class="schematic-card" aria-labelledby="schematic-title">
        <div class="schematic-heading">
          <div><span class="step-label">FAWE · SPONGE V3</span><h3 id="schematic-title">音ブロックグリッドを.schemで書き出す</h3></div>
          <span class="schematic-badge">/rec we grid 対応</span>
        </div>
        <p class="schematic-intro">
          各音を音ブロックとして配置し、その1ブロック上の看板へ音量・Pan・細かなタイミング差を書き込みます。
          サーバーへ置かず、FAWEのクリップボードから直接録音できます。
        </p>
        <div class="schematic-controls">
          <label class="field-row schematic-bpm-field"><span>グリッドBPM</span><span class="inline-field"><input id="schematic-bpm" type="number" min="1" max="999" step="1" value="${state.schematicBpm}" /> BPM</span></label>
          <button type="button" id="schematic-download" class="schematic-download-button">FAWE .schem をダウンロード <span>↓</span></button>
        </div>
        <div id="schematic-summary" class="schematic-summary" aria-live="polite"></div>
        <div class="sign-format" aria-label="看板フォーマット">
          <span><b>1行目</b> 音量 0〜100</span><span><b>2行目</b> Pan −100〜100</span><span><b>3行目</b> 四分音符に対する時刻差</span><span><b>4行目</b> カスタム音源ID（任意）</span>
        </div>
        <p id="schematic-status" class="schematic-status">書き出し準備ができています。</p>
      </section>

      <details class="minecraft-steps">
        <summary>Minecraftへ取り込む手順</summary>
        <div class="minecraft-methods">
          <div><h4>.oyasaiから登録</h4><ol>
            <li>ファイルを<code>plugins/OyasaiMusic/import</code>へ入れます。</li>
            <li><code>/mm import &lt;ファイル名&gt;</code>を実行します。</li>
            <li>曲名、参考URL、公開状態をゲーム内で設定します。</li>
          </ol></div>
          <div><h4>FAWE .schemから録音</h4><ol>
            <li>ファイルをサーバーで設定されたFAWEのschematicフォルダーへ入れます。</li>
            <li><code>//schem load &lt;ファイル名&gt;</code>でクリップボードへ読み込みます。</li>
            <li>プレイヤーを東向きにして、下に表示された実BPMで<code>/rec we grid &lt;BPM&gt;</code>を実行します。</li>
          </ol></div>
        </div>
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
  document.querySelector("#grid-subdivision").addEventListener("change", (event) => {
    state.gridSubdivision = Number(event.target.value);
    refreshRoll();
    scheduleSessionSave();
  });
  document.querySelector("#snap-button").addEventListener("click", toggleSnap);
  document.querySelector("#fit-width").addEventListener("click", fitViewWidth);
  document.querySelector("#fit-height").addEventListener("click", fitViewHeight);
  document.querySelector("#transport-start").addEventListener("click", () => seekAndMaybePlay(0, false));
  document.querySelector("#transport-play").addEventListener("click", togglePreview);
  document.querySelector("#transport-stop").addEventListener("click", stopPreview);
  document.querySelector("#automation-part").addEventListener("change", (event) => {
    state.activePartId = event.target.value;
    refreshRoll();
    refreshAutomationLane();
    updateAutomationTarget();
    scheduleSessionSave();
  });
  document.querySelectorAll("[data-automation-lane]").forEach((button) => {
    button.addEventListener("click", () => {
      state.automationLane = button.dataset.automationLane;
      document.querySelectorAll("[data-automation-lane]").forEach((candidate) => candidate.classList.toggle("is-active", candidate === button));
      refreshAutomationLane();
      scheduleSessionSave();
    });
  });
  document.querySelector("#roll-vscroll").addEventListener("input", (event) => centerPitchView(Number(event.target.value)));
  document.querySelector("#roll-hscroll").addEventListener("input", (event) => setHorizontalScroll(Number(event.target.value)));
  bindAutomationSplitter();
  bindDawShortcuts();
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
  const schematicBpm = document.querySelector("#schematic-bpm");
  schematicBpm.addEventListener("input", (event) => {
    state.schematicBpm = clampNumber(Math.round(Number(event.target.value)), 1, 999, 120);
    renderSchematicSummary();
    scheduleSessionSave();
  });
  schematicBpm.addEventListener("change", (event) => {
    event.target.value = state.schematicBpm;
  });
  document.querySelector("#schematic-download").addEventListener("click", downloadSchematic);
}

function initializeRoll() {
  const canvas = document.querySelector("#piano-roll");
  state.roll = new PianoRoll(canvas, {
    onSelectionChange: setSelection,
    onSeek: (timeMs, options) => seekAndMaybePlay(timeMs, options.play),
    onNavigate: navigateRoll,
    onKeyboardPreview: previewPianoKey,
    getSelected: () => state.selectedIds,
    getColor(noteId) {
      const part = state.parts.find((candidate) => candidate.id === state.assignments[noteId]);
      return part?.color || "#777";
    },
  });
  state.automationRoll = new AutomationLane(document.querySelector("#automation-canvas"), {
    onChange(points) {
      const partId = state.activePartId;
      if (!partId) return;
      state.automation[partId] ||= {};
      state.automation[partId][state.automationLane] = points;
      scheduleRecalculation();
    },
  });
  refreshRoll();
  state.roll.setPlayhead(previewSourceTime());
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
  const rulerMarks = buildRulerGrid({
    tempos: state.midi.tempos,
    timeSignatures: state.midi.timeSignatures,
    ppq: state.midi.ppq,
    startMs: state.view.startMs,
    endMs: state.view.endMs,
    subdivision: state.gridSubdivision,
  });
  state.roll.setData(editableNotes(), state.view, {
    gridTimes,
    rulerMarks,
    ghostNotes: ghostNotes(),
    snapToGrid: state.snapToGrid,
  });
  state.roll.setPlayhead(previewSourceTime());
  refreshAutomationLane(gridTimes);
  syncRollScrollbars();
}

function editableNotes() {
  if (state.editorMode !== "part" || !state.activePartId) return state.midi.notes;
  return state.midi.notes.filter((note) => state.assignments[note.id] === state.activePartId);
}

function ghostNotes() {
  if (state.editorMode !== "part" || !state.activePartId) return [];
  return state.midi.notes.filter((note) => state.assignments[note.id] !== state.activePartId);
}

function refreshAutomationLane(existingGrid = null) {
  if (!state.automationRoll) return;
  const gridTimes = existingGrid || buildTimeGrid({
    tempos: state.midi.tempos,
    ppq: state.midi.ppq,
    startMs: state.view.startMs,
    endMs: state.view.endMs,
    subdivision: state.gridSubdivision,
  });
  const partId = state.activePartId;
  const notes = partId
    ? state.midi.notes.filter((note) => state.assignments[note.id] === partId)
    : [];
  state.automationRoll.setData({
    notes,
    selectedIds: state.selectedIds,
    view: state.view,
    gridTimes,
    lane: state.automationLane,
    points: state.automation?.[partId]?.[state.automationLane] || [],
  });
}

function navigateRoll(action) {
  if (!state.midi) return;
  const timeSpan = Math.max(1, state.view.endMs - state.view.startMs);
  const pitchSpan = Math.max(1, state.view.maxPitch - state.view.minPitch + 1);
  if (action.type === "zoom-time") {
    zoomTime(action.direction > 0 ? 1.2 : 1 / 1.2, action.anchorTime);
  } else if (action.type === "zoom-pitch") {
    zoomPitch(action.direction > 0 ? 1.18 : 1 / 1.18, action.anchorPitch);
  } else if (action.type === "scroll-time") {
    moveTimeWindow(Math.sign(action.amount || action.direction) * timeSpan * 0.12);
  } else if (action.type === "scroll-pitch") {
    movePitchWindow(-Math.sign(action.amount || action.direction) * Math.max(1, pitchSpan * 0.09));
  } else if (action.type === "pan") {
    moveTimeWindow((-action.deltaX / Math.max(1, action.plot.width)) * timeSpan, false);
    movePitchWindow((action.deltaY / Math.max(1, action.plot.height)) * pitchSpan, false);
    refreshRoll();
    scheduleSessionSave();
  }
}

function zoomTime(scale, anchorTime = null) {
  const fullDuration = Math.max(1000, state.midi.durationMs);
  const oldSpan = Math.max(1, state.view.endMs - state.view.startMs);
  const newSpan = Math.max(80, Math.min(fullDuration, oldSpan * scale));
  const anchor = Math.max(0, Math.min(fullDuration, Number(anchorTime) || (state.view.startMs + oldSpan / 2)));
  const anchorRatio = Math.max(0, Math.min(1, (anchor - state.view.startMs) / oldSpan));
  let start = anchor - anchorRatio * newSpan;
  start = Math.max(0, Math.min(fullDuration - newSpan, start));
  state.view.startMs = start;
  state.view.endMs = start + newSpan;
  state.horizontalZoom = fullDuration / newSpan;
  refreshRoll();
  scheduleSessionSave();
}

function zoomPitch(scale, anchorPitch = null) {
  const fullMinimum = state.sourcePitchRange.min;
  const fullMaximum = state.sourcePitchRange.max;
  const fullSpan = Math.max(1, fullMaximum - fullMinimum + 1);
  const oldSpan = Math.max(1, state.view.maxPitch - state.view.minPitch + 1);
  const newSpan = Math.max(5, Math.min(fullSpan, Math.round(oldSpan * scale)));
  const anchor = Math.max(fullMinimum, Math.min(fullMaximum, Number(anchorPitch) || (state.view.minPitch + state.view.maxPitch) / 2));
  const anchorRatio = Math.max(0, Math.min(1, (anchor - state.view.minPitch) / oldSpan));
  let minimum = Math.round(anchor - anchorRatio * newSpan);
  minimum = Math.max(fullMinimum, Math.min(fullMaximum - newSpan + 1, minimum));
  state.view.minPitch = minimum;
  state.view.maxPitch = minimum + newSpan - 1;
  state.verticalZoom = fullSpan / newSpan;
  refreshRoll();
  scheduleSessionSave();
}

function moveTimeWindow(deltaMs, refresh = true) {
  const fullDuration = Math.max(1000, state.midi.durationMs);
  const span = Math.min(fullDuration, Math.max(1, state.view.endMs - state.view.startMs));
  const start = Math.max(0, Math.min(fullDuration - span, state.view.startMs + deltaMs));
  state.view.startMs = start;
  state.view.endMs = start + span;
  if (refresh) {
    refreshRoll();
    scheduleSessionSave();
  }
}

function movePitchWindow(deltaPitch, refresh = true) {
  const fullMinimum = state.sourcePitchRange.min;
  const fullMaximum = state.sourcePitchRange.max;
  const span = Math.min(fullMaximum - fullMinimum + 1, Math.max(1, state.view.maxPitch - state.view.minPitch + 1));
  let minimum = Math.round(state.view.minPitch + deltaPitch);
  minimum = Math.max(fullMinimum, Math.min(fullMaximum - span + 1, minimum));
  state.view.minPitch = minimum;
  state.view.maxPitch = minimum + span - 1;
  if (refresh) {
    refreshRoll();
    scheduleSessionSave();
  }
}

function centerPitchView(centerPitch) {
  const span = Math.max(1, state.view.maxPitch - state.view.minPitch + 1);
  const fullMinimum = state.sourcePitchRange.min;
  const fullMaximum = state.sourcePitchRange.max;
  let minimum = Math.round(centerPitch - span / 2);
  minimum = Math.max(fullMinimum, Math.min(fullMaximum - span + 1, minimum));
  state.view.minPitch = minimum;
  state.view.maxPitch = minimum + span - 1;
  refreshRoll();
  scheduleSessionSave();
}

function setHorizontalScroll(value) {
  const fullDuration = Math.max(1000, state.midi.durationMs);
  const span = Math.min(fullDuration, Math.max(1, state.view.endMs - state.view.startMs));
  const maximumStart = Math.max(0, fullDuration - span);
  state.view.startMs = (Math.max(0, Math.min(1000, value)) / 1000) * maximumStart;
  state.view.endMs = state.view.startMs + span;
  refreshRoll();
  scheduleSessionSave();
}

function fitViewWidth() {
  state.view.startMs = 0;
  state.view.endMs = Math.max(1000, state.midi.durationMs);
  state.horizontalZoom = 1;
  refreshRoll();
  scheduleSessionSave();
}

function fitViewHeight() {
  state.view.minPitch = state.sourcePitchRange.min;
  state.view.maxPitch = state.sourcePitchRange.max;
  state.verticalZoom = 1;
  refreshRoll();
  scheduleSessionSave();
}

function toggleSnap() {
  state.snapToGrid = !state.snapToGrid;
  const button = document.querySelector("#snap-button");
  button?.classList.toggle("is-active", state.snapToGrid);
  button?.setAttribute("aria-pressed", String(state.snapToGrid));
  refreshRoll();
  scheduleSessionSave();
}

function syncRollScrollbars() {
  const horizontal = document.querySelector("#roll-hscroll");
  const vertical = document.querySelector("#roll-vscroll");
  if (horizontal && !horizontal.matches(":active")) horizontal.value = horizontalScrollValue();
  if (vertical && !vertical.matches(":active")) vertical.value = Math.round((state.view.minPitch + state.view.maxPitch) / 2);
}

function bindAutomationSplitter() {
  const splitter = document.querySelector("#automation-splitter");
  const canvas = document.querySelector("#automation-canvas");
  splitter.addEventListener("pointerdown", (event) => {
    event.preventDefault();
    const startY = event.clientY;
    const startHeight = state.automationLaneHeight;
    const move = (moveEvent) => {
      state.automationLaneHeight = Math.round(Math.max(110, Math.min(320, startHeight + moveEvent.clientY - startY)));
      canvas.style.height = `${state.automationLaneHeight}px`;
      state.automationRoll?.draw();
    };
    const up = () => {
      window.removeEventListener("pointermove", move);
      window.removeEventListener("pointerup", up);
      scheduleSessionSave();
    };
    window.addEventListener("pointermove", move);
    window.addEventListener("pointerup", up, { once: true });
  });
}

function bindDawShortcuts() {
  if (state.keyHandler) document.removeEventListener("keydown", state.keyHandler);
  state.keyHandler = (event) => {
    if (!state.midi || isTextEntry(event.target)) return;
    const key = event.key.toLowerCase();
    if (event.code === "Space") {
      event.preventDefault();
      togglePreview();
    } else if (event.key === "Escape") {
      event.preventDefault();
      stopPreview();
    } else if (event.key === "Home") {
      event.preventDefault();
      seekAndMaybePlay(0, false);
    } else if (event.key === "End") {
      event.preventDefault();
      seekAndMaybePlay(state.midi.durationMs, false);
    } else if ((event.ctrlKey || event.metaKey) && key === "a") {
      event.preventDefault();
      setSelection(new Set(editableNotes().map((note) => note.id)));
    } else if ((event.key === "Delete" || event.key === "Backspace") && state.selectedIds.size > 0) {
      event.preventDefault();
      deleteSelectedNotes();
    } else if (["ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight"].includes(event.key) && state.selectedIds.size > 0) {
      event.preventDefault();
      const pitch = event.key === "ArrowUp" ? (event.shiftKey ? 12 : 1) : event.key === "ArrowDown" ? (event.shiftKey ? -12 : -1) : 0;
      const time = event.key === "ArrowRight" ? 1 : event.key === "ArrowLeft" ? -1 : 0;
      shiftSelectedNotes(time, pitch);
    } else if (key === "s" && !event.ctrlKey && !event.metaKey) {
      event.preventDefault();
      toggleSnap();
    } else if (key === "w" && !event.ctrlKey && !event.metaKey) {
      event.preventDefault();
      fitViewWidth();
    } else if (key === "h" && !event.ctrlKey && !event.metaKey) {
      event.preventDefault();
      fitViewHeight();
    } else if (event.key === "+" || event.key === "=") {
      event.preventDefault();
      zoomTime(1 / 1.2, previewSourceTime());
    } else if (event.key === "-") {
      event.preventDefault();
      zoomTime(1.2, previewSourceTime());
    }
  };
  document.addEventListener("keydown", state.keyHandler);
}

function isTextEntry(target) {
  return target instanceof HTMLElement && (target.matches("input, select, textarea") || target.isContentEditable);
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
  updateAutomationPartControl();
}

function updateAutomationPartControl() {
  const control = document.querySelector("#automation-part");
  if (!control) return;
  control.innerHTML = partControlOptions();
  control.value = state.activePartId || "";
  updateAutomationTarget();
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

function shiftSelectedNotes(gridSteps, pitchSteps) {
  if (state.selectedIds.size === 0) return;
  const count = state.selectedIds.size;
  rebuildMidiNotes((note) => ({
    ...note,
    startMs: Math.max(0, note.startMs + gridSteps * gridStepMsAt(note.startMs)),
    midi: Math.max(0, Math.min(127, note.midi + pitchSteps)),
  }));
  showEditorNotice(`${formatNumber(count)}音を${gridSteps ? `${signed(gridSteps)}マス` : ""}${gridSteps && pitchSteps ? "・" : ""}${pitchSteps ? `${signed(pitchSteps)}半音` : ""}移動しました。`);
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
  state.conversion = convertMidi(state.midi, state.parts, state.assignments, state.settings, state.automation);
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
  renderSchematicSummary();
  state.previewPositionMs = Math.min(state.previewPositionMs, metrics.durationMs);
  const seek = document.querySelector("#preview-seek");
  seek.max = Math.max(0, metrics.durationMs);
  seek.value = state.previewPositionMs;
  document.querySelector("#preview-progress").value = metrics.durationMs > 0 ? (state.previewPositionMs / metrics.durationMs) * 100 : 0;
  document.querySelector("#preview-time").textContent = `${formatTime(state.previewPositionMs)} / ${formatTime(metrics.durationMs)}`;
  state.roll?.setPlayhead(previewSourceTime());
  updateArrangeTransport();
}

async function togglePreview() {
  if (preview.playing) {
    preview.stop();
    updatePreviewButton(false);
    updateArrangeTransport();
    return;
  }
  if (state.previewPositionMs >= state.conversion.metrics.durationMs) setPreviewPosition(0);
  await preview.play(state.conversion.notes, state.previewPositionMs);
  updatePreviewButton(true);
  updateArrangeTransport();
}

function stopPreview() {
  preview.stop();
  updatePreviewButton(false);
  updateArrangeTransport();
}

async function seekAndMaybePlay(sourceTimeMs, shouldPlay = false) {
  const offset = state.conversion?.metrics.offsetMs || 0;
  setPreviewPosition(Math.max(0, Number(sourceTimeMs) - offset));
  if (shouldPlay || preview.playing) {
    await preview.play(state.conversion.notes, state.previewPositionMs);
    updatePreviewButton(true);
    updateArrangeTransport();
  }
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
  state.roll?.setPlayhead(previewSourceTime());
  updateArrangeTransport();
  scheduleSessionSave();
}

function updatePreviewButton(playing) {
  const button = document.querySelector("#preview-button");
  if (button) button.textContent = playing ? "■ 試聴を停止" : "▶ 変換後を試聴";
}

function updateArrangeTransport() {
  const button = document.querySelector("#transport-play");
  const time = document.querySelector("#transport-time");
  if (button) {
    button.textContent = preview.playing ? "❚❚" : "▶";
    button.classList.toggle("is-playing", preview.playing);
    button.setAttribute("aria-label", preview.playing ? "一時停止" : "再生");
  }
  if (time) time.textContent = formatTimeDetailed(previewSourceTime());
}

function previewSourceTime() {
  return Math.max(0, state.previewPositionMs + (state.conversion?.metrics.offsetMs || 0));
}

async function previewPianoKey(midiPitch) {
  let pitch = Math.round(midiPitch) - 54;
  while (pitch < 0) pitch += 12;
  while (pitch > 24) pitch -= 12;
  await keyboardPreview.play([{
    timeMs: 0,
    instrumentKey: activePart()?.instrumentKey || "piano",
    pitch,
    volume: 74,
    pan: 0,
  }]);
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

function renderSchematicSummary() {
  const summary = document.querySelector("#schematic-summary");
  const status = document.querySelector("#schematic-status");
  const button = document.querySelector("#schematic-download");
  if (!summary || !status || !button) return;
  if (!state.conversion?.notes?.length) {
    summary.innerHTML = "";
    status.textContent = "書き出せるノートがありません。";
    button.disabled = true;
    return;
  }
  try {
    const plan = planGridSchematic(state.conversion.notes, state.schematicBpm);
    summary.innerHTML = [
      metric("実BPM", formatNumber(plan.bpm)),
      metric("寸法", `${formatNumber(plan.width)} × ${formatNumber(plan.height)} × ${formatNumber(plan.length)}`),
      metric("配置ブロック", formatNumber(plan.blockCount)),
      metric("生成目安", formatBytes(plan.estimatedBytes)),
    ].join("");
    status.innerHTML = plan.bpmAdjusted
      ? `曲が長いためBPMを <b>${formatNumber(plan.requestedBpm)}</b> から <b>${formatNumber(plan.bpm)}</b> へ自動調整します。東向きで <code>/rec we grid ${plan.bpm}</code> を実行してください。`
      : `東向きで <code>/rec we grid ${plan.bpm}</code> を実行してください。最大タイミング誤差は ${formatNumber(plan.maxTimingErrorMs)} ms です。`;
    button.disabled = false;
  } catch (error) {
    summary.innerHTML = "";
    status.textContent = error instanceof Error ? error.message : ".schemを計画できませんでした。";
    button.disabled = true;
  }
}

async function downloadSchematic() {
  const button = document.querySelector("#schematic-download");
  const status = document.querySelector("#schematic-status");
  if (!button || !status || !state.conversion?.notes?.length) return;
  button.disabled = true;
  status.textContent = "ブラウザ内でSponge Schematic v3を生成・圧縮しています…";
  try {
    const result = await encodeSpongeSchematic({
      notes: state.conversion.notes,
      bpm: state.schematicBpm,
      title: state.title || state.midi.title || "OMMT Grid",
    });
    if (result.plan.bpmAdjusted) {
      state.schematicBpm = result.plan.bpm;
      document.querySelector("#schematic-bpm").value = result.plan.bpm;
      scheduleSessionSave();
    }
    const baseName = safeFileName((state.title || state.midi.title || "ommt-grid").trim());
    downloadBlob(result.blob, `${baseName}.schem`);
    renderSchematicSummary();
    status.innerHTML = `${formatBytes(result.compressedBytes)} の.schemを書き出しました。東向きで <code>/rec we grid ${result.plan.bpm}</code> を実行してください。`;
  } catch (error) {
    status.textContent = error instanceof Error ? error.message : ".schemの生成に失敗しました。";
    button.disabled = false;
  }
}

function instrumentOptions(selected) {
  return NOTE_BLOCK_INSTRUMENTS.map((instrument) => `
    <option value="${instrument.key}" ${instrument.key === selected ? "selected" : ""}>
      ${escapeHtml(instrument.label)} · ${escapeHtml(instrument.block)}
    </option>
  `).join("");
}

function activePart() {
  return state.parts.find((part) => part.id === state.activePartId) || state.parts[0] || null;
}

function partControlOptions() {
  return state.parts.map((part) => `<option value="${escapeAttribute(part.id)}" ${part.id === state.activePartId ? "selected" : ""}>${escapeHtml(part.name)}</option>`).join("");
}

function automationLaneTabs() {
  return Object.entries(AUTOMATION_LANES).map(([key, lane]) => `
    <button type="button" role="tab" data-automation-lane="${key}" class="automation-tab ${state.automationLane === key ? "is-active" : ""}" aria-selected="${state.automationLane === key}">${lane.label}</button>
  `).join("");
}

function updateAutomationTarget() {
  const target = document.querySelector("#automation-target");
  if (target) target.textContent = `${activePart()?.name || "パートなし"}へ適用 · Ctrl＋クリックで制御点追加 · 右クリックで削除`;
}

function horizontalScrollValue() {
  if (!state.midi) return 0;
  const fullDuration = Math.max(1000, state.midi.durationMs);
  const span = Math.min(fullDuration, Math.max(1, state.view.endMs - state.view.startMs));
  const maximumStart = Math.max(0, fullDuration - span);
  return maximumStart > 0 ? Math.round((state.view.startMs / maximumStart) * 1000) : 0;
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

function formatBytes(value) {
  const bytes = Math.max(0, Number(value) || 0);
  if (bytes < 1024) return `${formatNumber(bytes)} B`;
  if (bytes < 1024 ** 2) return `${formatDecimal(bytes / 1024)} KB`;
  if (bytes < 1024 ** 3) return `${formatDecimal(bytes / (1024 ** 2))} MB`;
  return `${formatDecimal(bytes / (1024 ** 3))} GB`;
}

function safeFileName(value) {
  const safe = String(value || "ommt-grid").replace(/[<>:\"/\\|?*\u0000-\u001f]/g, "_").replace(/[. ]+$/g, "");
  return safe || "ommt-grid";
}

function formatTime(ms) {
  const total = Math.max(0, Math.floor((Number(ms) || 0) / 1000));
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const seconds = String(total % 60).padStart(2, "0");
  return hours > 0 ? `${hours}:${String(minutes).padStart(2, "0")}:${seconds}` : `${minutes}:${seconds}`;
}

function formatTimeDetailed(ms) {
  const totalMs = Math.max(0, Math.round(Number(ms) || 0));
  const minutes = Math.floor(totalMs / 60_000);
  const seconds = Math.floor((totalMs % 60_000) / 1000);
  const milliseconds = totalMs % 1000;
  return `${String(minutes).padStart(3, "0")}:${String(seconds).padStart(2, "0")}:${String(milliseconds).padStart(3, "0")}`;
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
