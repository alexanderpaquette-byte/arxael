#!/usr/bin/env node
'use strict';
// Fetch + cache the Arxael engine (the JVM core distribution). The engine version is resolved DYNAMICALLY
// from GitHub releases — this launcher is deliberately decoupled from the fast-moving product version, so
// cutting a new public release never requires republishing npm. Resolution order:
//   1. cached engine in ~/.arxael/dist/<ver> (reused unless --force / `arxael upgrade`)
//   2. ARXAEL_CORE_TARBALL — a local .tar.gz (offline / testing)
//   3. the latest GitHub release that has an `arxael-core-*.tar.gz` asset (walks back if the newest lacks one)
// ARXAEL_ENGINE_VERSION pins a specific version. Integrity is checked against the published .sha256 sidecar.
const fs = require('fs'), os = require('os'), path = require('path'), https = require('https'), http = require('http'), crypto = require('crypto');
const { execFileSync } = require('child_process');

const REPO = process.env.ARXAEL_REPO || 'alexanderpaquette-byte/arxael';
const API_BASE = (process.env.ARXAEL_API_BASE || 'https://api.github.com').replace(/\/$/, ''); // GitHub Enterprise override
const HOME = process.env.ARXAEL_HOME || path.join(os.homedir(), '.arxael');
const DIST = path.join(HOME, 'dist');
const ASSET_RE = /^arxael-core-(.+)\.tar\.gz$/;

function log(m) { process.stderr.write(`[arxael] ${m}\n`); }

function httpGet(url, headers) {
  return new Promise((resolve, reject) => {
    const mod = url.startsWith('http://') ? http : https;
    const req = mod.get(url, { headers: Object.assign({ 'User-Agent': 'arxael-cli' }, headers || {}) }, (res) => {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
        res.resume(); return resolve(httpGet(res.headers.location, headers)); // follow GitHub -> CDN redirect
      }
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => resolve({ status: res.statusCode, body: Buffer.concat(chunks) }));
    });
    req.on('error', reject);
    req.setTimeout(30000, () => req.destroy(new Error('network timeout')));
  });
}

async function resolveRelease() {
  const pin = process.env.ARXAEL_ENGINE_VERSION;
  const r = await httpGet(`${API_BASE}/repos/${REPO}/releases?per_page=30`, { Accept: 'application/vnd.github+json' });
  if (r.status !== 200) throw new Error(`GitHub API returned ${r.status}`);
  const releases = JSON.parse(r.body.toString());
  for (const rel of releases) {                                   // releases are newest-first
    if (rel.draft || rel.prerelease) continue;
    if (pin && rel.tag_name !== `v${pin}` && rel.tag_name !== pin) continue;
    const asset = (rel.assets || []).find((a) => ASSET_RE.test(a.name));
    if (!asset) continue;                                         // newest release missing the artifact -> walk back
    const sha = (rel.assets || []).find((a) => a.name === asset.name + '.sha256');
    return { ver: ASSET_RE.exec(asset.name)[1], url: asset.browser_download_url, shaUrl: sha && sha.browser_download_url };
  }
  throw new Error(pin ? `no release ${pin} with a core artifact` : 'no published release has a core artifact yet');
}

function cachedVersion() {
  try { return fs.readFileSync(path.join(DIST, 'current'), 'utf8').trim() || null; } catch { return null; }
}

function binFor(ver) { return path.join(DIST, ver, 'core', 'bin', 'core'); }

function cachedEngine() {
  const v = cachedVersion();
  if (v && fs.existsSync(binFor(v))) return binFor(v);
  return null;
}

async function install(opts) {
  const force = opts && opts.force;
  if (!force) { const c = cachedEngine(); if (c) return c; }
  fs.mkdirSync(DIST, { recursive: true });

  let ver, tarBuf, shaBuf;
  if (process.env.ARXAEL_CORE_TARBALL) {                          // local file: offline / testing
    ver = process.env.ARXAEL_ENGINE_VERSION || 'local';
    tarBuf = fs.readFileSync(process.env.ARXAEL_CORE_TARBALL);
  } else {
    const rel = await resolveRelease();
    ver = rel.ver;
    if (!force && fs.existsSync(binFor(ver))) { writeCurrent(ver); return binFor(ver); }
    log(`fetching engine ${ver} (~5 MB)…`);
    const t = await httpGet(rel.url); if (t.status !== 200) throw new Error(`engine download failed (${t.status})`);
    tarBuf = t.body;
    if (rel.shaUrl) { const s = await httpGet(rel.shaUrl); if (s.status === 200) shaBuf = s.body; }
  }

  if (shaBuf) {
    const want = shaBuf.toString().trim().split(/\s+/)[0];
    const got = crypto.createHash('sha256').update(tarBuf).digest('hex');
    if (want && want !== got) throw new Error(`engine checksum mismatch (expected ${want.slice(0, 12)}…, got ${got.slice(0, 12)}…)`);
  } else if (!process.env.ARXAEL_CORE_TARBALL) {
    log('warning: no checksum sidecar for this release — skipping integrity check');
  }

  const vdir = path.join(DIST, ver);
  fs.rmSync(vdir, { recursive: true, force: true });
  fs.mkdirSync(vdir, { recursive: true });
  const tmp = path.join(vdir, 'engine.tar.gz');
  fs.writeFileSync(tmp, tarBuf);
  execFileSync('tar', ['xzf', tmp, '-C', vdir]);
  fs.unlinkSync(tmp);
  if (!fs.existsSync(binFor(ver))) throw new Error('engine archive did not contain core/bin/core');
  writeCurrent(ver);
  return binFor(ver);
}

function writeCurrent(ver) { fs.writeFileSync(path.join(DIST, 'current'), ver + '\n'); }

module.exports = { install, cachedEngine, cachedVersion };

if (require.main === module) {
  const postinstall = process.argv.includes('--postinstall');
  install({ force: process.argv.includes('--force') })
    .then((bin) => log(`engine ready: ${bin}`))
    .catch((e) => {
      // On postinstall a fetch failure must NOT fail `npm install` — defer to first run.
      if (postinstall) { log(`engine will be fetched on first run (${e.message})`); process.exit(0); }
      log(`error: ${e.message}`); process.exit(1);
    });
}
