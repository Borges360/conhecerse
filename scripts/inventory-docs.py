"""Read specification sources, compare requirement IDs and inventory document hashes."""
import hashlib
import json
import re
import zipfile
from pathlib import Path
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
paths = sorted(set([*root.glob('*.md'), *root.glob('*.docx'), *root.glob('contracts/*'), *root.glob('db/*.sql'), *root.glob('ops/*.md'), *root.glob('qa/*.md')]))
items = []
texts = {}
for path in paths:
    if not path.is_file():
        continue
    raw = path.read_bytes()
    if path.suffix == '.docx':
        with zipfile.ZipFile(path) as doc:
            text = '\n'.join(n.text or '' for n in ET.fromstring(doc.read('word/document.xml')).iter('{http://schemas.openxmlformats.org/wordprocessingml/2006/main}t'))
    else:
        text = raw.decode('utf-8')
    texts[path.name] = text
    items.append({'file': path.relative_to(root).as_posix(), 'bytes': len(raw), 'charactersAnalyzed': len(text), 'sha256': hashlib.sha256(raw).hexdigest()})
pattern = r'\b(?:ORG|PAT|SCH|VID|CLI|BIL|GOV|DOC|COM|CNT|SEC|NFR)-\d{3}\b'
canonical = set(re.findall(pattern, texts['SPECIFICACAO_TECNICA.md']))
word = set(re.findall(pattern, next(text for name, text in texts.items() if name.endswith('.docx'))))
assert canonical == word, f'Requirement IDs diverge: Word-only={word-canonical}, Markdown-only={canonical-word}'
(root / 'docs/fontes-analisadas.json').write_text(json.dumps(items, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(f'{len(items)} source documents inventoried; {len(canonical)} matching requirement IDs in Word/Markdown.')
