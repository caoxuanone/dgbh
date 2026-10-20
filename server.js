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

// 测速源。公共镜像多数对单 IP 限速，数值可能偏低，只作对照用。
const SPEED_SOURCES = {
  gdxs: "https://www.10099.com.cn/js/common-85ed7.js",
  tencent: "https://mirrors.cloud.tencent.com/ubuntu/ls-lR.gz",
  huawei: "https://mirrors.huaweicloud.com/ubuntu/ls-lR.gz",
};

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

  // 测下行速度：4 并发拉大文件，最多 4MB 或 6 秒，换算成 Mbps
  if (url.pathname === "/api/speed") {
    const key = url.searchParams.get("src") || "gdxs";
    const src = SPEED_SOURCES[key];
    if (!src) return sendJson(res, 400, { ok: false, error: "unknown src" });

    // 公共源不在 10099 白名单里，但只允许上面写死的几个地址
    const u = new URL(src);
    const t0 = Date.now();
    const limitMs = 6000;
    const cap = 4_000_000;
    let bytes = 0;
    let stop = false;

    const stream = async (idx) => {
      let pos = idx * 1048576;
      while (!stop && bytes < cap && Date.now() - t0 < limitMs) {
        try {
          const r = await fetch(u, {
            headers: {
              "user-agent": UA,
              "cache-control": "no-cache",
              range: `bytes=${pos}-${pos + 1048575}`,
              referer: u.origin + "/",
            },
            redirect: "follow",
            signal: AbortSignal.timeout(limitMs),
          });
          // 文件比 Range 短（比如 565KB 的 js）：从头重下，别停
          if (r.status === 416) { pos = 0; continue; }
          if (!r.body) return;
          let read = 0;
          for await (const c of r.body) {
            read += c.length;
            bytes += c.length;
            if (bytes >= cap || Date.now() - t0 >= limitMs) { stop = true; break; }
          }
          // 206 说明支持分段，接着往后拉；200 说明整文件，下一轮还是从头
          pos = r.status === 206 ? pos + read : 0;
          if (!read) return;
        } catch {
          return;
        }
      }
    };

    await Promise.all(Array.from({ length: 4 }, (_, i) => stream(i)));

    const ms = Date.now() - t0;
    const mbps = ms > 200 && bytes > 100_000 ? (bytes * 8) / ms / 1000 : 0;
    return sendJson(res, 200, {
      ok: true,
      src: key,
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
