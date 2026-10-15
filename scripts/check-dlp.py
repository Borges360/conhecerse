"""Scan technical artifacts for seeded canaries and credential patterns, without printing matches."""
import argparse,hashlib,json,re,datetime
from pathlib import Path
parser=argparse.ArgumentParser();parser.add_argument('paths',nargs='+');parser.add_argument('--output',default='ops/evidencias/dlp-complementacao.json');args=parser.parse_args()
patterns={'clinicalCanary':rb'CANARY_(?:CLINICAL|EMERGENCY|PERSON|INTERRUPTION)[A-Z0-9_]*','bearerToken':rb'Bearer\s+[A-Za-z0-9._-]{30,}','jwtToken':rb'eyJ[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}','privateKey':rb'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----'}
results=[]
for raw in args.paths:
 path=Path(raw);data=path.read_bytes();findings={name:len(re.findall(pattern,data)) for name,pattern in patterns.items()}
 results.append({'path':path.as_posix(),'sha256':hashlib.sha256(data).hexdigest(),'findings':findings,'passed':not any(findings.values())})
report={'recordedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'scope':'explicit technical logs only; not traces, metrics or final images','releaseApproved':False,'results':results,'passed':all(r['passed'] for r in results)}
target=Path(args.output);target.parent.mkdir(exist_ok=True,parents=True);target.write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8');print('DLP dos artefatos indicados: '+('PASSOU' if report['passed'] else 'FALHOU'));raise SystemExit(0 if report['passed'] else 1)
