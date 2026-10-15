"""Collect sanitized evidence only; never copy fixtures, session states or raw logs."""
import collections
import datetime as dt
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
LOCAL = ROOT / '.local'

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def junit(files):
    summary = dict(tests=0, failures=0, errors=0, skipped=0)
    reports = []
    for path in files:
        doc = ET.parse(path).getroot()
        suites = [doc] if doc.tag == 'testsuite' else list(doc.findall('testsuite'))
        counts = {key: sum(int(s.get(key, 0)) for s in suites) for key in summary}
        for key in summary:
            summary[key] += counts[key]
        reports.append({'path': path.relative_to(ROOT).as_posix(), 'sha256': digest(path), **counts})
    return {**summary, 'reports': reports}

result = {'recordedAt': dt.datetime.now(dt.timezone.utc).isoformat(),
          'environment': 'isolated localhost; synthetic data; real PostgreSQL 17.11 and Keycloak 26.8.1',
          'releaseApproved': False, 'remoteDeploymentExecuted': False}
result['backend'] = junit(sorted((ROOT / 'backend/target/surefire-reports').glob('TEST-*.xml')))
result['webUnit'] = junit([ROOT / 'apps/web/test-results/unit.xml'])
result['browser'] = junit([ROOT / 'apps/web/test-results/e2e.xml'])
classes = ('scheduling/SchedulingModule', 'billing/BillingModule', 'clinical/ClinicalModule', 'shared/Access')
coverage_path = ROOT / 'backend/target/site/jacoco/jacoco.xml'
mutation_path = ROOT / 'backend/target/pit-reports/mutations.xml'
coverage = ET.parse(coverage_path).getroot()
mutation = ET.parse(mutation_path).getroot()
result['criticalQuality'] = {}
for name in classes:
    full = 'com/psicocare/' + name
    cls = next(c for c in coverage.findall('.//class') if c.get('name') == full)
    line = next(c for c in cls.findall('counter') if c.get('type') == 'LINE')
    covered, missed = int(line.get('covered')), int(line.get('missed'))
    status = collections.Counter(m.get('status') for m in mutation if m.findtext('mutatedClass') == full.replace('/', '.'))
    total = sum(status.values())
    detected = sum(1 for m in mutation if m.findtext('mutatedClass') == full.replace('/', '.') and m.get('detected') == 'true')
    result['criticalQuality'][name] = {
        'coveredLines': covered, 'totalLines': covered + missed,
        'lineCoveragePercent': round(covered * 100 / (covered + missed), 2),
        'mutations': total, 'detected': detected, 'statuses': dict(status),
        'mutationScorePercent': round(detected * 100 / total, 2),
        'passed': covered / (covered + missed) >= .85 and detected / total >= .70,
    }
result['qualityReportHashes'] = {'jacoco': digest(coverage_path), 'pit': digest(mutation_path)}
result['journeys'] = {}
for name, allowed in {
    'oidc': ('keycloak', 'authorizationCodePkce', 'mfaTotp', 'opaqueHttpOnlyCookie', 'organizationCreated', 'finishedAt'),
    'vertical': ('oidc', 'database', 'bffCsrfDenied', 'professionalReviewed', 'patientCreated', 'appointmentScheduled', 'clinicalNoteSigned', 'manualInvoicePaid', 'publicContentReviewedPublished', 'finishedAt'),
}.items():
    data = json.loads((LOCAL / f'{name}-evidence.json').read_text(encoding='utf-8'))
    result['journeys'][name] = {key: data[key] for key in allowed}
result['securityScans'] = {}
for name, filename in {'source': 'trivy-source-final.json', 'javaBase': 'java-image.json', 'iamBase': 'iam-image.json', 'avBase': 'av-image.json'}.items():
    path = LOCAL / filename
    data = json.loads(path.read_text(encoding='utf-8'))
    vulnerabilities = [v for r in data.get('Results', []) for v in r.get('Vulnerabilities', []) if v.get('Severity') in ('HIGH', 'CRITICAL')]
    secrets = [s for r in data.get('Results', []) for s in r.get('Secrets', []) if s.get('Severity') in ('HIGH', 'CRITICAL')]
    result['securityScans'][name] = {'tool': data.get('Trivy', {}).get('Version'), 'createdAt': data.get('CreatedAt'),
        'highCriticalVulnerabilities': len(vulnerabilities), 'highCriticalSecrets': len(secrets), 'reportSha256': digest(path)}
result['limits'] = [
    'Final container images were not built locally: Docker Engine unavailable; image scan/signing still required.',
    'No real PSP, messaging, storage/AV, KMS or LiveKit provider homologation.',
    'No load/soak, restore drill, independent pentest, full DLP or human accessibility approval.',
    'Clinical, fiscal, legal and operational release approvals remain mandatory.',
]
# Bind the record to the code/configuration snapshot without including any secret or self-referential report.
paths = []
for directory in ('backend/src', 'apps/web/src', 'apps/web/test', 'contracts', 'scripts', 'pipelines', 'infra/helm', 'infra/opentofu', 'infra/k3s', 'infra/images', 'infra/keycloak', 'infra/local', '.github'):
    paths += [p for p in (ROOT / directory).rglob('*') if p.is_file() and not any(part in ('__pycache__', '.terraform') for part in p.parts)]
paths += [ROOT / p for p in ('pnpm-lock.yaml', 'package.json', 'pnpm-workspace.yaml', 'backend/pom.xml', '.gitignore', '.env.example', 'apps/web/package.json', 'apps/web/next.config.ts', 'apps/web/playwright.config.ts', 'apps/web/vitest.config.ts', 'apps/web/tsconfig.json')]
entries = [{'path': p.relative_to(ROOT).as_posix(), 'sha256': digest(p)} for p in sorted(set(paths))]
result['sourceSnapshot'] = {'files': len(entries), 'sha256': hashlib.sha256(json.dumps(entries, sort_keys=True).encode()).hexdigest()}
target = ROOT / 'ops/evidencias/validacao-local.json'
target.parent.mkdir(exist_ok=True, parents=True)
target.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(f'Evidence collected: backend={result["backend"]["tests"]}, web={result["webUnit"]["tests"]}, browser={result["browser"]["tests"]}; allCriticalQualityPassed={all(r["passed"] for r in result["criticalQuality"].values())}')
