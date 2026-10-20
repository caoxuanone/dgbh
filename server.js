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

// 测速源。
// public = speedtest.im 的公开下载源清单（386 条国内 CDN 大文件，明文 JSON），
//          每次采样随机换文件，避免命中缓存导致数字虚高。
// 其余是固定单文件源，只作对照。
const SPEED_SOURCES = {
  gdxs: "https://www.10099.com.cn/js/common-85ed7.js",
  tencent: "https://mirrors.cloud.tencent.com/ubuntu/ls-lR.gz",
  huawei: "https://mirrors.huaweicloud.com/ubuntu/ls-lR.gz",
};

const PUBLIC_CATALOG = "https://speedtest.im/api/download_sources.php";
// 只有 1、2 这两个池是「下载源」；U1 是 Ookla 的上传端点，不能用来测下行
const PUBLIC_POOLS = ["1", "2"];
const MIN_FILE_BYTES = 20 * 1024 * 1024;

let healthy = null;
let healthyAt = 0;
let fileQueue = [];
const pinned = new Map(); // pin -> [url...]，同一域名的基线与复测用同一批文件
const badFiles = new Map(); // url -> 时间戳：本轮里卡死过的文件，暂时不再用

function isBad(u) {
  const t = badFiles.get(u);
  if (!t) return false;
  if (Date.now() - t > 30 * 60 * 1000) {
    badFiles.delete(u);
    return false;
  }
  return true;
}

/** 拉清单 + 逐个体检（Range 取 2 字节，确认真的能下），缓存 10 分钟 */
async function publicSourceList() {
  if (healthy && Date.now() - healthyAt < 10 * 60 * 1000) return healthy;

  const r = await fetch(PUBLIC_CATALOG, {
    headers: { "user-agent": UA, referer: "https://speedtest.im/" },
    signal: AbortSignal.timeout(12000),
  });
  if (!r.ok) throw new Error("catalog " + r.status);
  const j = await r.json();
  const pools = j.healthTokens || j.downloadSourcePools || {};
  const set = new Set();
  for (const key of PUBLIC_POOLS) {
    const p = pools[key];
    if (Array.isArray(p)) {
      for (const u of p) if (typeof u === "string" && /^https?:\/\//i.test(u)) set.add(u);
    } else if (p && typeof p === "object") {
      for (const u of Object.keys(p)) if (/^https?:\/\//i.test(u)) set.add(u);
    }
  }
  const raw = [...set];
  if (!raw.length) throw new Error("catalog empty");

  // 体检：并发 16，整体最多 15 秒，拿不到就先用手上的
  const checkBy = Date.now() + 15000;
  const found = [];
  let cursor = 0;
  const worker = async () => {
    while (cursor < raw.length && Date.now() < checkBy) {
      const u = raw[cursor++];
      const f = await probeFile(u);
      if (f) found.push(f);
    }
  };
  await Promise.all(Array.from({ length: 16 }, worker));

  let list = found.filter((f) => f.size >= MIN_FILE_BYTES).sort((a, b) => b.size - a.size);
  if (list.length < 4) list = found.sort((a, b) => b.size - a.size).slice(0, 8);
  if (!list.length) throw new Error("no healthy source");

  healthy = list.map((f) => f.u);
  healthyAt = Date.now();
  fileQueue = shuffled(healthy.slice(0, 20));
  return healthy;
}

/** 探测单个文件是否可下载，返回 { u, size } */
async function probeFile(u) {
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), 5000);
  try {
    const r = await fetch(u, {
      headers: { "user-agent": UA, range: "bytes=0-1", "cache-control": "no-cache" },
      redirect: "follow",
      signal: ctl.signal,
    });
    const cr = r.headers.get("content-range") || "";
    const m = /\/(\d+)\s*$/.exec(cr);
    const size = m ? Number(m[1]) : Number(r.headers.get("content-length") || 0);
    if (r.body) {
      try { await r.body.cancel(); } catch (_) {}
    }
    return r.ok && size > 0 ? { u, size } : null;
  } catch (_) {
    return null;
  } finally {
    clearTimeout(timer);
  }
}

function shuffled(arr) {
  const a = arr.slice();
  for (let i = a.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [a[i], a[j]] = [a[j], a[i]];
  }
  return a;
}

/**
 * 取本次测速用的文件：同一个 pin（域名）前后两次测速拿到同一批文件，
 * 这样基线和复测走的是同一个 CDN、同一个文件，只有链路可能变。
 */
function takeFromQueue(n) {
  const out = [];
  let guard = 0;
  while (out.length < n && guard++ < 400) {
    if (!fileQueue.length) fileQueue = shuffled(healthy || []);
    if (!fileQueue.length) break;
    const u = fileQueue.pop();
    if (isBad(u)) continue;
    out.push(u);
  }
  return out;
}

/**
 * 取本次测速用的文件：同一个 pin（域名）前后两次测速拿到同一批文件，
 * 这样基线和复测走的是同一个 CDN、同一个文件，只有链路可能变。
 * 中途发现某个文件卡死，就用新的文件顶替。
 */
function pickFiles(pin, n) {
  const key = String(pin || "");
  if (key && pinned.has(key)) {
    const cur = pinned.get(key);
    const good = cur.filter((u) => !isBad(u));
    if (good.length === cur.length) return cur;
    const filled = good.concat(takeFromQueue(n - good.length));
    pinned.set(key, filled);
    return filled;
  }
  const out = takeFromQueue(n);
  if (key) {
    if (pinned.size > 32) pinned.clear();
    pinned.set(key, out);
  }
  return out;
}

/**
 * 按时间测下行速度（默认 30 秒，4 并发）。
 * 丢掉前 1 秒热身（TCP 慢启动），返回总 Mbps + 三段速度。
 * 每条连接下自己的文件，下完再取下一个，避免频繁握手。
 */
async function speedRun(srcKey, seconds, pin) {
  const totalMs = seconds * 1000;

  let list;
  let fallback = false;
  if (srcKey === "public") {
    try {
      const all = await publicSourceList();
      list = pickFiles(pin, 4);
      if (!list.length) list = all.slice(0, 4);
    } catch (_) {
      list = [SPEED_SOURCES.tencent];
      fallback = true;
    }
  } else {
    const u = SPEED_SOURCES[srcKey];
    if (!u) throw new Error("unknown src");
    list = [u];
  }

  // 计时从真正开始下载算起：拉清单/体检的时间不能算进测速窗口
  const t0 = Date.now();
  const deadline = t0 + totalMs;
  const segMs = Math.floor(totalMs / 3);

  const buckets = [0, 0, 0];
  let bytes = 0;
  let stop = false;

  const stream = async (idx) => {
    let i = idx;
    while (!stop && Date.now() < deadline) {
      const u = list[i % list.length];
      i += 1;
      const left = deadline - Date.now();
      if (left < 800) break;

      // 硬超时 + 停摆超时：4 秒没数据就换文件，别让一个卡死的源吃掉整个测速
      const ctl = new AbortController();
      let lastData = Date.now();
      const hard = setTimeout(() => ctl.abort(), left + 500);
      const watch = setInterval(() => {
        if (Date.now() - lastData > 4000) ctl.abort();
      }, 500);

      let got = 0;
      try {
        const r = await fetch(u, {
          headers: { "user-agent": UA, "cache-control": "no-cache", accept: "*/*" },
          redirect: "follow",
          signal: ctl.signal,
        });
        if (!r.ok || !r.body) {
          badFiles.set(u, Date.now());
          continue;
        }
        for await (const chunk of r.body) {
          lastData = Date.now();
          got += chunk.length;
          const t = Date.now() - t0;
          if (t < 1000) continue; // 热身
          if (t >= totalMs) { stop = true; break; }
          const b = Math.min(2, Math.floor(t / segMs));
          buckets[b] += chunk.length;
          bytes += chunk.length;
        }
        if (got === 0) badFiles.set(u, Date.now());
      } catch (_) {
        // 单个文件失败/停摆就换下一个，不影响整体；一个字节都没拿到才拉黑
        if (got === 0) badFiles.set(u, Date.now());
        if (Date.now() >= deadline) break;
      } finally {
        clearTimeout(hard);
        clearInterval(watch);
      }
    }
  };

  await Promise.all([0, 1, 2, 3].map((i) => stream(i)));

  const ms = Date.now() - t0;
  const span = [Math.max(1000, segMs - 1000), segMs, segMs];
  const seg = buckets.map((b, i) => Math.round((b * 8) / span[i]) / 1000);
  const mbps = ms > 1500 ? (bytes * 8) / ((totalMs - 1000) / 1000) / 1_000_000 : 0;
  return {
    mbps: Math.round(mbps * 10) / 10,
    seg: seg.map((v) => Math.round(v * 10) / 10),
    bytes,
    ms,
    sources: list.length,
    fallback,
  };
}

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

  // 测下行速度：默认 30 秒、4 并发，按时间计，丢前 1 秒热身，返回总 Mbps + 三段
  if (url.pathname === "/api/speed") {
    const key = url.searchParams.get("src") || "public";
    const seconds = Math.min(60, Math.max(5, Number(url.searchParams.get("seconds")) || 30));
    const pin = url.searchParams.get("pin") || "";
    if (key !== "public" && !SPEED_SOURCES[key]) {
      return sendJson(res, 400, { ok: false, error: "unknown src" });
    }

    try {
      const r = await speedRun(key, seconds, pin);
      return sendJson(res, 200, { ok: true, src: key, seconds, ...r });
    } catch (e) {
      return sendJson(res, 200, {
        ok: false,
        src: key,
        seconds,
        error: String(e && e.message ? e.message : e),
      });
    }
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
