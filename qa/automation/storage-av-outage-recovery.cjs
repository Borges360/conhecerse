const { chromium } = require('../../apps/web/node_modules/@playwright/test');
const { execFileSync } = require('node:child_process');
const crypto = require('node:crypto');
const fs = require('node:fs');
const baseURL = 'http://conhecerse.lab';
const statePath = '.local/qa-admin-storageState.json';
const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6N3sAAAAASUVORK5CYII=', 'base64');
if (process.env.E2E_ISOLATED_SYNTHETIC !== 'true' || process.env.E2E_TARGET_DEV_LAB !== 'conhecerse.lab') throw new Error('SYNTHETIC_LAB_GUARD');
if (!fs.existsSync(statePath)) throw new Error('QA_ADMIN_STATE_MISSING');
function kubectl(args) { return execFileSync('kubectl', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }); }
const ingress = JSON.parse(kubectl(['-n', 'dev', 'get', 'ingress', 'conhecerse', '-o', 'json']));
if (!(ingress.spec.rules || []).some(rule => rule.host === 'conhecerse.lab')) throw new Error('DEV_LAB_INGRESS_GUARD');
const deployment = JSON.parse(kubectl(['-n', 'dev', 'get', 'deployment', 'conhecerse-av', '-o', 'json']));
const originalReplicas = Number(deployment.spec.replicas || 0);
if (originalReplicas !== 1 || Number(deployment.status.readyReplicas || 0) !== 1) throw new Error('AV_REQUIRES_ONE_READY_REPLICA');
function waitForEndpointsEmpty() {
  const deadline = Date.now() + 60000;
  while (Date.now() < deadline) {
    const endpoints = JSON.parse(kubectl(['-n', 'dev', 'get', 'endpoints', 'conhecerse-av', '-o', 'json']));
    if (!endpoints.subsets || endpoints.subsets.length === 0) return;
    Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 500);
  }
  throw new Error('AV_ENDPOINTS_NOT_REMOVED');
}
async function main() {
  const browser = await chromium.launch({ headless: true });
  let scaledDown = false;
  let page;
  let failure;
  let recovery = false;
  try {
    const context = await browser.newContext({ storageState: statePath });
    page = await context.newPage();
    await page.goto(baseURL + '/app');
    const session = await page.request.get(baseURL + '/api/session').then(r => r.json());
    if (!session.authenticated || session.accessToken || session.refreshToken) throw new Error('BFF_SESSION_INVALID');
    async function api(path, body, method = 'POST') {
      return page.evaluate(async ({ path, body, method, key }) => {
        const current = await fetch('/api/session').then(r => r.json());
        const response = await fetch('/api/bff' + path, {
          method,
          headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': current.csrfToken, 'Idempotency-Key': key },
          body: body === undefined ? undefined : JSON.stringify(body),
        });
        let data = {};
        try { data = await response.json(); } catch {}
        return { status: response.status, data };
      }, { path, body, method, key: crypto.randomUUID() });
    }
    function ok(result, label) { if (result.status < 200 || result.status >= 300) throw new Error(label + '_HTTP_' + result.status + '_' + String(result.data.code || 'UNKNOWN')); return result.data; }
    const runId = Date.now();
    const org = ok(await api('/v1/orgs', { displayName: 'QA sintético AV outage ' + runId, slug: 'qa-av-' + runId, legalPersonType: 'PF', timezone: 'America/Sao_Paulo', ownerIsPsychologist: true }), 'ORG');
    const base = '/v1/orgs/' + org.id;
    const members = ok(await api(base + '/members', undefined, 'GET'), 'MEMBERS').items || [];
    const owner = members.find(m => (m.roles || []).includes('OWNER') && (m.roles || []).includes('PSYCHOLOGIST'));
    if (!owner?.userId) throw new Error('OWNER_MISSING');
    const professional = ok(await api(base + '/professionals', { userId: owner.userId, crpUf: 'RS', crpNumber: 'SYNAV-' + runId, displayName: 'Profissional QA AV outage ' + runId }), 'PROFESSIONAL');
    ok(await api(base + '/professionals/' + professional.id + '/review', { status: 'VERIFIED', evidence: 'SYNTHETIC_AV_OUTAGE_RECOVERY_TEST' }), 'REVIEW');
    const patient = ok(await api(base + '/patients', { fullName: 'Paciente QA AV outage ' + runId, professionalId: professional.id }), 'PATIENT');
    const upload = ok(await api(base + '/files/uploads', { filename: 'synthetic.png', contentType: 'image/png', sizeBytes: png.length, sha256: crypto.createHash('sha256').update(png).digest('hex'), classification: 'SENSITIVE_CLINICAL', patientId: patient.id }), 'UPLOAD_INIT');
    const put = await page.request.put(upload.uploadUrl, { headers: { 'Content-Type': 'image/png' }, data: png });
    if (!put.ok()) throw new Error('QUARANTINE_PUT_HTTP_' + put.status());
    scaledDown = true;
    kubectl(['-n', 'dev', 'scale', 'deployment/conhecerse-av', '--replicas=0']);
    waitForEndpointsEmpty();
    const unavailable = await api(base + '/files/' + upload.id + '/complete');
    if (unavailable.status !== 503 || unavailable.data.code !== 'MALWARE_SCANNER_UNAVAILABLE') throw new Error('AV_OUTAGE_NOT_FAIL_CLOSED_' + unavailable.status + '_' + String(unavailable.data.code || 'UNKNOWN'));
    kubectl(['-n', 'dev', 'scale', 'deployment/conhecerse-av', `--replicas=${originalReplicas}`]);
    kubectl(['-n', 'dev', 'rollout', 'status', 'deployment/conhecerse-av', '--timeout=120s']);
    scaledDown = false;
    const ready = ok(await api(base + '/files/' + upload.id + '/complete'), 'RECOVERY_COMPLETE');
    if (ready.status !== 'READY') throw new Error('AV_RECOVERY_FILE_STATUS_' + ready.status);
    const ticket = ok(await api(base + '/files/' + upload.id + '/download'), 'RECOVERY_DOWNLOAD');
    const downloaded = await page.request.get(ticket.downloadUrl);
    const bytes = Buffer.from(await downloaded.body());
    if (downloaded.status() !== 200 || !bytes.equals(png)) throw new Error('RECOVERY_CHECKSUM_MISMATCH');
    recovery = true;
    console.log(JSON.stringify({ outageStatus: unavailable.status, outageCode: unavailable.data.code, fileRemainedRetryable: true, recoveryReady: ready.status === 'READY', recoveryChecksum: true, syntheticOnly: true }));
  } catch (error) {
    failure = error;
  } finally {
    if (scaledDown) {
      try {
        kubectl(['-n', 'dev', 'scale', 'deployment/conhecerse-av', `--replicas=${originalReplicas}`]);
        kubectl(['-n', 'dev', 'rollout', 'status', 'deployment/conhecerse-av', '--timeout=120s']);
      } catch (restoreError) {
        failure = new Error('ANTIVIRUS_RESTORE_FAILED_' + String(restoreError.message || restoreError).slice(0, 160));
      }
    }
    await browser.close();
  }
  if (failure) throw failure;
  if (!recovery) throw new Error('RECOVERY_NOT_CONFIRMED');
}
main().catch(error => { console.error(String(error.message || error).replace(/https?:\/\/[^\s]+/g, '[URL]')); process.exit(1); });
