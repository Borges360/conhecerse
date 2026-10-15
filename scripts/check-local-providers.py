"""Exercise real local OpenBao, private MinIO and ClamAV. No simulated pass results."""
import argparse,base64,datetime,json,os,socket,struct,time,uuid
from pathlib import Path
from urllib.request import Request,urlopen
from urllib.error import HTTPError
from urllib.parse import urlparse

root=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser();parser.add_argument('--env-file',default='infra/secrets/local.env');parser.add_argument('--output',default='ops/evidencias/provedores-locais.json');args=parser.parse_args()
source=root/args.env_file
if not source.is_file():raise SystemExit('Configuração local ausente. Execute scripts/init-local.ps1 e bootstrap-local.py antes do exercício.')
env=dict(l.split('=',1) for l in source.read_text(encoding='utf-8').splitlines() if l and not l.startswith('#'))
for key in ('BAO_URL','S3_ENDPOINT'):
 if urlparse(env[key]).hostname not in ('localhost','127.0.0.1'):raise SystemExit('Este exercício é limitado a dependências locais.')
if env.get('CLAMAV_HOST','localhost') not in ('localhost','127.0.0.1'):raise SystemExit('ClamAV precisa estar em localhost.')
result={'recordedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'releaseApproved':False,'realProviders':True,'checks':{}}
def check(name,work):
 begin=time.monotonic()
 try:work();result['checks'][name]={'passed':True,'seconds':round(time.monotonic()-begin,3)}
 except Exception as error:result['checks'][name]={'passed':False,'errorType':type(error).__name__}
def kms():
 token=Path(env['BAO_TOKEN_FILE']).read_text().strip();context=base64.b64encode(uuid.uuid4().bytes).decode();plain=base64.b64encode(os.urandom(32)).decode()
 def call(operation,body):
  with urlopen(Request(env['BAO_URL']+'/v1/transit/'+operation+'/conhecerse',data=json.dumps(body).encode(),headers={'X-Vault-Token':token,'Content-Type':'application/json'}),timeout=10) as response:return json.load(response)['data']
 encrypted=call('encrypt',{'plaintext':plain,'context':context})['ciphertext'];assert call('decrypt',{'ciphertext':encrypted,'context':context})['plaintext']==plain
 try:call('decrypt',{'ciphertext':encrypted,'context':base64.b64encode(uuid.uuid4().bytes).decode()});raise AssertionError('Cross-tenant decryption accepted')
 except HTTPError as error:assert error.code==400
def private_bucket():
 with urlopen(env['S3_ENDPOINT']+'/minio/health/live',timeout=10) as response:assert response.status==200
 try:
  with urlopen(env['S3_ENDPOINT']+'/'+env['S3_BUCKET'],timeout=10) as response:raise AssertionError('Anonymous bucket accepted')
 except HTTPError as error:assert error.code in (401,403) and b'AccessDenied' in error.read(8192)
def antivirus():
 def scan(content):
  with socket.create_connection((env.get('CLAMAV_HOST','localhost'),int(env.get('CLAMAV_PORT','3310'))),timeout=10) as client:
   client.sendall(b'zINSTREAM\0'+struct.pack('!I',len(content))+content+struct.pack('!I',0));response=b''
   while not response.endswith(b'\0') and len(response)<2048:
    chunk=client.recv(2048)
    if not chunk:break
    response+=chunk
   return response
 assert b'OK' in scan(b'SYNTHETIC_CLEAN_FILE')
 # Standard antivirus test string; never a real malicious file.
 eicar=b'X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*';assert b'FOUND' in scan(eicar)
check('openbaoTenantEncryption',kms);check('minioAnonymousBucketDenied',private_bucket);check('clamavCleanAndEicar',antivirus)
target=root/args.output;target.parent.mkdir(exist_ok=True,parents=True);target.write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
print(json.dumps({name:value['passed'] for name,value in result['checks'].items()}));raise SystemExit(0 if all(v['passed'] for v in result['checks'].values()) else 1)
