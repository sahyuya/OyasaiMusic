import { INSTRUMENT_BY_KEY } from "./instruments.js";

export class PreviewPlayer {
  constructor({ onTime = () => {}, onStop = () => {} } = {}) {
    this.onTime = onTime;
    this.onStop = onStop;
    this.context = null;
    this.timer = null;
    this.nodes = new Set();
    this.playing = false;
    this.notes = [];
    this.cursor = 0;
    this.startedAt = 0;
    this.startOffsetMs = 0;
    this.durationMs = 0;
  }

  async play(notes, startOffsetMs = 0) {
    this.stop(false);
    if (!notes.length) return;
    this.context ||= new AudioContext({ latencyHint: "interactive" });
    if (this.context.state === "suspended") await this.context.resume();
    this.notes = notes;
    this.startOffsetMs = Math.max(0, startOffsetMs);
    this.durationMs = notes.at(-1).timeMs;
    this.cursor = lowerBound(notes, this.startOffsetMs);
    this.startedAt = this.context.currentTime + 0.08 - this.startOffsetMs / 1000;
    this.playing = true;
    this.schedule();
    this.timer = window.setInterval(() => this.schedule(), 80);
  }

  schedule() {
    if (!this.playing || !this.context) return;
    const nowMs = Math.max(0, (this.context.currentTime - this.startedAt) * 1000);
    const horizonMs = nowMs + 650;
    let scheduled = 0;
    while (this.cursor < this.notes.length && this.notes[this.cursor].timeMs <= horizonMs) {
      this.scheduleNote(this.notes[this.cursor]);
      this.cursor += 1;
      scheduled += 1;
      if (scheduled >= 1500) break;
    }
    this.onTime(nowMs, this.durationMs);
    if (this.cursor >= this.notes.length && nowMs > this.durationMs + 800) this.stop();
  }

  scheduleNote(note) {
    const context = this.context;
    const instrument = INSTRUMENT_BY_KEY.get(note.instrumentKey);
    const oscillator = context.createOscillator();
    const gain = context.createGain();
    const panner = typeof context.createStereoPanner === "function" ? context.createStereoPanner() : null;
    const start = Math.max(context.currentTime + 0.005, this.startedAt + note.timeMs / 1000);
    const isDrum = ["bass_drum", "snare_drum", "sticks"].includes(note.instrumentKey);
    const length = note.instrumentKey === "chime" || note.instrumentKey === "bell" ? 0.55 : isDrum ? 0.1 : 0.28;
    const midi = 54 + note.pitch;
    const baseFrequency = 440 * 2 ** ((midi - 69) / 12);
    oscillator.type = instrument?.wave || "triangle";
    oscillator.frequency.setValueAtTime(
      note.instrumentKey === "bass_drum" ? Math.max(45, baseFrequency / 3) : baseFrequency,
      start,
    );
    if (isDrum) oscillator.frequency.exponentialRampToValueAtTime(Math.max(30, baseFrequency / 5), start + length);
    const peak = Math.max(0.0001, Math.min(0.16, (note.volume / 100) * 0.13));
    gain.gain.setValueAtTime(0.0001, start);
    gain.gain.exponentialRampToValueAtTime(peak, start + 0.008);
    gain.gain.exponentialRampToValueAtTime(0.0001, start + length);
    if (panner) panner.pan.setValueAtTime(Math.max(-1, Math.min(1, note.pan / 100)), start);
    oscillator.connect(gain);
    if (panner) {
      gain.connect(panner);
      panner.connect(context.destination);
    } else {
      gain.connect(context.destination);
    }
    oscillator.start(start);
    oscillator.stop(start + length + 0.02);
    this.nodes.add(oscillator);
    oscillator.addEventListener("ended", () => this.nodes.delete(oscillator), { once: true });
  }

  stop(notify = true) {
    if (this.timer !== null) window.clearInterval(this.timer);
    this.timer = null;
    for (const node of this.nodes) {
      try {
        node.stop();
      } catch {
        // すでに停止済みのノードは無視する。
      }
    }
    this.nodes.clear();
    const wasPlaying = this.playing;
    this.playing = false;
    if (notify && wasPlaying) this.onStop();
  }
}

function lowerBound(notes, timeMs) {
  let low = 0;
  let high = notes.length;
  while (low < high) {
    const middle = Math.floor((low + high) / 2);
    if (notes[middle].timeMs < timeMs) low = middle + 1;
    else high = middle;
  }
  return low;
}
