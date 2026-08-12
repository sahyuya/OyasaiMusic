import assert from "node:assert/strict";
import test from "node:test";
import { gunzipSync } from "node:zlib";
import { encodePasteCommands } from "../src/paste-format.js";

test(".oyasaiバイト列を検証付きのMinecraftコマンド列へ変換する", async () => {
  const source = Uint8Array.from({ length: 2048 }, (_, index) => index % 251);
  const result = await encodePasteCommands(source, { chunkSize: 80 });
  assert.match(result.commands[0], /^\/mm paste begin \d+ [0-9a-f]{64}$/);
  assert.equal(result.commands.at(-1), "/mm paste finish");
  assert.equal(result.commands.length, result.chunkCount + 2);
  const payload = result.commands.slice(1, -1).map((command, index) => {
    const match = command.match(/^\/mm paste add (\d+) ([A-Za-z0-9_-]+)$/);
    assert.ok(match);
    assert.equal(Number(match[1]), index);
    assert.ok(match[2].length <= 80);
    return match[2];
  }).join("");
  const compressed = Buffer.from(payload.replace(/-/g, "+").replace(/_/g, "/"), "base64");
  assert.deepEqual(gunzipSync(compressed), Buffer.from(source));
});
