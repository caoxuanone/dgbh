/**
 * 可选的本地小服务：托管页面 + 代发请求（这样页面能拿到真实的状态码和字节数）。
 * 用法：node server.js  然后浏览器打开 http://127.0.0.1:8787
 */
const http = require("node:http");
const fs = require("node:fs");
const path = require("node:path");

const PORT = Number(process.env.PORT || 8787);
const HOST = "127.0.0.1";
const ROOT = __dirname;

// 只允许代理这些域名，避免变成开放代理
const ALLOW_SUFFIX = (process.env.ALLOW_SUFFIX || "10099.com.cn")
  .split(",")
  .map((s) => s.trim().toLowerCase())
  .filter(Boolean);

const UA =
  "Mozilla/5.0 (Linux; Android 14; PGT-AN10) AppleWebKit/537.36 (KHTML, like Gecko) " +
  "Chrome/124.0.0.0 Mobile Safari/537.36";

// 测速用的固定文件：站点自己的 565KB 静态资源，不受 WAF 影响
const SPEED_URL = process.env.SPEED_URL || "https://www.10099.com.cn/js/common-85ed7.js";

function allowed(urlStr) {
  let u;
  try {
    u = new URL(urlStr);
  } catch {
    return null;
  }
  if (u.protocol !== "https:" && u.protocol !== "http:") return null;
  const host = u.hostname.toLowerCase();
  const ok = ALLOW_SUFFIX.some((s) => host === s || host.endsWith("." + s));
  return ok ? u : null;
}

function sendJson(res, code, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(code, {
    "content-type": "application/json; charset=utf-8",
    "cache-control": "no-store",
    "access-control-allow-origin": "*",
    "content-length": Buffer.byteLength(body),
  });
  res.end(body);
}

const MIME = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".svg": "image/svg+xml",
  ".ico": "image/x-icon",
  ".png": "image/png",
};

function serveStatic(req, res, pathname) {
  const rel = pathname === "/" ? "index.html" : pathname.replace(/^\/+/, "");
  const file = path.join(ROOT, rel);
  if (!file.startsWith(ROOT)) {
    res.writeHead(403).end("forbidden");
    return;
  }
  fs.readFile(file, (err, buf) => {
    if (err) {
      res.writeHead(404, { "content-type": "text/plain; charset=utf-8" }).end("404");
      return;
    }
    res.writeHead(200, {
      "content-type": MIME[path.extname(file).toLowerCase()] || "application/octet-stream",
      "cache-control": "no-store",
    });
    res.end(buf);
  });
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${HOST}:${PORT}`);

  if (url.pathname === "/api/health") {
    return sendJson(res, 200, { ok: true, allow: ALLOW_SUFFIX, port: PORT });
  }

  if (url.pathname === "/api/hit") {
    const target = url.searchParams.get("url") || "";
    const u = allowed(target);
    if (!u) return sendJson(res, 400, { ok: false, error: "url not allowed" });

    const t0 = Date.now();
    try {
      const r = await fetch(u, {
        headers: {
          "user-agent": UA,
          accept: "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
          "accept-language": "zh-CN,zh;q=0.9",
          referer: u.origin + "/",
        },
        redirect: "follow",
        signal: AbortSignal.timeout(20000),
      });
      const buf = Buffer.from(await r.arrayBuffer());
      const head = buf.subarray(0, 4096).toString("utf8");
      return sendJson(res, 200, {
        ok: true,
        status: r.status,
        bytes: buf.length,
        ms: Date.now() - t0,
        finalUrl: r.url,
        contentType: r.headers.get("content-type") || "",
        waf: head.includes("aliyun_waf_aa") || head.includes('id="renderData"'),
      });
    } catch (e) {
      return sendJson(res, 200, {
        ok: false,
        status: 0,
        bytes: 0,
        ms: Date.now() - t0,
        error: String(e && e.message ? e.message : e),
      });
    }
  }

  // 测下行速度：连续拉静态资源，最多 3MB 或 8 秒，换算成 Mbps
  if (url.pathname === "/api/speed") {
    const u = allowed(SPEED_URL);
    if (!u) return sendJson(res, 400, { ok: false, error: "url not allowed" });

    const t0 = Date.now();
    const limitMs = 8000;
    const targetBytes = 3_000_000;
    let bytes = 0;

    for (let i = 0; i < 8 && bytes < targetBytes && Date.now() - t0 < limitMs; i++) {
      try {
        const r = await fetch(u, {
          headers: {
            "user-agent": UA,
            "cache-control": "no-cache",
            pragma: "no-cache",
            referer: u.origin + "/",
          },
          redirect: "follow",
          signal: AbortSignal.timeout(limitMs),
        });
        if (!r.body) break;
        for await (const chunk of r.body) {
          bytes += chunk.length;
          if (bytes >= targetBytes) break;
          if (Date.now() - t0 >= limitMs) break;
        }
      } catch {
        // 超时或中断：用已经收到的字节数算
        break;
      }
    }

    const ms = Date.now() - t0;
    const mbps = ms > 200 && bytes > 100_000 ? (bytes * 8) / ms / 1000 : 0;
    return sendJson(res, 200, {
      ok: true,
      bytes,
      ms,
      mbps: Math.round(mbps * 10) / 10,
    });
  }

  if (req.method === "GET" || req.method === "HEAD") {
    return serveStatic(req, res, url.pathname);
  }

  res.writeHead(405).end();
});

server.listen(PORT, HOST, () => {
  console.log(`页面:  http://${HOST}:${PORT}`);
  console.log(`代理白名单: ${ALLOW_SUFFIX.join(", ")}`);
  console.log("Ctrl+C 退出");
});
