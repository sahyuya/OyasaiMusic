const DEFAULT_CHUNK_SIZE = 160;
const MAX_CHUNK_SIZE = 180;

/** `.oyasai`を安全なURL-safe Base64へ圧縮し、Minecraftへ1行ずつ貼れるコマンド列にする。 */
export async function encodePasteCommands(source, { chunkSize = DEFAULT_CHUNK_SIZE } = {}) {
  const safeChunkSize = Math.max(40, Math.min(MAX_CHUNK_SIZE, Math.round(Number(chunkSize) || DEFAULT_CHUNK_SIZE)));
  const bytes = await sourceBytes(source);
  if (typeof CompressionStream !== "function") {
    throw new Error("このブラウザはコピペデータのgzip圧縮に対応していません。");
  }
  if (!globalThis.crypto?.subtle) {
    throw new Error("このブラウザはコピペデータの整合性検証に対応していません。");
  }
  const compressedBuffer = await new Response(
    new Blob([bytes]).stream().pipeThrough(new CompressionStream("gzip")),
  ).arrayBuffer();
  const compressed = new Uint8Array(compressedBuffer);
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", compressed));
  const checksum = [...digest].map((value) => value.toString(16).padStart(2, "0")).join("");
  const encoded = base64UrlEncode(compressed);
  const chunks = [];
  for (let offset = 0; offset < encoded.length; offset += safeChunkSize) chunks.push(encoded.slice(offset, offset + safeChunkSize));
  const commands = [
    `/mm paste begin ${chunks.length} ${checksum}`,
    ...chunks.map((chunk, index) => `/mm paste add ${index} ${chunk}`),
    "/mm paste finish",
  ];
  return {
    text: commands.join("\n"),
    commands,
    chunkCount: chunks.length,
    checksum,
    originalBytes: bytes.byteLength,
    compressedBytes: compressed.byteLength,
  };
}

async function sourceBytes(source) {
  if (source instanceof Uint8Array) return source;
  if (source instanceof ArrayBuffer) return new Uint8Array(source);
  if (source && typeof source.arrayBuffer === "function") return new Uint8Array(await source.arrayBuffer());
  throw new TypeError("コピペ用に変換できるバイナリデータではありません。");
}

function base64UrlEncode(bytes) {
  const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
  let result = "";
  for (let index = 0; index < bytes.length; index += 3) {
    const first = bytes[index];
    const hasSecond = index + 1 < bytes.length;
    const hasThird = index + 2 < bytes.length;
    const second = hasSecond ? bytes[index + 1] : 0;
    const third = hasThird ? bytes[index + 2] : 0;
    const value = (first << 16) | (second << 8) | third;
    result += alphabet[(value >> 18) & 63];
    result += alphabet[(value >> 12) & 63];
    if (hasSecond) result += alphabet[(value >> 6) & 63];
    if (hasThird) result += alphabet[value & 63];
  }
  return result;
}
