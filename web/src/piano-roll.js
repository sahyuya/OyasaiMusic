import { midiNoteName } from "./instruments.js";

export class PianoRoll {
  constructor(canvas, { onSelectionChange, getColor, getSelected }) {
    this.canvas = canvas;
    this.context = canvas.getContext("2d");
    this.onSelectionChange = onSelectionChange;
    this.getColor = getColor;
    this.getSelected = getSelected;
    this.notes = [];
    this.visibleNotes = [];
    this.view = { startMs: 0, endMs: 1000, minPitch: 36, maxPitch: 84 };
    this.drag = null;
    this.resizeObserver = new ResizeObserver(() => this.draw());
    this.resizeObserver.observe(canvas);
    canvas.addEventListener("pointerdown", (event) => this.pointerDown(event));
    canvas.addEventListener("pointermove", (event) => this.pointerMove(event));
    canvas.addEventListener("pointerup", (event) => this.pointerUp(event));
    canvas.addEventListener("pointercancel", () => { this.drag = null; this.draw(); });
  }

  setData(notes, view) {
    this.notes = notes;
    this.view = { ...this.view, ...view };
    this.draw();
  }

  draw() {
    const rect = this.canvas.getBoundingClientRect();
    if (rect.width <= 0 || rect.height <= 0) return;
    const ratio = Math.min(2, window.devicePixelRatio || 1);
    const width = Math.round(rect.width * ratio);
    const height = Math.round(rect.height * ratio);
    if (this.canvas.width !== width || this.canvas.height !== height) {
      this.canvas.width = width;
      this.canvas.height = height;
    }
    const context = this.context;
    context.setTransform(ratio, 0, 0, ratio, 0, 0);
    context.clearRect(0, 0, rect.width, rect.height);
    context.fillStyle = "#0d100b";
    context.fillRect(0, 0, rect.width, rect.height);

    const plot = { x: 52, y: 12, width: Math.max(1, rect.width - 62), height: Math.max(1, rect.height - 36) };
    this.drawGrid(context, plot);
    this.plot = plot;
    this.visibleNotes = [];
    const selected = this.getSelected();
    const startIndex = lowerBound(this.notes, this.view.startMs);
    const endIndex = upperBound(this.notes, this.view.endMs);
    const visibleTimeCount = Math.max(0, endIndex - startIndex);
    const drawStride = Math.max(1, Math.ceil(visibleTimeCount / 50_000));
    const duration = Math.max(1, this.view.endMs - this.view.startMs);
    const pitchSpan = Math.max(1, this.view.maxPitch - this.view.minPitch + 1);

    for (let index = startIndex; index < endIndex; index += drawStride) {
      const note = this.notes[index];
      if (note.midi < this.view.minPitch || note.midi > this.view.maxPitch) continue;
      const region = this.noteRegion(note, plot, duration, pitchSpan);
      this.visibleNotes.push(region);
      context.fillStyle = this.getColor(note.id);
      context.globalAlpha = selected.has(note.id) ? 1 : 0.72;
      context.fillRect(region.x, region.y, region.width, region.height);
      if (selected.has(note.id)) {
        context.strokeStyle = "#ffffff";
        context.lineWidth = 1.5;
        context.strokeRect(region.x - 0.5, region.y - 0.5, region.width + 1, region.height + 1);
      }
    }
    context.globalAlpha = 1;

    if (drawStride > 1) {
      context.fillStyle = "rgb(13 16 11 / 88%)";
      context.fillRect(plot.x + 8, plot.y + 8, Math.min(310, plot.width - 16), 24);
      context.fillStyle = "#d7e8b2";
      context.font = "11px ui-monospace, monospace";
      context.textAlign = "left";
      context.fillText("表示を軽量化中 · 時間範囲を狭めると全ノート表示", plot.x + 16, plot.y + 24);
    }

    if (this.drag) {
      const area = normalizeRect(this.drag.startX, this.drag.startY, this.drag.currentX, this.drag.currentY);
      context.fillStyle = "rgb(185 231 105 / 14%)";
      context.strokeStyle = "#b9e769";
      context.lineWidth = 1;
      context.setLineDash([5, 4]);
      context.fillRect(area.x, area.y, area.width, area.height);
      context.strokeRect(area.x, area.y, area.width, area.height);
      context.setLineDash([]);
    }

    context.fillStyle = "#8f9687";
    context.font = "11px ui-monospace, monospace";
    context.textAlign = "left";
    context.fillText(formatTime(this.view.startMs), plot.x, rect.height - 8);
    context.textAlign = "right";
    context.fillText(formatTime(this.view.endMs), rect.width - 10, rect.height - 8);
  }

  drawGrid(context, plot) {
    const pitchSpan = Math.max(1, this.view.maxPitch - this.view.minPitch + 1);
    context.lineWidth = 1;
    for (let pitch = this.view.minPitch; pitch <= this.view.maxPitch; pitch += 1) {
      if (pitch % 12 !== 0) continue;
      const y = plot.y + ((this.view.maxPitch - pitch) / pitchSpan) * plot.height;
      context.strokeStyle = "#252a20";
      context.beginPath();
      context.moveTo(plot.x, y);
      context.lineTo(plot.x + plot.width, y);
      context.stroke();
      context.fillStyle = "#858b7d";
      context.font = "10px ui-monospace, monospace";
      context.textAlign = "right";
      context.fillText(midiNoteName(pitch), plot.x - 7, y + 3);
    }
    for (let step = 0; step <= 8; step += 1) {
      const x = plot.x + (step / 8) * plot.width;
      context.strokeStyle = step % 2 === 0 ? "#2a3024" : "#1e231b";
      context.beginPath();
      context.moveTo(x, plot.y);
      context.lineTo(x, plot.y + plot.height);
      context.stroke();
    }
  }

  noteRegion(note, plot, duration, pitchSpan) {
    const x = plot.x + ((note.startMs - this.view.startMs) / duration) * plot.width;
    const noteEnd = note.startMs + Math.max(note.durationMs || duration * 0.005, duration * 0.002);
    const noteWidth = Math.max(2, ((noteEnd - note.startMs) / duration) * plot.width);
    const y = plot.y + ((this.view.maxPitch - note.midi) / pitchSpan) * plot.height;
    const noteHeight = Math.max(2.5, plot.height / pitchSpan - 0.5);
    return { id: note.id, x, y, width: noteWidth, height: noteHeight, midi: note.midi };
  }

  pointerDown(event) {
    const point = this.point(event);
    this.canvas.setPointerCapture(event.pointerId);
    this.drag = {
      startX: point.x,
      startY: point.y,
      currentX: point.x,
      currentY: point.y,
      additive: event.shiftKey || event.ctrlKey || event.metaKey,
    };
  }

  pointerMove(event) {
    if (!this.drag) return;
    const point = this.point(event);
    this.drag.currentX = point.x;
    this.drag.currentY = point.y;
    this.draw();
  }

  pointerUp(event) {
    if (!this.drag) return;
    const point = this.point(event);
    this.drag.currentX = point.x;
    this.drag.currentY = point.y;
    const area = normalizeRect(this.drag.startX, this.drag.startY, point.x, point.y);
    const isClick = area.width < 5 && area.height < 5;
    let ids;
    if (isClick) {
      const hit = [...this.visibleNotes].reverse().find((region) => contains(region, point));
      ids = hit ? [hit.id] : [];
    } else {
      ids = this.notesInArea(area);
    }
    const next = this.drag.additive ? new Set(this.getSelected()) : new Set();
    if (isClick && ids.length === 1 && next.has(ids[0]) && this.drag.additive) next.delete(ids[0]);
    else for (const id of ids) next.add(id);
    this.drag = null;
    this.onSelectionChange(next);
    this.draw();
  }

  point(event) {
    const rect = this.canvas.getBoundingClientRect();
    return { x: event.clientX - rect.left, y: event.clientY - rect.top };
  }

  notesInArea(area) {
    if (!this.plot) return [];
    const duration = Math.max(1, this.view.endMs - this.view.startMs);
    const pitchSpan = Math.max(1, this.view.maxPitch - this.view.minPitch + 1);
    const relativeStart = Math.max(0, Math.min(1, (area.x - this.plot.x) / this.plot.width));
    const relativeEnd = Math.max(0, Math.min(1, (area.x + area.width - this.plot.x) / this.plot.width));
    const startMs = this.view.startMs + Math.min(relativeStart, relativeEnd) * duration;
    const endMs = this.view.startMs + Math.max(relativeStart, relativeEnd) * duration;
    const startIndex = lowerBound(this.notes, startMs);
    const endIndex = upperBound(this.notes, endMs);
    const ids = [];
    for (let index = startIndex; index < endIndex; index += 1) {
      const note = this.notes[index];
      if (note.midi < this.view.minPitch || note.midi > this.view.maxPitch) continue;
      const region = this.noteRegion(note, this.plot, duration, pitchSpan);
      if (intersects(region, area)) ids.push(note.id);
    }
    return ids;
  }
}

function lowerBound(notes, timeMs) {
  let low = 0;
  let high = notes.length;
  while (low < high) {
    const middle = Math.floor((low + high) / 2);
    if (notes[middle].startMs < timeMs) low = middle + 1;
    else high = middle;
  }
  return Math.max(0, low - 1);
}

function upperBound(notes, timeMs) {
  let low = 0;
  let high = notes.length;
  while (low < high) {
    const middle = Math.floor((low + high) / 2);
    if (notes[middle].startMs <= timeMs) low = middle + 1;
    else high = middle;
  }
  return low;
}

function normalizeRect(x1, y1, x2, y2) {
  return { x: Math.min(x1, x2), y: Math.min(y1, y2), width: Math.abs(x2 - x1), height: Math.abs(y2 - y1) };
}

function intersects(a, b) {
  return a.x <= b.x + b.width && a.x + a.width >= b.x && a.y <= b.y + b.height && a.y + a.height >= b.y;
}

function contains(region, point) {
  return point.x >= region.x && point.x <= region.x + region.width && point.y >= region.y && point.y <= region.y + region.height;
}

function formatTime(ms) {
  const totalSeconds = Math.max(0, ms / 1000);
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = Math.floor(totalSeconds % 60).toString().padStart(2, "0");
  return `${minutes}:${seconds}`;
}
