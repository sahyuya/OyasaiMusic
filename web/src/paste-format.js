const DEFAULT_SEGMENT_SIZE = 23_500;
const MAX_SEGMENT_SIZE = 23_500;
const TRANSFER_PREFIX = "OMMT1";

/**
 * `.oyasai`を圧縮し、Paper Dialog APIへ貼り付ける検証付き文字列へ変換する。
 * 通常は1回、Minecraftの受信上限を超える大きな曲だけ約23KBずつに分割する。
 */
export async function encodePasteTransfer(source, { segmentSize = DEFAULT_SEGMENT_SIZE } = {}) {
  const safeSegmentSize = Math.max(256, Math.min(MAX_SEGMENT_SIZE, Math.round(Number(segmentSize) || DEFAULT_SEGMENT_SIZE)));
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
  const payloads = [];
  for (let offset = 0; offset < encoded.length; offset += safeSegmentSize) payloads.push(encoded.slice(offset, offset + safeSegmentSize));
  const transferId = checksum.slice(0, 32);
  const segments = payloads.map(
    (payload, index) => `${TRANSFER_PREFIX}:${transferId}:${index + 1}:${payloads.length}:${checksum}:${payload}`,
  );
  return {
    text: segments.join("\n"),
    segments,
    segmentCount: segments.length,
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
