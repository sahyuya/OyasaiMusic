import { nearestGridTime } from "./grid.js";

export const AUTOMATION_LANES = Object.freeze({
  velocity: { label: "Velocity", shortLabel: "VEL", minimum: 1, maximum: 127, sourceKey: "velocity" },
  volume: { label: "Volume", shortLabel: "VOL", minimum: 0, maximum: 127, sourceKey: "channelVolume" },
  pan: { label: "Pan", shortLabel: "PAN", minimum: 0, maximum: 127, sourceKey: "pan" },
  expression: { label: "Expression", shortLabel: "EXP", minimum: 0, maximum: 127, sourceKey: "expression" },
});

export class AutomationLane {
  constructor(canvas, { onChange = () => {} } = {}) {
    this.canvas = canvas;
    this.context = canvas.getContext("2d");
    this.onChange = onChange;
    this.keyboardWidth = 76;
    this.notes = [];
    this.selectedIds = new Set();
    this.view = { startMs: 0, endMs: 1000 };
    this.gridTimes = [];
    this.lane = "velocity";
    this.points = [];
    this.regions = [];
    this.drag = null;
    this.resizeObserver = new ResizeObserver(() => this.draw());
    this.resizeObserver.observe(canvas);
    canvas.addEventListener("pointerdown", (event) => this.pointerDown(event));
    canvas.addEventListener("pointermove", (event) => this.pointerMove(event));
    canvas.addEventListener("pointerup", (event) => this.pointerUp(event));
    canvas.addEventListener("pointercancel", () => { this.drag = null; });
    canvas.addEventListener("contextmenu", (event) => this.removeAt(event));
  }

  setData({ notes, selectedIds, view, gridTimes, lane, points }) {
    this.notes = notes || [];
    this.selectedIds = selectedIds || new Set();
    this.view = { ...this.view, ...view };
    this.gridTimes = gridTimes || [];
    this.lane = AUTOMATION_LANES[lane] ? lane : "velocity";
    this.points = (points || []).map((point) => ({ ...point })).sort((a, b) => a.timeMs - b.timeMs);
    this.draw();
  }

  destroy() {
    this.resizeObserver.disconnect();
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
    context.fillStyle = "#17191f";
    context.fillRect(0, 0, rect.width, rect.height);
    const plot = { x: this.keyboardWidth, y: 0, width: Math.max(1, rect.width - this.keyboardWidth), height: rect.height };
    this.plot = plot;
    this.drawScale(context, plot);
    this.drawGrid(context, plot);
    this.drawNoteValues(context, plot);
    this.drawEnvelope(context, plot);
  }

  drawScale(context, plot) {
    const definition = AUTOMATION_LANES[this.lane];
    context.fillStyle = "#20232a";
    context.fillRect(0, 0, this.keyboardWidth, plot.height);
    context.strokeStyle = "#3a3f49";
    context.beginPath();
    context.moveTo(this.keyboardWidth - 0.5, 0);
    context.lineTo(this.keyboardWidth - 0.5, plot.height);
    context.stroke();
    context.fillStyle = "#e1e4e9";
    context.font = "10px ui-monospace, monospace";
    context.textAlign = "center";
    context.textBaseline = "middle";
    context.fillText(definition.shortLabel, this.keyboardWidth / 2, 16);
    context.fillStyle = "#8f96a4";
    context.font = "9px ui-monospace, monospace";
    context.fillText(this.formatValue(definition.maximum), this.keyboardWidth / 2, 34);
    context.fillText(this.formatValue(Math.round((definition.maximum + definition.minimum) / 2)), this.keyboardWidth / 2, plot.height / 2);
    context.fillText(this.formatValue(definition.minimum), this.keyboardWidth / 2, plot.height - 10);
  }

  drawGrid(context, plot) {
    const duration = Math.max(1, this.view.endMs - this.view.startMs);
    context.strokeStyle = "#30343d";
    context.lineWidth = 1;
    for (const value of [0.25, 0.5, 0.75]) {
      const y = plot.y + plot.height * value;
      context.beginPath();
      context.moveTo(plot.x, y);
      context.lineTo(plot.x + plot.width, y);
      context.stroke();
    }
    for (let index = 0; index < this.gridTimes.length; index += 1) {
      const time = this.gridTimes[index];
      if (time < this.view.startMs || time > this.view.endMs) continue;
      const x = plot.x + ((time - this.view.startMs) / duration) * plot.width;
      context.strokeStyle = index % 4 === 0 ? "#3e434e" : "#292d35";
      context.beginPath();
      context.moveTo(x, 0);
      context.lineTo(x, plot.height);
      context.stroke();
    }
  }

  drawNoteValues(context, plot) {
    const definition = AUTOMATION_LANES[this.lane];
    const duration = Math.max(1, this.view.endMs - this.view.startMs);
    const visible = this.notes.filter((note) => note.startMs >= this.view.startMs && note.startMs <= this.view.endMs);
    const stride = Math.max(1, Math.ceil(visible.length / 15_000));
    for (let index = 0; index < visible.length; index += stride) {
      const note = visible[index];
      const raw = Number(note[definition.sourceKey]);
      const value = Number.isFinite(raw) ? raw : definition.maximum;
      const x = plot.x + ((note.startMs - this.view.startMs) / duration) * plot.width;
      const y = this.valueY(value, plot, definition);
      context.strokeStyle = this.selectedIds.has(note.id) ? "rgb(185 231 105 / 78%)" : "rgb(117 144 195 / 35%)";
      context.lineWidth = this.selectedIds.has(note.id) ? 2 : 1;
      context.beginPath();
      context.moveTo(x, plot.height);
      context.lineTo(x, y);
      context.stroke();
    }
  }

  drawEnvelope(context, plot) {
    const definition = AUTOMATION_LANES[this.lane];
    const duration = Math.max(1, this.view.endMs - this.view.startMs);
    this.regions = [];
    if (!this.points.length) {
      context.fillStyle = "#777f8e";
      context.font = "10px system-ui, sans-serif";
      context.textAlign = "left";
      context.textBaseline = "top";
      context.fillText("Ctrl + クリックで制御点を追加", plot.x + 12, 10);
      return;
    }
    const visiblePoints = this.points.filter((point) => point.timeMs >= this.view.startMs - duration && point.timeMs <= this.view.endMs + duration);
    if (!visiblePoints.length) return;
    context.strokeStyle = "#6ea8ff";
    context.lineWidth = 2;
    context.beginPath();
    visiblePoints.forEach((point, index) => {
      const x = plot.x + ((point.timeMs - this.view.startMs) / duration) * plot.width;
      const y = this.valueY(point.value, plot, definition);
      if (index === 0) context.moveTo(x, y);
      else context.lineTo(x, y);
    });
    context.stroke();
    for (const point of visiblePoints) {
      const x = plot.x + ((point.timeMs - this.view.startMs) / duration) * plot.width;
      const y = this.valueY(point.value, plot, definition);
      this.regions.push({ id: point.id, x, y, radius: 7 });
      context.fillStyle = "#dce9ff";
      context.beginPath();
      context.arc(x, y, 4, 0, Math.PI * 2);
      context.fill();
      context.strokeStyle = "#326fc9";
      context.lineWidth = 2;
      context.stroke();
    }
  }

  pointerDown(event) {
    if (event.button !== 0 || !this.plot) return;
    const point = this.point(event);
    if (point.x < this.plot.x) return;
    const hit = this.hitPoint(point);
    if (hit) {
      this.canvas.setPointerCapture(event.pointerId);
      this.drag = { pointId: hit.id };
      return;
    }
    if (!(event.ctrlKey || event.metaKey)) return;
    event.preventDefault();
    const next = [...this.points, {
      id: `automation-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 7)}`,
      timeMs: this.timeAtX(point.x, true),
      value: this.valueAtY(point.y),
    }].sort((a, b) => a.timeMs - b.timeMs);
    this.points = next;
    this.onChange(next);
    this.draw();
  }

  pointerMove(event) {
    if (!this.drag) return;
    const point = this.point(event);
    const target = this.points.find((candidate) => candidate.id === this.drag.pointId);
    if (!target) return;
    target.timeMs = this.timeAtX(point.x, event.shiftKey);
    target.value = this.valueAtY(point.y);
    this.points.sort((a, b) => a.timeMs - b.timeMs);
    this.draw();
  }

  pointerUp() {
    if (!this.drag) return;
    this.drag = null;
    this.onChange(this.points.map((point) => ({ ...point })));
  }

  removeAt(event) {
    if (!this.plot) return;
    const hit = this.hitPoint(this.point(event));
    if (!hit) return;
    event.preventDefault();
    this.points = this.points.filter((point) => point.id !== hit.id);
    this.onChange(this.points.map((point) => ({ ...point })));
    this.draw();
  }

  hitPoint(point) {
    return this.regions.find((region) => Math.hypot(region.x - point.x, region.y - point.y) <= region.radius);
  }

  point(event) {
    const rect = this.canvas.getBoundingClientRect();
    return { x: event.clientX - rect.left, y: event.clientY - rect.top };
  }

  timeAtX(x, bypassSnap = false) {
    const relative = Math.max(0, Math.min(1, (x - this.plot.x) / this.plot.width));
    const raw = this.view.startMs + relative * Math.max(1, this.view.endMs - this.view.startMs);
    return bypassSnap ? Math.max(0, raw) : nearestGridTime(raw, this.gridTimes);
  }

  valueAtY(y) {
    const definition = AUTOMATION_LANES[this.lane];
    const relative = 1 - Math.max(0, Math.min(1, y / this.plot.height));
    return Math.round(definition.minimum + relative * (definition.maximum - definition.minimum));
  }

  valueY(value, plot, definition) {
    const relative = (Number(value) - definition.minimum) / Math.max(1, definition.maximum - definition.minimum);
    return plot.y + (1 - Math.max(0, Math.min(1, relative))) * plot.height;
  }

  formatValue(value) {
    if (this.lane !== "pan") return String(value);
    const pan = Math.round(((value - 64) / 63) * 100);
    if (Math.abs(pan) <= 1) return "C";
    return pan < 0 ? `L${Math.abs(pan)}` : `R${pan}`;
  }
}
