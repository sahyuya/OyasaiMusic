import { cp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const root = dirname(dirname(fileURLToPath(import.meta.url)));
const output = join(root, "dist");

await rm(output, { recursive: true, force: true });
await mkdir(output, { recursive: true });
await cp(join(root, "src"), join(output, "src"), { recursive: true });
await cp(join(root, "index.html"), join(output, "index.html"));
await cp(join(root, "styles.css"), join(output, "styles.css"));
await writeFile(join(output, ".nojekyll"), "");

const html = await readFile(join(output, "index.html"), "utf8");
if (!html.includes("OyasaiMusicMidiTranslator") || !html.includes('./src/app.js')) {
  throw new Error("生成されたindex.htmlにOMMTのエントリーポイントがありません。");
}
console.log(`OMMT static site built: ${output}`);
