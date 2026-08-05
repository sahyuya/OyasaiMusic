import { createReadStream, existsSync, statSync } from "node:fs";
import { createServer } from "node:http";
import { extname, join, normalize } from "node:path";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("../", import.meta.url));
const port = Number(process.env.PORT || 4173);
const mime = {
  ".css": "text/css; charset=utf-8",
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".png": "image/png",
};

createServer((request, response) => {
  const rawPath = decodeURIComponent(new URL(request.url || "/", "http://localhost").pathname);
  const relative = normalize(rawPath).replace(/^([/\\])+/, "");
  let filePath = join(root, relative || "index.html");
  if (existsSync(filePath) && statSync(filePath).isDirectory()) filePath = join(filePath, "index.html");
  if (!existsSync(filePath) || !filePath.startsWith(root)) {
    response.writeHead(404, { "content-type": "text/plain; charset=utf-8" });
    response.end("Not found");
    return;
  }
  response.writeHead(200, {
    "cache-control": "no-store",
    "content-type": mime[extname(filePath)] || "application/octet-stream",
  });
  createReadStream(filePath).pipe(response);
}).listen(port, "127.0.0.1", () => {
  console.log(`Local URL: http://127.0.0.1:${port}/`);
});
