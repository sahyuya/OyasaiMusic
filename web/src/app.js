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
import { encodeOyasaiPackage, downloadBlob } from "./oyasai-format.js";
import { PianoRoll } from "./piano-roll.js";

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
  worker: null,
  conversionTimer: null,
};

const preview = new PreviewPlayer({
  onTime(current, duration) {
    const progress = document.querySelector("#preview-progress");
    const time = document.querySelector("#preview-time");
    if (progress) progress.value = duration > 0 ? Math.min(100, (current / duration) * 100) : 0;
    if (time) time.textContent = `${formatTime(current)} / ${formatTime(duration)}`;
  },
  onStop() {
    updatePreviewButton(false);
  },
});

renderUpload();

function renderUpload() {
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
        <strong>MIDIをドロップ</strong>
        <span>または</span>
        <button type="button" id="choose-file" class="primary-button">ファイルを選ぶ</button>
        <small id="drop-help">ファイルサイズ・曲長・ノート数の固定上限はありません</small>
        <input id="file-input" type="file" accept=".mid,.midi,audio/midi,audio/x-midi" hidden />
      </div>
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
  fileInput.addEventListener("change", () => fileInput.files?.[0] && loadFile(fileInput.files[0]));
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
    const file = event.dataTransfer?.files?.[0];
    if (file) loadFile(file);
  });
}

async function loadFile(file) {
  if (!/\.(mid|midi)$/i.test(file.name)) {
    showUploadError(".mid または .midi ファイルを選んでください。");
    return;
  }
  workspace.innerHTML = `
    <section class="processing-card" aria-labelledby="processing-title">
      <span class="step-label">ANALYZING LOCALLY</span>
      <div class="processing-visual" aria-hidden="true"><i></i><i></i><i></i><i></i><i></i><i></i><i></i></div>
      <h2 id="processing-title">${escapeHtml(file.name)}を解析中</h2>
      <p id="processing-status">MIDIヘッダーを確認しています…</p>
      <button type="button" id="cancel-processing" class="text-button">キャンセル</button>
    </section>
  `;

  try {
    const buffer = await file.arrayBuffer();
    const worker = new Worker(new URL("./midi-worker.js", import.meta.url), { type: "module" });
    state.worker = worker;
    document.querySelector("#cancel-processing").addEventListener("click", () => {
      worker.terminate();
      renderUpload();
    });
    worker.addEventListener("message", (event) => {
      if (event.data.type === "progress") {
        const { track, totalTracks, notes } = event.data.progress;
        const status = document.querySelector("#processing-status");
        if (status) status.textContent = `トラック ${track} / ${totalTracks} · ${formatNumber(notes)}ノートを検出`;
      } else if (event.data.type === "result") {
        worker.terminate();
        state.worker = null;
        initializeEditor(event.data.midi);
      } else if (event.data.type === "error") {
        worker.terminate();
        state.worker = null;
        renderUpload();
        showUploadError(event.data.message);
      }
    });
    worker.addEventListener("error", () => {
      worker.terminate();
      state.worker = null;
      renderUpload();
      showUploadError("解析処理を開始できませんでした。ページを再読み込みしてもう一度お試しください。");
    });
    worker.postMessage({ buffer, sourceName: file.name }, [buffer]);
  } catch (error) {
    renderUpload();
    showUploadError(error instanceof Error ? error.message : "ファイルを読み取れませんでした。");
  }
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

function initializeEditor(midi) {
  state.midi = midi;
  const initial = createInitialParts(midi);
  state.parts = initial.parts;
  state.assignments = initial.assignments;
  state.selectedIds = new Set();
  state.settings = { ...DEFAULT_SETTINGS };
  state.title = midi.title;
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
  recalculate(false);
  renderEditor();
}

function renderEditor() {
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
        ${summaryStat("テンポ", `${formatDecimal(midi.tempos[0]?.bpm || 120)} BPM`)}
      </div>
      <button type="button" id="replace-file" class="secondary-button">別のMIDIを開く</button>
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
      <div class="piano-panel">
        <div class="piano-toolbar">
          <div class="view-fields">
            <label>表示開始 <input id="view-start" type="number" min="0" step="0.1" value="${round(state.view.startMs / 1000, 2)}" /> 秒</label>
            <label>表示終了 <input id="view-end" type="number" min="0" step="0.1" value="${round(state.view.endMs / 1000, 2)}" /> 秒</label>
            <button type="button" id="apply-view" class="compact-button">表示を更新</button>
            <button type="button" id="show-all" class="compact-button">曲全体</button>
          </div>
          <div class="legend"><span></span>色は現在の出力パート</div>
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
            <div class="preview-readout"><progress id="preview-progress" max="100" value="0"></progress><span id="preview-time">0:00 / 0:00</span></div>
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
  document.querySelector("#apply-view").addEventListener("click", applyViewInputs);
  document.querySelector("#show-all").addEventListener("click", () => {
    state.view.startMs = 0;
    state.view.endMs = Math.max(1000, state.midi.durationMs);
    document.querySelector("#view-start").value = 0;
    document.querySelector("#view-end").value = round(state.view.endMs / 1000, 2);
    refreshRoll();
  });
  document.querySelector("#select-range").addEventListener("click", () => selectByInputs(false));
  document.querySelector("#add-range").addEventListener("click", () => selectByInputs(true));
  document.querySelector("#clear-selection").addEventListener("click", () => setSelection(new Set()));
  document.querySelector("#create-part").addEventListener("click", createPartFromSelection);
  document.querySelector("#song-title").addEventListener("input", (event) => { state.title = event.target.value; });
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
}

function refreshRoll() {
  if (!state.roll) return;
  state.roll.setData(state.midi.notes, state.view);
}

function applyViewInputs() {
  const start = clampNumber(document.querySelector("#view-start").value, 0, state.midi.durationMs / 1000, 0);
  const end = clampNumber(document.querySelector("#view-end").value, start + 0.01, Math.max(start + 0.01, state.midi.durationMs / 1000), state.midi.durationMs / 1000);
  state.view.startMs = start * 1000;
  state.view.endMs = end * 1000;
  document.querySelector("#view-start").value = round(start, 2);
  document.querySelector("#view-end").value = round(end, 2);
  refreshRoll();
}

function selectByInputs(additive) {
  const startMs = clampNumber(document.querySelector("#select-start").value, 0, Number.MAX_SAFE_INTEGER, 0) * 1000;
  const endMs = clampNumber(document.querySelector("#select-end").value, 0, Number.MAX_SAFE_INTEGER, state.midi.durationMs / 1000) * 1000;
  const minPitch = clampNumber(document.querySelector("#select-min-pitch").value, 0, 127, 0);
  const maxPitch = clampNumber(document.querySelector("#select-max-pitch").value, 0, 127, 127);
  const next = additive ? new Set(state.selectedIds) : new Set();
  for (const note of state.midi.notes) {
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
  state.selectedIds = selection;
  updateSelectionUi();
  refreshRoll();
}

function updateSelectionUi() {
  const count = document.querySelector("#selected-count");
  if (!count) return;
  count.textContent = formatNumber(state.selectedIds.size);
  const detail = document.querySelector("#selected-detail");
  const create = document.querySelector("#create-part");
  create.disabled = state.selectedIds.size === 0;
  if (state.selectedIds.size === 0) {
    detail.textContent = "ピアノロールまたは条件を使って音を選んでください。";
  } else if (state.selectedIds.size === 1) {
    const note = state.midi.notes[[...state.selectedIds][0]];
    detail.textContent = `${midiNoteName(note.midi)} · ${formatTime(note.startMs)} · Velocity ${note.velocity}`;
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
  state.selectedIds = new Set();
  renderParts();
  updateSelectionUi();
  refreshRoll();
  scheduleRecalculation();
  document.querySelector("#parts").scrollIntoView({ behavior: "smooth", block: "start" });
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
      <button type="button" data-action="move-selection" class="compact-button" ${state.selectedIds.size === 0 ? "disabled" : ""}>選択音をここへ</button>
    </article>
  `).join("");

  partList.querySelectorAll(".part-card").forEach((card) => {
    const partId = card.dataset.partId;
    card.addEventListener("input", (event) => updatePart(partId, event.target));
    card.addEventListener("change", (event) => updatePart(partId, event.target));
    card.querySelector('[data-action="move-selection"]').addEventListener("click", () => {
      if (state.selectedIds.size === 0) return;
      state.assignments = moveSelectionToPart(state.assignments, state.selectedIds, partId);
      state.parts = deleteEmptyParts(state.parts, state.assignments);
      state.selectedIds = new Set();
      renderParts();
      updateSelectionUi();
      refreshRoll();
      scheduleRecalculation();
    });
  });
}

function updatePart(partId, target) {
  const action = target.dataset.action;
  if (!action || action === "move-selection") return;
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
  document.querySelector("#preview-time").textContent = `0:00 / ${formatTime(metrics.durationMs)}`;
}

async function togglePreview() {
  if (preview.playing) {
    preview.stop();
    updatePreviewButton(false);
    return;
  }
  await preview.play(state.conversion.notes);
  updatePreviewButton(true);
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
