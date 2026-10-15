"""Require coverage and mutation thresholds independently for every critical component."""
from pathlib import Path
import collections
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
classes = ('scheduling/SchedulingModule', 'billing/BillingModule', 'clinical/ClinicalModule', 'shared/Access')
coverage = ET.parse(root / 'backend/target/site/jacoco/jacoco.xml').getroot()
mutations = ET.parse(root / 'backend/target/pit-reports/mutations.xml').getroot()
results = {}
for name in classes:
    full = 'com/psicocare/' + name
    cls = next(c for c in coverage.findall('.//class') if c.get('name') == full)
    line = next(c for c in cls.findall('counter') if c.get('type') == 'LINE')
    covered, missed = int(line.get('covered')), int(line.get('missed'))
    status = collections.Counter(m.get('detected') for m in mutations if m.findtext('mutatedClass') == full.replace('/', '.'))
    total = sum(status.values())
    assert total > 0, f'Missing mutations for {name}'
    line_ratio = covered / (covered + missed)
    mutation_ratio = status['true'] / total
    print(f'{name}: lines {line_ratio:.2%}; mutations {mutation_ratio:.2%}')
    results[name] = line_ratio >= .85 and mutation_ratio >= .70
if not all(results.values()):
    raise SystemExit('Critical quality gate failed: ' + ', '.join(k for k, passed in results.items() if not passed))
