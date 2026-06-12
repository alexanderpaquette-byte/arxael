#!/usr/bin/env node
'use strict';
// The `arxael` command (npm). A thin launcher: verify Java, fetch/cache the engine (see lib/ensure-core.js),
// then delegate to the engine's own CLI. The engine version is resolved dynamically, so this launcher rarely
// needs republishing even as the product moves fast.
const path = require('path'), { spawnSync } = require('child_process');
const core = require('../lib/ensure-core');
const pkg = require('../package.json');
const REPO = process.env.ARXAEL_REPO || 'alexanderpaquette-byte/arxael';

function javaMajor() {
  const candidates = [process.env.JAVA_HOME && path.join(process.env.JAVA_HOME, 'bin', 'java'), 'java'].filter(Boolean);
  for (const java of candidates) {
    const r = spawnSync(java, ['-version'], { encoding: 'utf8' });
    if (r.error) continue;
    const out = (r.stderr || '') + (r.stdout || '');
    let m = out.match(/version "(\d+)(?:\.(\d+))?/);            // "21.0.11" -> 21 ; legacy "1.8.0_392" -> 8
    if (m) { const major = parseInt(m[1], 10); return major === 1 && m[2] ? parseInt(m[2], 10) : major; }
    m = out.match(/(?:openjdk|java)\s+(\d+)/i);                 // e.g. "openjdk 21 2023-09-19"
    if (m) return parseInt(m[1], 10);
  }
  return null;
}

function failJava(jv) {
  console.error(`\narxael needs Java 21+ — the engine is a JVM service. ${jv ? 'Found Java ' + jv + '.' : 'No Java found on PATH.'}`);
  console.error('Install one, then re-run:');
  console.error('  macOS          brew install openjdk@21');
  console.error('  Ubuntu/Debian  sudo apt-get install -y openjdk-21-jre-headless');
  console.error('  Fedora/RHEL    sudo dnf install -y java-21-openjdk-headless');
  console.error('  any platform   https://adoptium.net/temurin/releases/?version=21\n');
  process.exit(1);
}

// Best-effort: ask the RUNNING daemon its version (loopback /health). Never throws; returns null on any failure.
async function daemonVersion() {
  try {
    const fs = require('fs'), http = require('http'), os = require('os');
    const state = process.env.ARXAEL_STATE_DIR || path.join(os.homedir(), '.arxael');
    const port = parseInt(String(fs.readFileSync(path.join(state, 'port'), 'utf8')).trim(), 10);
    if (!port) return null;
    return await new Promise((resolve) => {
      const req = http.get({ host: '127.0.0.1', port, path: '/health', timeout: 1500 }, (res) => {
        let b = ''; res.on('data', (c) => { b += c; }); res.on('end', () => {
          const m = b.match(/"version":"([^"]+)"/); resolve(m ? m[1] : null);
        });
      });
      req.on('error', () => resolve(null));
      req.on('timeout', () => { req.destroy(); resolve(null); });
    });
  } catch { return null; }
}

(async () => {
  const args = process.argv.slice(2);
  const cmd = args[0] || '';

  if (cmd === '--version' || cmd === '-v' || cmd === 'version') {
    const engine = core.cachedVersion();
    console.log(`arxael CLI ${pkg.version}`);
    console.log(`engine     ${engine || 'not installed yet (run `arxael up`)'}`);
    const dv = await daemonVersion();
    if (dv) {
      console.log(`daemon     ${dv} (running)`);
      if (engine && dv !== engine) {
        console.log(`\n⚠ the running daemon is ${dv} but ${engine} is installed — restart to adopt: arxael stop && arxael up`);
      }
    }
    return;
  }
  if (cmd === 'verify' || cmd === 'bench') {
    console.error(`'arxael ${cmd}' builds from source — clone https://github.com/${REPO} and run scripts/arxael ${cmd}.`);
    process.exit(2);
  }

  const jv = javaMajor();
  if (jv === null || jv < 21) failJava(jv);

  let coreBin;
  try {
    coreBin = await core.install({ force: cmd === 'upgrade' });
  } catch (e) {
    console.error(`Could not fetch the arxael engine: ${e.message}`);
    console.error('Check your connection, or pin a known version with ARXAEL_ENGINE_VERSION=<x.y.z>.');
    process.exit(1);
  }

  if (cmd === 'upgrade') { console.log(`engine updated to ${core.cachedVersion()}`); return; }

  const script = path.join(__dirname, '..', 'scripts', 'arxael');
  const env = Object.assign({}, process.env, { ARXAEL_CORE_BIN: coreBin });
  const r = spawnSync('bash', [script, ...args], { stdio: 'inherit', env });
  process.exit(r.status == null ? 1 : r.status);
})();
