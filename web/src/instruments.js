export const NOTE_BLOCK_INSTRUMENTS = [
  { id: 0, key: "piano", enumName: "PIANO", label: "ハープ / ピアノ", block: "通常ブロック", wave: "triangle", color: "#b9e769" },
  { id: 1, key: "bass_guitar", enumName: "BASS_GUITAR", label: "ベース", block: "木材系", wave: "sawtooth", color: "#74c69d" },
  { id: 2, key: "bass_drum", enumName: "BASS_DRUM", label: "バスドラム", block: "石系", wave: "sine", color: "#ff9b71" },
  { id: 3, key: "snare_drum", enumName: "SNARE_DRUM", label: "スネア", block: "砂系", wave: "square", color: "#f7c66b" },
  { id: 4, key: "sticks", enumName: "STICKS", label: "ハイハット", block: "ガラス系", wave: "square", color: "#d8d8d8" },
  { id: 5, key: "flute", enumName: "FLUTE", label: "フルート", block: "粘土", wave: "sine", color: "#79c7ff" },
  { id: 6, key: "bell", enumName: "BELL", label: "ベル", block: "金ブロック", wave: "sine", color: "#ffd166" },
  { id: 7, key: "guitar", enumName: "GUITAR", label: "ギター", block: "羊毛", wave: "triangle", color: "#e9a66f" },
  { id: 8, key: "chime", enumName: "CHIME", label: "チャイム", block: "氷塊", wave: "sine", color: "#a8dadc" },
  { id: 9, key: "xylophone", enumName: "XYLOPHONE", label: "木琴", block: "骨ブロック", wave: "triangle", color: "#f4a261" },
  { id: 10, key: "iron_xylophone", enumName: "IRON_XYLOPHONE", label: "鉄琴", block: "鉄ブロック", wave: "sine", color: "#b8c0cc" },
  { id: 11, key: "cow_bell", enumName: "COW_BELL", label: "カウベル", block: "ソウルサンド", wave: "square", color: "#c5a46d" },
  { id: 12, key: "didgeridoo", enumName: "DIDGERIDOO", label: "ディジュリドゥ", block: "カボチャ", wave: "sawtooth", color: "#d97745" },
  { id: 13, key: "bit", enumName: "BIT", label: "ビット", block: "エメラルドブロック", wave: "square", color: "#66d9a6" },
  { id: 14, key: "banjo", enumName: "BANJO", label: "バンジョー", block: "干草の俵", wave: "triangle", color: "#e9c46a" },
  { id: 15, key: "pling", enumName: "PLING", label: "プリング", block: "グロウストーン", wave: "sine", color: "#f6e58d" },
];

export const INSTRUMENT_BY_KEY = new Map(
  NOTE_BLOCK_INSTRUMENTS.map((instrument) => [instrument.key, instrument]),
);

export const INSTRUMENT_BY_ID = new Map(
  NOTE_BLOCK_INSTRUMENTS.map((instrument) => [instrument.id, instrument]),
);

export const PART_COLORS = [
  "#b9e769",
  "#79c7ff",
  "#f7c66b",
  "#d990ff",
  "#ff9b71",
  "#74c69d",
  "#a8dadc",
  "#f4a261",
  "#f6e58d",
  "#b8c0cc",
];

const GM_FAMILY_NAMES = [
  "Piano",
  "Chromatic Percussion",
  "Organ",
  "Guitar",
  "Bass",
  "Strings",
  "Ensemble",
  "Brass",
  "Reed",
  "Pipe",
  "Synth Lead",
  "Synth Pad",
  "Synth Effects",
  "Ethnic",
  "Percussive",
  "Sound Effects",
];

export function gmFamilyName(program) {
  return GM_FAMILY_NAMES[Math.max(0, Math.min(15, Math.floor(program / 8)))];
}

export function defaultInstrumentForProgram(program) {
  const value = Math.max(0, Math.min(127, Number(program) || 0));
  if (value <= 7) return "piano";
  if (value <= 15) {
    if (value === 9 || value === 10) return "bell";
    if (value === 14) return "chime";
    return value >= 12 ? "xylophone" : "iron_xylophone";
  }
  if (value <= 23) return value >= 19 ? "pling" : "piano";
  if (value <= 31) return value >= 28 ? "banjo" : "guitar";
  if (value <= 39) return "bass_guitar";
  if (value <= 55) return value >= 48 ? "pling" : "guitar";
  if (value <= 63) return value >= 60 ? "cow_bell" : "didgeridoo";
  if (value <= 79) return "flute";
  if (value <= 87) return "bit";
  if (value <= 95) return value >= 92 ? "chime" : "pling";
  if (value <= 103) return value % 2 === 0 ? "bit" : "chime";
  if (value <= 111) return value <= 107 ? "banjo" : "didgeridoo";
  if (value <= 119) return value <= 115 ? "bell" : "cow_bell";
  return value >= 126 ? "sticks" : "bit";
}

const DRUM_NAMES = new Map([
  [35, "Acoustic Bass Drum"], [36, "Bass Drum 1"], [37, "Side Stick"],
  [38, "Acoustic Snare"], [39, "Hand Clap"], [40, "Electric Snare"],
  [41, "Low Floor Tom"], [42, "Closed Hi-Hat"], [43, "High Floor Tom"],
  [44, "Pedal Hi-Hat"], [45, "Low Tom"], [46, "Open Hi-Hat"],
  [47, "Low-Mid Tom"], [48, "Hi-Mid Tom"], [49, "Crash Cymbal 1"],
  [50, "High Tom"], [51, "Ride Cymbal 1"], [52, "Chinese Cymbal"],
  [53, "Ride Bell"], [54, "Tambourine"], [55, "Splash Cymbal"],
  [56, "Cowbell"], [57, "Crash Cymbal 2"], [58, "Vibraslap"],
  [59, "Ride Cymbal 2"], [60, "Hi Bongo"], [61, "Low Bongo"],
  [62, "Mute Hi Conga"], [63, "Open Hi Conga"], [64, "Low Conga"],
  [65, "High Timbale"], [66, "Low Timbale"], [67, "High Agogo"],
  [68, "Low Agogo"], [69, "Cabasa"], [70, "Maracas"],
  [71, "Short Whistle"], [72, "Long Whistle"], [73, "Short Guiro"],
  [74, "Long Guiro"], [75, "Claves"], [76, "Hi Wood Block"],
  [77, "Low Wood Block"], [78, "Mute Cuica"], [79, "Open Cuica"],
  [80, "Mute Triangle"], [81, "Open Triangle"],
]);

export function drumMapping(midiNote) {
  const note = Math.max(0, Math.min(127, Number(midiNote) || 0));
  let instrumentKey = "sticks";
  let pitch = 12;

  if (note === 35 || note === 36) {
    instrumentKey = "bass_drum";
    pitch = note === 35 ? 8 : 11;
  } else if (note >= 37 && note <= 40) {
    instrumentKey = "snare_drum";
    pitch = 10 + (note - 37) * 2;
  } else if ([41, 43, 45, 47, 48, 50].includes(note)) {
    instrumentKey = note <= 45 ? "bass_drum" : "snare_drum";
    pitch = Math.max(0, Math.min(24, note - 35));
  } else if ([42, 44, 46, 49, 51, 52, 54, 55, 57, 59].includes(note)) {
    instrumentKey = "sticks";
    pitch = note === 42 || note === 44 ? 8 : note === 46 ? 14 : 20;
  } else if (note === 56) {
    instrumentKey = "cow_bell";
    pitch = 12;
  } else if ([67, 68, 80, 81].includes(note)) {
    instrumentKey = "bell";
    pitch = note % 2 === 0 ? 9 : 16;
  } else if (note >= 60 && note <= 66) {
    instrumentKey = "snare_drum";
    pitch = Math.max(0, Math.min(24, note - 53));
  } else if (note >= 75 && note <= 77) {
    instrumentKey = "sticks";
    pitch = note === 77 ? 7 : 15;
  }

  return {
    instrumentKey,
    pitch,
    name: DRUM_NAMES.get(note) || `Percussion ${note}`,
  };
}

const PITCH_CLASS_NAMES = ["C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B"];

export function midiNoteName(note) {
  const value = Math.max(0, Math.min(127, Number(note) || 0));
  return `${PITCH_CLASS_NAMES[value % 12]}${Math.floor(value / 12) - 1}`;
}
