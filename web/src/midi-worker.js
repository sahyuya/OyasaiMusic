import { parseMidi } from "./midi-parser.js";

self.addEventListener("message", (event) => {
  const { buffer, sourceName } = event.data || {};
  try {
    const midi = parseMidi(buffer, sourceName, (progress) => {
      self.postMessage({ type: "progress", progress });
    });
    self.postMessage({ type: "result", midi });
  } catch (error) {
    self.postMessage({
      type: "error",
      message: error instanceof Error ? error.message : "MIDIの解析中に不明なエラーが発生しました。",
    });
  }
});
