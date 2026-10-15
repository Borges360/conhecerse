"""Collect actual sanitized results for ISSUE-PSC-017. Preserve the prior baseline evidence."""
import collections,datetime,hashlib,json,xml.etree.ElementTree as ET
from pathlib import Path
import yaml
root=Path(__file__).resolve().parents[1]
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def junit(paths):
 counts=dict(tests=0,failures=0,errors=0,skipped=0);reports=[]
 for path in paths:
  tree=ET.parse(path).getroot();suites=[tree] if tree.tag=='testsuite' else tree.findall('.//testsuite')
  result={k:sum(int(s.get(k,0)) for s in suites) for k in counts}
  for key in counts:counts[key]+=result[key]
  reports.append({'path':path.relative_to(root).as_posix(),'sha256':sha(path),**result})
 return {**counts,'reports':reports}
spec=yaml.safe_load((root/'contracts/openapi.yaml').read_text(encoding='utf-8'))
result={'recordedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'issue':'ISSUE-PSC-017','releaseApproved':False,'remoteDeploymentExecuted':False,'contract':{'operations':sum(len(p) for p in spec['paths'].values()),'schemas':len(spec['components']['schemas']),'sha256':sha(root/'contracts/openapi.yaml')}}
result['backend']=junit(sorted((root/'backend/target/surefire-reports').glob('TEST-*.xml')))
result['webUnit']=junit([root/'apps/web/test-results/unit.xml']);result['browser']=junit([root/'apps/web/test-results/e2e.xml'])
coverage=ET.parse(root/'backend/target/site/jacoco/jacoco.xml').getroot();mutations=ET.parse(root/'backend/target/pit-reports/mutations.xml').getroot();result['criticalQuality']={}
for name in ('scheduling/SchedulingModule','billing/BillingModule','clinical/ClinicalModule','shared/Access'):
 full='com/psicocare/'+name;cls=next(c for c in coverage.findall('.//class') if c.get('name')==full);counter=next(c for c in cls.findall('counter') if c.get('type')=='LINE');covered=int(counter.get('covered'));total=covered+int(counter.get('missed'));items=[m for m in mutations if m.findtext('mutatedClass')==full.replace('/','.')];detected=sum(m.get('detected')=='true' for m in items)
 result['criticalQuality'][name]={'lineCoveragePercent':round(100*covered/total,2),'mutationScorePercent':round(100*detected/len(items),2),'passed':covered/total>=.85 and detected/len(items)>=.7}
result['journeys']={}
oidc=json.loads((root/'.local/oidc-evidence.json').read_text(encoding='utf-8'));result['journeys']['oidc']={k:oidc[k] for k in ('authorizationCodePkce','mfaTotp','opaqueHttpOnlyCookie','organizationCreated','finishedAt')}
result['journeys']['completion']=json.loads((root/'ops/evidencias/jornada-complementacao.json').read_text(encoding='utf-8'))
sca=json.loads((root/'.local/completion-sca.json').read_text(encoding='utf-8'));result['sca']={'scope':'Maven dependencies resolved from backend/pom.xml; not final container images','reportSha256':sha(root/'.local/completion-sca.json'),'packages':sum(len(r.get('Packages',[])) for r in sca.get('Results',[])),'highCritical':sum(len(r.get('Vulnerabilities',[])) for r in sca.get('Results',[]))}
result['dlp']=json.loads((root/'ops/evidencias/dlp-complementacao.json').read_text(encoding='utf-8'));result['localProviders']=json.loads((root/'ops/evidencias/provedores-locais.json').read_text(encoding='utf-8'))
paths=[]
for directory in ('backend/src','apps/web/src','apps/web/test','contracts','scripts','qa/automation','infra/helm','infra/k3s'):
 paths.extend(p for p in (root/directory).rglob('*') if p.is_file() and '__pycache__' not in p.parts)
paths.extend(root/name for name in ('backend/pom.xml','apps/web/package.json','package.json','pnpm-lock.yaml','.env.example','docs/IMPLEMENTACAO_PENDENCIAS.md','docs/adr/ADR-017-completar-lacunas.md','infra/local/compose.yaml'))
entries=[{'path':p.relative_to(root).as_posix(),'sha256':sha(p)} for p in sorted(set(paths))]
result['sourceSnapshot']={'files':len(entries),'sha256':hashlib.sha256(json.dumps(entries,sort_keys=True).encode()).hexdigest()}
result['pending']=['Docker Engine did not respond; local MinIO/OpenBao/ClamAV real acceptance checks failed availability.','Asaas/LiveKit/Resend/WhatsApp credentials and real provider homologation deferred by user.','Clinical/legal/fiscal approvals, ICP-Brasil certificates, PDF/A and templates require responsible specialists.','No final image build/signing/deployment, load60m/soak24h, restore with objects/KMS, independent pentest or human accessibility approval.','DLP covers listed technical logs; full traces/metrics/images acceptance still required.']
result['engineeringChecksPassed']=all(result[k]['failures']==0 and result[k]['errors']==0 for k in ('backend','webUnit','browser')) and all(q['passed'] for q in result['criticalQuality'].values()) and result['sca']['packages']>0 and result['sca']['highCritical']==0 and result['dlp']['passed']
target=root/'ops/evidencias/complementacao-local.json';target.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8');print(json.dumps({'backendTests':result['backend']['tests'],'webTests':result['webUnit']['tests'],'browserTests':result['browser']['tests'],'engineeringChecksPassed':result['engineeringChecksPassed'],'releaseApproved':False}));raise SystemExit(0 if result['engineeringChecksPassed'] else 1)
