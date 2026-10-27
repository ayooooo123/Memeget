#!/usr/bin/env node
/**
 * Local Apple Silicon 3-Stage Meme Extractor for Unraid Immich (`http://unraid.local:2283`) + Local Folders.
 *
 * Uses the exact shared C++20 `native/core/` engine:
 *   - Stage 0: MobileCLIP-S2 (`5c7311ce...`, `recipe-et0.9.2`) 512-d image vectors via `sim_s2_embedder`
 *   - Stage 1: Apple Vision ANE OCR + 2x2 Panel Gutter Detector + 6,028 OpenVocabConcepts + MemeGateCore
 *   - Stage 2: Fine-tuned `LiquidAI/d1-omni-600M` (`ed64250a...`) System-1 decision pass on macOS Metal GPU
 *   - Stage 3: Optional `MiniCPM-V 4.7 1B` Metal 1-token gate + two-domain captioning (or Gemini two-domain ID)
 *
 * Security & Safety Contract:
 *   - Reads `IMMICH_BASE_URL` and `IMMICH_API_KEY` from `.immich.env` (never echoed, logged, or written to output).
 *   - Runs in `--dry-run` mode by default: writes `immich-out/dry_run_manifest.json`, `immich-out/review.html`,
 *     and a pre-embedded Memeget Collection Pack (`immich-out/memeget-pack/`).
 *   - Only creates/updates the non-destructive Immich `"Memes"` album when `--commit-album` is explicitly passed.
 *   - NEVER deletes, edits, or mutates original Immich assets.
 */

import { spawn, execFileSync } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import path from 'node:path';
import readline from 'node:readline';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(__dirname, '../..');
const OUT_ROOT = path.join(REPO_ROOT, 'immich-out');
const MODELS_DIR = path.join(OUT_ROOT, 'models');
const THUMBS_DIR = path.join(OUT_ROOT, 'thumbs');
const PACK_DIR = path.join(OUT_ROOT, 'memeget-pack');
const PACK_MEDIA_DIR = path.join(PACK_DIR, 'media');

const OPEN_VOCAB_BIN = path.join(REPO_ROOT, 'native/app/src/main/assets/knowledge/open_vocab_concepts.bin');
const VERIFIER_BIN = path.join(REPO_ROOT, 'native/app/src/main/assets/knowledge/pairwise_verifier.bin');
const ANCHORS_BIN = path.join(REPO_ROOT, 'native/app/src/main/assets/knowledge/meme_gate_anchors.bin');
const GATE_WORKER_BIN = path.join(__dirname, 'build/immich_gate_worker');
const SIM_EMBEDDER_BIN = path.join(__dirname, 'build/sim_s2_embedder');

const ARTIFACTS = {
  s2Image: {
    filename: 'mobileclip_s2_image_xnnpack_fp32.pte',
    url: 'https://github.com/ayooooo123/Memeget/releases/download/models-v1/mobileclip_s2_image_xnnpack_fp32.pte',
    sha256: 'dd6a357fea3199b00eff17cd0898023213cf176e7566f7cbdf8dab9f0a1fe7f5',
    bytes: 143278592,
  },
  s2Text: {
    filename: 'mobileclip_s2_text_xnnpack_fp32.pte',
    url: 'https://github.com/ayooooo123/Memeget/releases/download/models-v1/mobileclip_s2_text_xnnpack_fp32.pte',
    sha256: 'e7576174e756d5579179df4c76ea136123db0a66ee222974a3cf8e2a590ff488',
    bytes: 254061440,
  },
  s2Tokenizer: {
    filename: 'mobileclip_s2_tokenizer.json',
    url: 'https://github.com/ayooooo123/Memeget/releases/download/models-v1/mobileclip_s2_tokenizer.json',
    sha256: '8e019f9349caf5f9bb92514b0e4fc00c92841aef6ae8df9d833d975c623b7c72',
    bytes: 3642348,
  },
  d1Omni: {
    filename: 'd1-omni-600M-memeget-Q8_0.gguf',
    url: 'https://github.com/ayooooo123/Memeget/releases/download/models-v1/d1-omni-600M-memeget-Q8_0.gguf',
    sha256: 'ed64250aecb7bf62ebd5a655ac68012e9d5af859c968a648184c6f5bcea6d88d',
    bytes: 407203808,
  },
  minicpmModel: {
    filename: 'MiniCPM-V-4_7-1B-Q4_K_M.gguf',
    url: 'https://huggingface.co/openbmb/MiniCPM-V-4.7-1B-gguf/resolve/59cd286146b6ce70f818508ccf512e11438d8957/MiniCPM-V-4_7-1B-Q4_K_M.gguf',
    sha256: '588c6c94c82f756b86dba35c42ed9ba9ec187d65824eb52f039c347df563e63d',
    bytes: 672003680,
  },
  minicpmProjBf16: {
    filename: 'mmproj-model-f16.gguf',
    url: 'https://huggingface.co/openbmb/MiniCPM-V-4.7-1B-gguf/resolve/59cd286146b6ce70f818508ccf512e11438d8957/mmproj-model-f16.gguf',
    sha256: 'fb9cc047a3cdad5317fd8433a4aeda2d22f00f02ca4bd93b34ad84165b25a900',
    bytes: 1110117984,
  },
  minicpmProjConverted: {
    filename: 'mmproj-model-f16-converted.gguf',
    sha256: '204a7993d345456fb36d19892dbfd4ab6b5ce09026c7bc0bd3fec1e4831834b7',
    bytes: 1110117984,
  },
};

function parseTwoDomainReply(raw) {
  const cleaned = String(raw || '').trim().replace(/^YES[|:]\s*/i, '');
  const fields = {};
  const chunks = cleaned.includes('|') ? cleaned.split('|') : cleaned.split(/\r?\n/);
  for (const ch of chunks) {
    const idx = ch.indexOf(':');
    if (idx > 0 && idx <= 28) {
      const k = ch.slice(0, idx).trim().toLowerCase().replace(/\s+/g, '_');
      const v = ch.slice(idx + 1).trim();
      if (v && !['none', 'unknown'].includes(v.toLowerCase())) fields[k] = v;
    }
  }
  const scene = fields.scene || fields.description || cleaned.slice(0, 400);
  const baseRef = fields.base_reference || fields.base || fields.template || '';
  const targetTopic = fields.target_topic || fields.target || fields.topic || '';
  const replyIntent = fields.reply_intent || fields.intent || fields.situation || '';
  const rawTags = fields.tags || '';

  const tags = [];
  const seen = new Set();
  const pushTag = (lbl, cat, score) => {
    const norm = lbl.toLowerCase().replace(/[^a-z0-9\s'-]+/g, ' ').replace(/\s+/g, ' ').trim();
    if (norm.length < 2 || ['none', 'unknown', 'meme', 'image', 'photo'].includes(norm) || seen.has(norm)) return;
    seen.add(norm);
    tags.push({ label: norm, category: cat, score, source: 'vision' });
  };
  if (baseRef) baseRef.split(/[,;]/).forEach((s) => pushTag(s, 'format', 0.78));
  if (targetTopic) targetTopic.split(/[,;]/).forEach((s) => pushTag(s, 'topic', 0.74));
  if (rawTags) rawTags.split(/[,;]/).slice(0, 8).forEach((s) => pushTag(s, 'topic', 0.68));

  const combinedCaption = [
    scene || null,
    baseRef ? `Reference: ${baseRef}.` : null,
    targetTopic ? `About: ${targetTopic}.` : null,
    replyIntent ? `Reply use: ${replyIntent}.` : null,
  ].filter(Boolean).join(' ');
  const searchTerms = [baseRef, targetTopic, replyIntent, rawTags].filter(Boolean).join(' ');
  return { combinedCaption, searchTerms, tags };
}

function parseArgs(argv) {
  const opts = {
    limit: 250,
    localDir: null,
    commitAlbum: false,
    albumName: 'Memes',
    skipD1: false,
    skipVlm: false,
    insecureHttp: false,
    simDevice: '35D44AB4-7E65-4DE0-8F18-86889234F539',
  };
  for (let i = 2; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--limit' && argv[i + 1]) opts.limit = Number(argv[++i]);
    else if (a === '--local-dir' && argv[i + 1]) opts.localDir = path.resolve(argv[++i]);
    else if (a === '--commit-album') {
      opts.commitAlbum = true;
      if (argv[i + 1] && !argv[i + 1].startsWith('--')) opts.albumName = argv[++i];
    } else if (a === '--skip-d1') opts.skipD1 = true;
    else if (a === '--skip-vlm') opts.skipVlm = true;
    else if (a === '--insecure-http') opts.insecureHttp = true;
    else if (a === '--sim-udid' && argv[i + 1]) opts.simDevice = argv[++i];
  }
  return opts;
}

function sanitizeImmichBaseUrl(rawUrl, allowInsecureHttp) {
  if (!rawUrl) return '';
  let parsed;
  try {
    parsed = new URL(rawUrl);
  } catch {
    throw new Error('Invalid IMMICH_BASE_URL');
  }
  if (parsed.username || parsed.password || parsed.hash) {
    throw new Error('IMMICH_BASE_URL must not contain credentials or URL fragments');
  }
  if (parsed.protocol !== 'https:' && !(allowInsecureHttp && parsed.protocol === 'http:')) {
    throw new Error('IMMICH_BASE_URL requires https:// (pass --insecure-http explicitly for trusted LAN HTTP)');
  }
  return `${parsed.origin}${parsed.pathname}`.replace(/\/+$/, '');
}

function loadImmichConfig(allowInsecureHttp = false) {
  const envMap = {};
  const envFile = path.join(REPO_ROOT, '.immich.env');
  if (fs.existsSync(envFile)) {
    const st = fs.statSync(envFile);
    if ((st.mode & 0o077) !== 0) {
      throw new Error('Refusing to load .immich.env with group/world permissions; run chmod 600 .immich.env');
    }
    for (const rawLine of fs.readFileSync(envFile, 'utf8').split(/\r?\n/)) {
      const line = rawLine.trim();
      if (!line || line.startsWith('#')) continue;
      const eq = line.indexOf('=');
      if (eq > 0) {
        envMap[line.slice(0, eq).trim()] = line.slice(eq + 1).trim();
      }
    }
  }
  const rawBase = (process.env.IMMICH_BASE_URL || envMap.IMMICH_BASE_URL || '').trim();
  return {
    baseUrl: rawBase ? sanitizeImmichBaseUrl(rawBase, allowInsecureHttp) : '',
    apiKey: process.env.IMMICH_API_KEY || envMap.IMMICH_API_KEY || '',
  };
}

async function sha256File(filePath) {
  const hash = crypto.createHash('sha256');
  const stream = fs.createReadStream(filePath);
  for await (const chunk of stream) hash.update(chunk);
  return hash.digest('hex');
}

async function ensureArtifact(spec) {
  await fsp.mkdir(MODELS_DIR, { recursive: true });
  const dest = path.join(MODELS_DIR, spec.filename);
  if (fs.existsSync(dest) && fs.statSync(dest).size === spec.bytes) {
    return dest;
  }
  console.log(`[models] Downloading ${spec.filename} (${(spec.bytes / 1e6).toFixed(1)} MB, 16x parallel range streams)...`);
  const part = `${dest}.part`;
  await fsp.rm(part, { force: true });

  const fh = await fsp.open(part, 'w');
  try {
    await fh.truncate(spec.bytes);
    const chunkSize = 4 * 1024 * 1024; // 4 MiB slices
    const ranges = [];
    for (let offset = 0; offset < spec.bytes; offset += chunkSize) {
      const end = Math.min(spec.bytes - 1, offset + chunkSize - 1);
      ranges.push({ offset, end });
    }
    let cursor = 0;
    const concurrency = Math.min(12, ranges.length);
    await Promise.all(Array.from({ length: concurrency }, async () => {
      while (cursor < ranges.length) {
        const { offset, end } = ranges[cursor++];
        let pos = offset;
        let retries = 0;
        while (pos <= end) {
          try {
            const r = await fetch(spec.url, {
              headers: { Range: `bytes=${pos}-${end}` },
              redirect: 'follow',
            });
            if (r.status !== 206 && !(pos === 0 && end === spec.bytes - 1 && r.status === 200)) {
              throw new Error(`Unexpected status ${r.status}`);
            }
            const buf = Buffer.from(await r.arrayBuffer());
            if (buf.length === 0) throw new Error('Zero-byte range read');
            const toWrite = Math.min(buf.length, end - pos + 1);
            await fh.write(buf, 0, toWrite, pos);
            pos += toWrite;
            retries = 0;
          } catch (err) {
            if (++retries > 8) throw err;
            await new Promise((res) => setTimeout(res, 400 * retries));
          }
        }
      }
    }));
  } finally {
    await fh.close();
  }

  const actualHash = await sha256File(part);
  if (actualHash !== spec.sha256) {
    await fsp.unlink(part).catch(() => {});
    throw new Error(`SHA-256 mismatch on ${spec.filename}: got ${actualHash}`);
  }
  await fsp.rename(part, dest);
  console.log(`[models] Verified ${spec.filename}`);
  return dest;
}

class LineWorker {
  constructor(cmd, args, extraEnv = {}) {
    this.proc = spawn(cmd, args, {
      stdio: ['pipe', 'pipe', 'inherit'],
      env: { ...process.env, ...extraEnv },
    });
    this.rl = readline.createInterface({ input: this.proc.stdout });
    this.waiters = [];
    this.rl.on('line', (line) => {
      const next = this.waiters.shift();
      if (next) next(line);
    });
  }

  send(lineStr) {
    return new Promise((resolve, reject) => {
      this.waiters.push((reply) => {
        try {
          resolve(JSON.parse(reply));
        } catch (err) {
          reject(new Error(`Malformed worker JSON: ${reply.slice(0, 160)}`));
        }
      });
      this.proc.stdin.write(lineStr + '\n');
    });
  }

  close() {
    this.proc.stdin.end();
  }
}

async function ensureSimulatorBooted(udid) {
  try {
    execFileSync('xcrun', ['simctl', 'boot', udid], { stdio: 'ignore' });
  } catch {
    // Already booted
  }
}

async function fetchImmichCandidates(baseUrl, apiKey, limit) {
  await fsp.mkdir(THUMBS_DIR, { recursive: true });
  const headers = {
    'x-api-key': apiKey,
    'Content-Type': 'application/json',
    Accept: 'application/json',
  };
  const items = [];
  let page = 1;
  const pageSize = Math.min(250, Math.max(50, limit));
  while (items.length < limit) {
    const resp = await fetch(`${baseUrl}/api/search/metadata`, {
      method: 'POST',
      headers,
      body: JSON.stringify({ page, size: pageSize, type: 'IMAGE' }),
    });
    if (!resp.ok) throw new Error(`Immich /api/search/metadata returned HTTP ${resp.status}`);
    const data = await resp.json();
    const rows = data?.assets?.items || [];
    if (rows.length === 0) break;
    const resolvedThumbsRoot = path.resolve(THUMBS_DIR) + path.sep;
    for (const r of rows) {
      if (items.length >= limit) break;
      const assetId = String(r.id || '');
      if (!/^[a-fA-F0-9-]{8,64}$/.test(assetId)) continue;
      const thumbPath = path.resolve(THUMBS_DIR, `${assetId}.jpg`);
      if (!thumbPath.startsWith(resolvedThumbsRoot)) continue;
      if (!fs.existsSync(thumbPath) || fs.statSync(thumbPath).size === 0) {
        const tResp = await fetch(`${baseUrl}/api/assets/${encodeURIComponent(assetId)}/thumbnail?size=preview`, {
          headers: { 'x-api-key': apiKey },
        });
        if (!tResp.ok) continue;
        const buf = Buffer.from(await tResp.arrayBuffer());
        await fsp.writeFile(thumbPath, buf);
      }
      const origName = r.originalFileName || `${assetId}.jpg`;
      items.push({
        id: assetId,
        name: origName,
        localPath: thumbPath,
        isVideo: r.type === 'VIDEO',
        durationMs: 0,
        isScreenshot: /screenshot|screen_shot|img_/i.test(origName) && origName.toLowerCase().endsWith('.png'),
      });
    }
    if (!data?.assets?.nextPage) break;
    page++;
  }
  return items;
}

async function collectLocalDirCandidates(localDir, limit) {
  const entries = await fsp.readdir(localDir);
  const exts = new Set(['.jpg', '.jpeg', '.png', '.webp', '.heic']);
  const out = [];
  for (const name of entries.sort()) {
    if (out.length >= limit) break;
    const ext = path.extname(name).toLowerCase();
    if (!exts.has(ext)) continue;
    const full = path.join(localDir, name);
    out.push({
      id: `local:${name}`,
      name,
      localPath: full,
      isVideo: false,
      durationMs: 0,
      isScreenshot: /screenshot/i.test(name),
    });
  }
  return out;
}

async function createOrUpdateImmichAlbum(baseUrl, apiKey, albumName, assetIds) {
  if (assetIds.length === 0) return null;
  const headers = {
    'x-api-key': apiKey,
    'Content-Type': 'application/json',
    Accept: 'application/json',
  };
  const listResp = await fetch(`${baseUrl}/api/albums`, { headers });
  if (!listResp.ok) throw new Error(`Immich GET /api/albums failed: HTTP ${listResp.status}`);
  const albums = await listResp.json();
  let target = Array.isArray(albums) ? albums.find((a) => a.albumName === albumName) : null;

  if (!target) {
    const createResp = await fetch(`${baseUrl}/api/albums`, {
      method: 'POST',
      headers,
      body: JSON.stringify({
        albumName,
        description: 'Auto-organized by Memeget 3-Stage Local Meme Gate (non-destructive)',
        assetIds,
      }),
    });
    if (!createResp.ok) throw new Error(`Immich POST /api/albums failed: HTTP ${createResp.status}`);
    target = await createResp.json();
    return { albumId: target.id, created: true, addedCount: assetIds.length };
  }

  const putResp = await fetch(`${baseUrl}/api/albums/${target.id}/assets`, {
    method: 'PUT',
    headers,
    body: JSON.stringify({ ids: assetIds }),
  });
  if (!putResp.ok) throw new Error(`Immich PUT /api/albums/${target.id}/assets failed: HTTP ${putResp.status}`);
  return { albumId: target.id, created: false, addedCount: assetIds.length };
}

function escHtml(s) {
  return String(s ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function buildReviewHtml(records, summary) {
  const resolvedOut = path.resolve(OUT_ROOT) + path.sep;
  const cards = records.map((r) => {
    const absLocal = path.resolve(String(r.localPath || ''));
    const relImg = absLocal.startsWith(resolvedOut) ? path.relative(OUT_ROOT, absLocal).split(path.sep).map(encodeURIComponent).join('/') : '';
    const badgeColor = r.finalBucket === 'accepted' ? '#22e084' : r.finalBucket === 'borderline' ? '#ffb833' : '#ff525c';
    const tagsStr = (r.tags || []).map((t) => escHtml(t.label)).join(', ');
    const bucketAttr = escHtml(r.finalBucket);
    return `<div class="card" data-bucket="${bucketAttr}">
      ${relImg ? `<img src="${escHtml(relImg)}" loading="lazy" />` : ''}
      <div class="meta">
        <span class="badge" style="color:${badgeColor}">${escHtml(String(r.finalBucket).toUpperCase())} · ${(r.finalProb * 100).toFixed(0)}% (${escHtml(r.decidedBy)})</span>
        <div class="name">${escHtml(r.name)}</div>
        ${tagsStr ? `<div class="tags">${tagsStr}</div>` : ''}
        ${r.ocr ? `<div class="ocr">${escHtml(r.ocr.slice(0, 140))}</div>` : ''}
      </div>
    </div>`;
  }).join('\n');
  return `<!doctype html>
<html><head><meta charset="utf-8"><title>Memeget Immich Gate Dry-Run Review</title>
<style>
  body { background:#0D0F14; color:#fff; font-family:-apple-system,BlinkMacSystemFont,sans-serif; margin:20px; }
  h1 span { color:#D4FF00; }
  .stats { display:flex; gap:16px; margin-bottom:18px; flex-wrap:wrap; }
  .stat { background:#161922; padding:10px 16px; border-radius:10px; border:1px solid rgba(255,255,255,0.1); }
  .grid { display:grid; grid-template-columns:repeat(auto-fill, minmax(210px, 1fr)); gap:12px; }
  .card { background:#161922; border-radius:10px; overflow:hidden; border:1px solid rgba(255,255,255,0.08); }
  .card img { width:100%; height:190px; object-fit:cover; background:#000; }
  .meta { padding:10px; font-size:12px; }
  .badge { font-weight:700; font-size:11px; }
  .name { color:#aaa; margin-top:4px; word-break:break-all; }
  .tags { color:#D4FF00; margin-top:4px; }
  .ocr { color:#8892b0; margin-top:4px; font-size:11px; }
</style></head><body>
<h1>Memeget<span>.</span> 3-Stage Gate Review</h1>
<div class="stats">
  <div class="stat">Total Scanned: <b>${summary.totalScanned}</b></div>
  <div class="stat">Accepted Memes: <b style="color:#22e084">${summary.acceptedCount}</b> (S1: ${summary.stage1Accepted}, D1: ${summary.stage2D1Accepted}, MiniCPM: ${summary.stage3VlmAccepted})</div>
  <div class="stat">Unresolved: <b style="color:#ffb833">${summary.borderlineCount}</b></div>
  <div class="stat">Rejected Non-Memes: <b style="color:#ff525c">${summary.rejectedCount}</b></div>
</div>
<div class="grid">${cards}</div>
</body></html>`;
}

async function main() {
  const opts = parseArgs(process.argv);
  await fsp.mkdir(OUT_ROOT, { recursive: true });
  await fsp.mkdir(PACK_MEDIA_DIR, { recursive: true });

  const immichCfg = opts.localDir ? { baseUrl: '', apiKey: '' } : loadImmichConfig(opts.insecureHttp);
  let candidates = [];
  let sourceMode = 'local';

  if (opts.localDir) {
    candidates = await collectLocalDirCandidates(opts.localDir, opts.limit);
  } else {
    if (!immichCfg.baseUrl || !immichCfg.apiKey) {
      throw new Error(
        'Missing IMMICH_BASE_URL or IMMICH_API_KEY in gitignored .immich.env. ' +
        'Set both in .immich.env (never in chat) or pass --local-dir <folder> for offline fixture testing.'
      );
    }
    sourceMode = 'immich';
    console.log(`[immich] Connecting to ${immichCfg.baseUrl} (limit=${opts.limit}, dryRun=${!opts.commitAlbum})...`);
    candidates = await fetchImmichCandidates(immichCfg.baseUrl, immichCfg.apiKey, opts.limit);
  }

  console.log(`[pipeline] Loaded ${candidates.length} candidate items from ${sourceMode}.`);
  if (candidates.length === 0) return;

  const s2Path = await ensureArtifact(ARTIFACTS.s2Image);
  const d1Path = opts.skipD1 ? '' : await ensureArtifact(ARTIFACTS.d1Omni);

  await ensureSimulatorBooted(opts.simDevice);
  const simFwDir = path.join(REPO_ROOT, 'native/runtime/build/executorch/third-party/ios/ExecutorchLib.xcframework/ios-arm64-simulator');
  const embedder = new LineWorker('xcrun', ['simctl', 'spawn', opts.simDevice, SIM_EMBEDDER_BIN, s2Path], {
    SIMCTL_CHILD_DYLD_FRAMEWORK_PATH: simFwDir,
  });
  const gateArgs = ['--open-vocab', OPEN_VOCAB_BIN, '--verifier', VERIFIER_BIN, '--anchors', ANCHORS_BIN];
  if (d1Path) gateArgs.push('--d1-model', d1Path);
  const gateWorker = new LineWorker(GATE_WORKER_BIN, gateArgs);

  const records = [];
  const uncertainTextQueue = [];
  const uncertainVisualQueue = [];

  try {
    // Stage 0 + Stage 1: MobileCLIP-S2 + Apple Vision ANE OCR + Gutter Detector + 6,028 OpenVocabConcepts
    for (let i = 0; i < candidates.length; i++) {
      const c = candidates[i];
      const embRes = await embedder.send(c.localPath);
      const vec = embRes.ok ? embRes.vec : null;
      const s1 = await gateWorker.send(JSON.stringify({
        op: 'stage1',
        path: c.localPath,
        isVideo: c.isVideo,
        durationMs: c.durationMs,
        isScreenshot: c.isScreenshot,
        ordinaryAnchorCos: 0.0,
        vec,
      }));
      if (!s1.ok) continue;

      const rec = {
        id: c.id,
        name: c.name,
        localPath: c.localPath,
        stage1Verdict: s1.verdict,
        stage1Prob: s1.prob,
        finalBucket: s1.verdict === 1 ? 'accepted' : s1.verdict === 0 ? 'rejected' : 'borderline',
        finalProb: s1.prob,
        decidedBy: s1.verdict === 1 ? 'stage1-clip+layout' : s1.verdict === 0 ? s1.reason : (s1.verdict === 2 ? 'pending-stage2-d1' : 'pending-stage3-minicpm'),
        panelCount: s1.panelCount,
        hasGutter: s1.hasGutter,
        ocr: s1.ocr || '',
        caption: '',
        extraTerms: '',
        tags: s1.tags || [],
        d1Prompt: s1.d1Prompt || '',
        vec,
      };
      records.push(rec);
      if (s1.verdict === 2 && d1Path && rec.d1Prompt) {
        uncertainTextQueue.push(rec);
      } else if (s1.verdict === 3 && !opts.skipVlm) {
        uncertainVisualQueue.push(rec);
      }
    }

    // Stage 2: Batched LiquidAI/d1-omni-600M Metal GPU System-1 Decision Pass on UNCERTAIN_TEXT screenshots
    if (uncertainTextQueue.length > 0) {
      console.log(`[stage2-d1] Running fine-tuned d1-omni-600M Metal pass on ${uncertainTextQueue.length} borderline text screenshots...`);
      for (const rec of uncertainTextQueue) {
        const d1Res = await gateWorker.send(JSON.stringify({ op: 'd1_gate', prompt: rec.d1Prompt }));
        if (d1Res.ok) {
          rec.finalProb = d1Res.prob;
          rec.finalBucket = d1Res.isMeme ? 'accepted' : 'rejected';
          rec.decidedBy = d1Res.isMeme ? 'stage2-d1-omni-meme' : 'stage2-d1-omni-rejected';
        }
        delete rec.d1Prompt;
      }
    }
  } finally {
    embedder.close();
    gateWorker.close();
  }

  // Stage 3: Batched MiniCPM-V 4.7 1B Metal GPU 1-Token Gate + Zero-Re-Encode Two-Domain Continuation on UNCERTAIN_VISUAL
  if (uncertainVisualQueue.length > 0 && !opts.skipVlm) {
    const vlmModelPath = await ensureArtifact(ARTIFACTS.minicpmModel);
    const convProjPath = path.join(MODELS_DIR, ARTIFACTS.minicpmProjConverted.filename);
    if (!fs.existsSync(convProjPath) || fs.statSync(convProjPath).size !== ARTIFACTS.minicpmProjConverted.bytes) {
      const bf16Path = await ensureArtifact(ARTIFACTS.minicpmProjBf16);
      console.log('[models] Converting MiniCPM-V projector BF16 -> F16 once...');
      const convWorker = new LineWorker(GATE_WORKER_BIN, []);
      const partProj = `${convProjPath}.part`;
      const convRes = await convWorker.send(JSON.stringify({ op: 'convert_proj', src: bf16Path, dst: partProj }));
      convWorker.close();
      if (!convRes.ok) throw new Error('Failed converting MiniCPM-V projector');
      const convHash = await sha256File(partProj);
      if (convHash !== ARTIFACTS.minicpmProjConverted.sha256) {
        await fsp.unlink(partProj).catch(() => {});
        throw new Error('Converted MiniCPM-V projector SHA-256 mismatch');
      }
      await fsp.rename(partProj, convProjPath);
      await fsp.unlink(bf16Path).catch(() => {});
    }

    console.log(`[stage3-minicpm] Running MiniCPM-V 4.7 1B Metal 1-token gate + two-domain describer on ${uncertainVisualQueue.length} visual borderlines...`);
    const vlmWorker = new LineWorker(GATE_WORKER_BIN, ['--vlm-model', vlmModelPath, '--vlm-proj', convProjPath]);
    try {
      for (const rec of uncertainVisualQueue) {
        const hints = (rec.ocr && rec.ocr.trim().length > 0)
          ? (rec.tags || []).map((t) => t.label).join(', ')
          : (rec.tags || []).filter((t) => (t.score || 0) >= 0.76).map((t) => t.label).join(', ');
        const vlmRes = await vlmWorker.send(JSON.stringify({
          op: 'vlm_gate',
          path: rec.localPath,
          ocr: rec.ocr,
          hints,
        }));
        if (vlmRes.ok) {
          rec.finalProb = vlmRes.prob;
          if (vlmRes.isMeme) {
            rec.finalBucket = 'accepted';
            rec.decidedBy = 'stage3-minicpm-yes';
            const twoDom = parseTwoDomainReply(vlmRes.description);
            rec.caption = twoDom.combinedCaption;
            rec.extraTerms = twoDom.searchTerms;
            const existingLbls = new Set((rec.tags || []).map((t) => t.label.toLowerCase()));
            for (const nt of twoDom.tags) {
              if (!existingLbls.has(nt.label)) {
                rec.tags.push(nt);
                existingLbls.add(nt.label);
              }
            }
          } else {
            rec.finalBucket = 'rejected';
            rec.decidedBy = 'stage3-minicpm-no';
          }
        }
      }
    } finally {
      vlmWorker.close();
    }
  }

  const accepted = records.filter((r) => r.finalBucket === 'accepted');
  const borderline = records.filter((r) => r.finalBucket === 'borderline');
  const rejected = records.filter((r) => r.finalBucket === 'rejected');

  // Export byte-exact Memeget v2 Collection ZIP (`manifest.json` + `media/...`) matching CollectionManifest.kt & ZipImporter.kt!
  const PRIMARY_MODEL = 'mobileclip-s2@512:dd6a357f:e7576174:8e019f93:recipe-et0.9.2';
  await fsp.rm(PACK_DIR, { recursive: true, force: true });
  await fsp.mkdir(PACK_MEDIA_DIR, { recursive: true });
  const packMemes = [];
  for (const r of accepted) {
    const safeName = `${String(r.id).replace(/[^a-zA-Z0-9._-]/g, '_')}_${path.basename(r.name).replace(/[^a-zA-Z0-9._-]/g, '_')}`;
    const mediaDest = path.join(PACK_MEDIA_DIR, safeName);
    await fsp.copyFile(r.localPath, mediaDest);
    const plainEmbedding = Array.isArray(r.vec) && r.vec.length === 512 ? Array.from(r.vec, Number) : null;
    packMemes.push({
      file: `media/${safeName}`,
      name: r.name,
      kind: 'image',
      ocr: r.ocr || '',
      caption: r.caption || '',
      extraTerms: r.extraTerms || '',
      twoDomainProvenance: r.decidedBy === 'stage3-minicpm-yes' ? 'minicpm-v-4.7-1b' : '',
      transcript: '',
      tags: (r.tags || []).map((t) => ({
        label: t.label,
        category: t.category || 'topic',
        score: Number(t.score ?? 0.8),
        source: t.source || 'open_vocab',
        confirmation: t.confirmation || 'unreviewed',
      })),
      embedding: plainEmbedding,
      embeddingModel: PRIMARY_MODEL,
      pending: false,
    });
  }
  await fsp.writeFile(
    path.join(PACK_DIR, 'manifest.json'),
    JSON.stringify({
      format: 'memeget-stage3-pack',
      version: 3,
      model: PRIMARY_MODEL,
      memes: packMemes,
    }, null, 2)
  );
  const zipOutPath = path.join(OUT_ROOT, 'immich-memes-collection.zip');
  await fsp.rm(zipOutPath, { force: true });
  execFileSync('/usr/bin/zip', ['-r', '-q', zipOutPath, 'manifest.json', 'media'], { cwd: PACK_DIR });
  const summary = {
    sourceMode,
    dryRun: !opts.commitAlbum,
    totalScanned: records.length,
    acceptedCount: accepted.length,
    stage1Accepted: accepted.filter((r) => r.decidedBy.startsWith('stage1')).length,
    stage2D1Accepted: accepted.filter((r) => r.decidedBy.startsWith('stage2')).length,
    stage3VlmAccepted: accepted.filter((r) => r.decidedBy.startsWith('stage3')).length,
    borderlineCount: borderline.length,
    rejectedCount: rejected.length,
  };

  let albumOutcome = null;
  if (opts.commitAlbum && sourceMode === 'immich') {
    albumOutcome = await createOrUpdateImmichAlbum(
      immichCfg.baseUrl,
      immichCfg.apiKey,
      opts.albumName,
      accepted.map((r) => r.id)
    );
    summary.albumOutcome = albumOutcome;
  }

  const cleanRecords = records.map(({ vec, d1Prompt, ...rest }) => rest);
  const manifestPath = path.join(OUT_ROOT, 'dry_run_manifest.json');
  await fsp.writeFile(
    manifestPath,
    JSON.stringify({
      generatedAt: new Date().toISOString(),
      summary,
      acceptedIds: accepted.map((r) => r.id),
      borderlineIds: borderline.map((r) => r.id),
      rejectedIds: rejected.map((r) => r.id),
      items: cleanRecords,
    }, null, 2)
  );

  const htmlPath = path.join(OUT_ROOT, 'review.html');
  await fsp.writeFile(htmlPath, buildReviewHtml(records, summary));

  console.log('[done] Summary:', JSON.stringify(summary));
  console.log(`[done] Manifest: ${manifestPath}`);
  console.log(`[done] Visual Review Gallery: ${htmlPath}`);
  console.log(`[done] Memeget v2 Collection ZIP: ${zipOutPath}`);
}

main().catch((err) => {
  console.error('[immich-extract] ERROR:', err.message);
  process.exit(1);
});
