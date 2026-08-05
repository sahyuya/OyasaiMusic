import { midiNoteName } from "./instruments.js";
import { isMinecraftBoundaryPitch } from "./grid.js";

const BLACK_KEYS = new Set([1, 3, 6, 8, 10]);

export class PianoRoll {
  constructor(canvas, {
    onSelectionChange,
    onSeek = () => {},
    onNavigate = () => {},
    onKeyboardPreview = () => {},
    getColor,
    getSelected,
  }) {
    this.canvas = canvas;
    this.context = canvas.getContext("2d");
    this.onSelectionChange = onSelectionChange;
    this.onSeek = onSeek;
    this.onNavigate = onNavigate;
    this.onKeyboardPreview = onKeyboardPreview;
    this.getColor = getColor;
    this.getSelected = getSelected;
    this.notes = [];
    this.ghostNotes = [];
    this.visibleNotes = [];
    this.view = { startMs: 0, endMs: 1000, minPitch: 36, maxPitch: 84 };
    this.gridTimes = [];
    this.rulerMarks = [];
    this.snapToGrid = true;
    this.playheadMs = 0;
    this.drag = null;
    this.keyboardWidth = 76;
    this.rulerHeight = 32;
    this.resizeObserver = new ResizeObserver(() => this.draw());
    this.resizeObserver.observe(canvas);
    canvas.addEventListener("pointerdown", (event) => this.pointerDown(event));
    canvas.addEventListener("pointermove", (event) => this.pointerMove(event));
    canvas.addEventListener("pointerup", (event) => this.pointerUp(event));
    canvas.addEventListener("pointercancel", () => { this.drag = null; this.draw(); });
    canvas.addEventListener("wheel", (event) => this.wheel(event), { passive: false });
    canvas.addEventListener("contextmenu", (event) => event.preventDefault());
  }

  setData(notes, view, {
    gridTimes = [],
    rulerMarks = [],
    ghostNotes = [],
    snapToGrid = true,
  } = {}) {
    this.notes = notes;
    this.ghostNotes = ghostNotes;
    this.view = { ...this.view, ...view };
    this.gridTimes = gridTimes;
    this.rulerMarks = rulerMarks;
    this.snapToGrid = snapToGrid;
    this.draw();
  }

  setPlayhead(timeMs) {
    this.playheadMs = Math.max(0, Number(timeMs) || 0);
    this.draw();
  }

  destroy() {
    this.resizeObserver.disconnect();
    this.canvas.classList.remove("is-panning");
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
    context.fillStyle = "#111318";
    context.fillRect(0, 0, rect.width, rect.height);

    const plot = {
      x: this.keyboardWidth,
      y: this.rulerHeight,
      width: Math.max(1, rect.width - this.keyboardWidth),
      height: Math.max(1, rect.height - this.rulerHeight),
    };
    this.plot = plot;
    this.drawPitchRows(context, plot);
    this.drawTimeGrid(context, plot);
    this.drawKeyboard(context, plot);
    this.drawRuler(context, plot, rect.width);
    this.visibleNotes = [];
    this.drawGhostNotes(context, plot);
    this.drawNotes(context, plot);
    this.drawPlayhead(context, plot);
    this.drawSelection(context);
  }

  drawPitchRows(context, plot) {
    const pitchSpan = Math.max(1, this.view.maxPitch - this.view.minPitch + 1);
    const rowHeight = plot.height / pitchSpan;
    for (let pitch = this.view.minPitch; pitch <= this.view.maxPitch; pitch += 1) {
      const y = this.pitchY(pitch, plot, pitchSpan);
      const pitchClass = ((pitch % 12) + 12) % 12;
      const minecraftBoundary = isMinecraftBoundaryPitch(pitch);
      context.fillStyle = BLACK_KEYS.has(pitchClass) ? "#171a20" : "#20232a";
      context.fillRect(plot.x, y, plot.width, rowHeight + 0.5);
      if (minecraftBoundary) {
        context.fillStyle = "rgb(185 231 105 / 9%)";
        context.fillRect(plot.x, y, plot.width, rowHeight + 0.5);
      }
      context.strokeStyle = minecraftBoundary ? "#728a52" : "#2a2e36";
      context.lineWidth = minecraftBoundary ? 1.4 : 1;
      context.beginPath();
      context.moveTo(plot.x, y);
      context.lineTo(plot.x + plot.width, y);
      context.stroke();
    }
  }

  drawTimeGrid(context, plot) {
    const duration = Math.max(1, this.view.endMs - this.view.startMs);
    for (const mark of this.rulerMarks) {
      const x = this.timeX(mark.timeMs, plot, duration);
      context.strokeStyle = mark.isBar ? "#535a68" : mark.isBeat ? "#393e48" : "#2b2f37";
      context.lineWidth = mark.isBar ? 1.35 : 1;
      context.beginPath();
      context.moveTo(x, plot.y);
      context.lineTo(x, plot.y + plot.height);
      context.stroke();
    }
    if (!this.rulerMarks.length) {
      for (let index = 0; index < this.gridTimes.length; index += 1) {
        const x = this.timeX(this.gridTimes[index], plot, duration);
        context.strokeStyle = index % 4 === 0 ? "#414751" : "#2b2f37";
        context.beginPath();
        context.moveTo(x, plot.y);
        context.lineTo(x, plot.y + plot.height);
        context.stroke();
      }
    }
  }

  drawKeyboard(context, plot) {
    const pitchSpan = Math.max(1, this.view.maxPitch - this.view.minPitch + 1);
    const rowHeight = plot.height / pitchSpan;
    context.fillStyle = "#e9e9e5";
    context.fillRect(0, plot.y, this.keyboardWidth, plot.height);
    for (let pitch = this.view.minPitch; pitch <= this.view.maxPitch; pitch += 1) {
      const y = this.pitchY(pitch, plot, pitchSpan);
      const pitchClass = ((pitch % 12) + 12) % 12;
      if (!BLACK_KEYS.has(pitchClass)) {
        context.fillStyle = pitchClass === 6 ? "#dfecc8" : "#f1f1ed";
        context.fillRect(0, y, this.keyboardWidth, rowHeight + 0.5);
        context.strokeStyle = "#a9abb0";
        context.lineWidth = 0.7;
        context.strokeRect(-0.5, y, this.keyboardWidth + 0.5, rowHeight);
      }
    }
    for (let pitch = this.view.minPitch; pitch <= this.view.maxPitch; pitch += 1) {
      const pitchClass = ((pitch % 12) + 12) % 12;
      if (!BLACK_KEYS.has(pitchClass)) continue;
      const y = this.pitchY(pitch, plot, pitchSpan);
      const isFSharp = pitchClass === 6;
      context.fillStyle = isFSharp ? "#40512b" : "#1d1f25";
      context.fillRect(0, y + 0.7, this.keyboardWidth * 0.64, Math.max(2, rowHeight - 1.4));
      if (isFSharp) {
        context.strokeStyle = "#b9e769";
        context.lineWidth = 1;
        context.strokeRect(0.5, y + 1.2, this.keyboardWidth * 0.64 - 1, Math.max(1, rowHeight - 2.4));
      }
    }
    context.font = `${Math.max(8, Math.min(11, rowHeight * 0.72))}px ui-monospace, monospace`;
    context.textBaseline = "middle";
    context.textAlign = "right";
    for (let pitch = this.view.minPitch; pitch <= this.view.maxPitch; pitch += 1) {
      const pitchClass = ((pitch % 12) + 12) % 12;
      if (pitchClass !== 0 && pitchClass !== 6) continue;
      const y = this.pitchY(pitch, plot, pitchSpan) + rowHeight / 2;
      context.fillStyle = pitchClass === 6 ? "#dfffa7" : "#555a65";
      context.fillText(midiNoteName(pitch), this.keyboardWidth - 5, y);
    }
    context.fillStyle = "#181b21";
    context.fillRect(0, 0, this.keyboardWidth, this.rulerHeight);
    context.fillStyle = "#aeb4c0";
    context.font = "10px ui-monospace, monospace";
    context.textAlign = "center";
    context.textBaseline = "middle";
    context.fillText("KEY", this.keyboardWidth / 2, this.rulerHeight / 2);
  }

  drawRuler(context, plot, width) {
    context.fillStyle = "#20232b";
    context.fillRect(plot.x, 0, plot.width, this.rulerHeight);
    context.strokeStyle = "#3b404b";
    context.beginPath();
    context.moveTo(plot.x, this.rulerHeight - 0.5);
    context.lineTo(width, this.rulerHeight - 0.5);
    context.stroke();
    const duration = Math.max(1, this.view.endMs - this.view.startMs);
    let lastLabelX = -100;
    for (const mark of this.rulerMarks) {
      const x = this.timeX(mark.timeMs, plot, duration);
      const tickHeight = mark.isBar ? 13 : mark.isBeat ? 8 : 5;
      context.strokeStyle = mark.isBar ? "#d4d8df" : "#747b89";
      context.lineWidth = mark.isBar ? 1.2 : 1;
      context.beginPath();
      context.moveTo(x, this.rulerHeight - tickHeight);
      context.lineTo(x, this.rulerHeight);
      context.stroke();
      if (mark.isBar && x - lastLabelX > 32) {
        context.fillStyle = "#d6dae2";
        context.font = "11px ui-monospace, monospace";
        context.textAlign = "left";
        context.textBaseline = "middle";
        context.fillText(String(mark.bar), x + 4, 11);
        lastLabelX = x;
      }
    }
  }

  drawGhostNotes(context, plot) {
    if (!this.ghostNotes.length) return;
    const duration = Math.max(1, this.view.endMs - this.view.startMs);
    const pitchSpan = Math.max(1, this.view.maxPitch - this.view.minPitch + 1);
    const startIndex = lowerBound(this.ghostNotes, this.view.startMs);
    const endIndex = upperBound(this.ghostNotes, this.view.endMs);
    const stride = Math.max(1, Math.ceil(Math.max(0, endIndex - startIndex) / 30_000));
    context.globalAlpha = 0.18;
    for (let index = startIndex; index < endIndex; index += stride) {
      const note = this.ghostNotes[index];
      if (note.midi < this.view.minPitch || note.midi > this.view.maxPitch) continue;
      const region = this.noteRegion(note, plot, duration, pitchSpan);
      context.fillStyle = this.getColor(note.id);
      context.fillRect(region.x, region.y + 1, region.width, Math.max(2, region.height - 2));
    }
    context.globalAlpha = 1;
  }

  drawNotes(context, plot) {
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
      context.globalAlpha = selected.has(note.id) ? 1 : 0.82;
      context.fillRect(region.x, region.y + 1, region.width, Math.max(2, region.height - 2));
      context.strokeStyle = selected.has(note.id) ? "#ffffff" : "rgb(255 255 255 / 22%)";
      context.lineWidth = selected.has(note.id) ? 1.5 : 0.7;
      context.strokeRect(region.x - 0.5, region.y + 0.5, region.width + 1, Math.max(2, region.height - 1));
    }
    context.globalAlpha = 1;
    if (drawStride > 1) {
      context.fillStyle = "rgb(16 18 22 / 90%)";
      context.fillRect(plot.x + 10, plot.y + 10, Math.min(330, plot.width - 20), 26);
      context.fillStyle = "#d7e8b2";
      context.font = "11px ui-monospace, monospace";
      context.textAlign = "left";
      context.textBaseline = "middle";
      context.fillText("軽量表示中 · 横方向へ拡大すると全ノートを表示", plot.x + 18, plot.y + 23);
    }
  }

  drawPlayhead(context, plot) {
    if (this.playheadMs < this.view.startMs || this.playheadMs > this.view.endMs) return;
    const duration = Math.max(1, this.view.endMs - this.view.startMs);
    const x = this.timeX(this.playheadMs, plot, duration);
    context.strokeStyle = "#ff5d57";
    context.lineWidth = 1.5;
    context.beginPath();
    context.moveTo(x, 0);
    context.lineTo(x, plot.y + plot.height);
    context.stroke();
    context.fillStyle = "#ff5d57";
    context.beginPath();
    context.moveTo(x - 5, 0);
    context.lineTo(x + 5, 0);
    context.lineTo(x, 7);
    context.closePath();
    context.fill();
  }

  drawSelection(context) {
    if (!this.drag || this.drag.mode !== "select") return;
    const rawArea = normalizeRect(this.drag.startX, this.drag.startY, this.drag.currentX, this.drag.currentY);
    const area = rawArea.width < 5 && rawArea.height < 5 ? rawArea : this.snapArea(rawArea);
    context.fillStyle = "rgb(97 151 255 / 16%)";
    context.strokeStyle = "#82a9ff";
    context.lineWidth = 1;
    context.setLineDash([5, 4]);
    context.fillRect(area.x, area.y, area.width, area.height);
    context.strokeRect(area.x, area.y, area.width, area.height);
    context.setLineDash([]);
  }

  pointerDown(event) {
    const point = this.point(event);
    this.canvas.setPointerCapture(event.pointerId);
    if (event.button === 1) {
      event.preventDefault();
      this.drag = { mode: "pan", startX: point.x, startY: point.y, currentX: point.x, currentY: point.y };
      this.canvas.classList.add("is-panning");
      return;
    }
    if (event.button !== 0) return;
    if (point.y < this.rulerHeight && point.x >= this.keyboardWidth) {
      const timeMs = this.timeAtX(point.x);
      this.drag = { mode: "scrub", currentX: point.x };
      this.onSeek(timeMs, { play: false, source: "ruler" });
      return;
    }
    if (point.x < this.keyboardWidth && point.y >= this.rulerHeight) {
      this.onKeyboardPreview(this.pitchAtY(point.y));
      this.drag = { mode: "keyboard" };
      return;
    }
    if (!contains(this.plot, point)) return;
    this.drag = {
      mode: "select",
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
    if (this.drag.mode === "pan") {
      const deltaX = point.x - this.drag.currentX;
      const deltaY = point.y - this.drag.currentY;
      this.drag.currentX = point.x;
      this.drag.currentY = point.y;
      this.onNavigate({ type: "pan", deltaX, deltaY, plot: this.plot });
      return;
    }
    if (this.drag.mode === "scrub") {
      this.drag.currentX = point.x;
      this.onSeek(this.timeAtX(point.x), { play: false, source: "ruler" });
      return;
    }
    if (this.drag.mode !== "select") return;
    this.drag.currentX = point.x;
    this.drag.currentY = point.y;
    this.draw();
  }

  pointerUp(event) {
    if (!this.drag) return;
    const point = this.point(event);
    if (this.drag.mode === "pan") {
      this.drag = null;
      this.canvas.classList.remove("is-panning");
      return;
    }
    if (this.drag.mode === "scrub") {
      this.drag = null;
      this.onSeek(this.timeAtX(point.x), { play: true, source: "ruler" });
      return;
    }
    if (this.drag.mode === "keyboard") {
      this.drag = null;
      return;
    }
    if (this.drag.mode !== "select") return;
    this.drag.currentX = point.x;
    this.drag.currentY = point.y;
    const rawArea = normalizeRect(this.drag.startX, this.drag.startY, point.x, point.y);
    const isClick = rawArea.width < 5 && rawArea.height < 5;
    const area = isClick ? rawArea : this.snapArea(rawArea);
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

  wheel(event) {
    if (!this.plot) return;
    event.preventDefault();
    const point = this.point(event);
    const direction = Math.sign(event.deltaY || event.deltaX || 0);
    if (event.ctrlKey || event.metaKey) {
      this.onNavigate({ type: "zoom-time", direction, anchorTime: this.timeAtX(point.x) });
    } else if (event.shiftKey) {
      this.onNavigate({ type: "zoom-pitch", direction, anchorPitch: this.pitchAtY(point.y) });
    } else if (point.y < this.rulerHeight) {
      this.onNavigate({ type: "scroll-time", direction, amount: event.deltaY || event.deltaX });
    } else {
      this.onNavigate({ type: "scroll-pitch", direction, amount: event.deltaY || event.deltaX });
    }
  }

  point(event) {
    const rect = this.canvas.getBoundingClientRect();
    return { x: event.clientX - rect.left, y: event.clientY - rect.top };
  }

  timeAtX(x) {
    const relative = clamp01((x - this.plot.x) / this.plot.width);
    return this.view.startMs + relative * Math.max(1, this.view.endMs - this.view.startMs);
  }

  pitchAtY(y) {
    const pitchSpan = Math.max(1, this.view.maxPitch - this.view.minPitch + 1);
    const relative = clamp01((y - this.plot.y) / this.plot.height);
    const row = Math.min(pitchSpan - 1, Math.floor(relative * pitchSpan));
    return Math.max(0, Math.min(127, this.view.maxPitch - row));
  }

  timeX(timeMs, plot, duration) {
    return plot.x + ((timeMs - this.view.startMs) / duration) * plot.width;
  }

  pitchY(pitch, plot, pitchSpan) {
    return plot.y + ((this.view.maxPitch - pitch) / pitchSpan) * plot.height;
  }

  noteRegion(note, plot, duration, pitchSpan) {
    const x = this.timeX(note.startMs, plot, duration);
    const noteEnd = note.startMs + Math.max(note.durationMs || duration * 0.005, duration * 0.002);
    const noteWidth = Math.max(3, ((noteEnd - note.startMs) / duration) * plot.width);
    const y = this.pitchY(note.midi, plot, pitchSpan);
    const noteHeight = Math.max(3, plot.height / pitchSpan);
    return { id: note.id, x, y, width: noteWidth, height: noteHeight, midi: note.midi };
  }

  notesInArea(area) {
    if (!this.plot) return [];
    const duration = Math.max(1, this.view.endMs - this.view.startMs);
    const pitchSpan = Math.max(1, this.view.maxPitch - this.view.minPitch + 1);
    const relativeStart = clamp01((area.x - this.plot.x) / this.plot.width);
    const relativeEnd = clamp01((area.x + area.width - this.plot.x) / this.plot.width);
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

  snapArea(area) {
    if (!this.snapToGrid || !this.plot) return area;
    const duration = Math.max(1, this.view.endMs - this.view.startMs);
    const pitchSpan = Math.max(1, this.view.maxPitch - this.view.minPitch + 1);
    const leftTime = this.view.startMs + clamp01((area.x - this.plot.x) / this.plot.width) * duration;
    const rightTime = this.view.startMs + clamp01((area.x + area.width - this.plot.x) / this.plot.width) * duration;
    const snappedStart = gridFloor(leftTime, this.gridTimes);
    const snappedEnd = gridCeil(rightTime, this.gridTimes);
    const topPitch = this.view.maxPitch - ((area.y - this.plot.y) / this.plot.height) * pitchSpan;
    const bottomPitch = this.view.maxPitch - ((area.y + area.height - this.plot.y) / this.plot.height) * pitchSpan;
    const highPitch = Math.min(this.view.maxPitch, Math.ceil(topPitch));
    const lowPitch = Math.max(this.view.minPitch - 1, Math.floor(bottomPitch));
    const x1 = this.plot.x + ((snappedStart - this.view.startMs) / duration) * this.plot.width;
    const x2 = this.plot.x + ((snappedEnd - this.view.startMs) / duration) * this.plot.width;
    const y1 = this.plot.y + ((this.view.maxPitch - highPitch) / pitchSpan) * this.plot.height;
    const y2 = this.plot.y + ((this.view.maxPitch - lowPitch) / pitchSpan) * this.plot.height;
    return normalizeRect(x1, y1, x2, y2);
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

function clamp01(value) {
  return Math.max(0, Math.min(1, value));
}

function gridFloor(value, grid) {
  if (!grid?.length) return value;
  let candidate = grid[0];
  for (const point of grid) {
    if (point > value) break;
    candidate = point;
  }
  return candidate;
}

function gridCeil(value, grid) {
  if (!grid?.length) return value;
  for (const point of grid) if (point >= value) return point;
  return grid.at(-1);
}
