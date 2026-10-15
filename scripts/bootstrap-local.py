"""Initialize the local Raft KMS and private S3 bucket. Never prints credentials.

Only loopback dependencies from infra/local/compose.yaml are accepted. Recovery
material is stored in the ignored infra/secrets directory. Production uses the
operator-managed OpenBao policies and workload identity described in OPERACAO.
"""
import base64, datetime, hashlib, hmac, json, os
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.error import HTTPError
from urllib.parse import urlparse, quote

ROOT=Path(__file__).resolve().parents[1]
secrets_dir=ROOT/'infra/secrets';env_path=secrets_dir/'local.env'
if not env_path.is_file():raise SystemExit('Execute scripts/init-local.ps1 primeiro.')
env=dict(line.split('=',1) for line in env_path.read_text().splitlines() if line and not line.startswith('#'))
for key in ('BAO_URL','S3_ENDPOINT'):
    if urlparse(env[key]).hostname not in ('localhost','127.0.0.1'):raise SystemExit('Bootstrap é exclusivo do ambiente local.')

def save(path,data):
    path.write_text(data,encoding='utf-8');os.chmod(path,0o600)
def bao(path,body=None,token=None,method=None):
    data=json.dumps(body).encode() if body is not None else None
    headers={'Content-Type':'application/json'}
    if token:headers['X-Vault-Token']=token
    try:
        with urlopen(Request(env['BAO_URL']+'/v1/'+path,data=data,headers=headers,method=method),timeout=15) as response:
            content=response.read();return json.loads(content) if content else {}
    except HTTPError as e:
        # Do not include the body, headers, URL query or secret values in diagnostics.
        raise RuntimeError(f'OpenBao: HTTP {e.code} ao configurar {path.split("/")[0]}') from None

recovery=secrets_dir/'bao-local-recovery.json'
if not bao('sys/init')['initialized']:
    save(recovery,json.dumps(bao('sys/init',{'secret_shares':1,'secret_threshold':1}),indent=2))
if not recovery.is_file():raise SystemExit('OpenBao já inicializado. Restaure o material de recuperação local antes de continuar.')
material=json.loads(recovery.read_text());root_token=material['root_token']
if bao('sys/seal-status')['sealed']:bao('sys/unseal',{'key':material['keys_base64'][0]})
mounts=bao('sys/mounts',token=root_token)['data']
if 'transit/' not in mounts:bao('sys/mounts/transit',{'type':'transit'},root_token)
if 'secret/' not in mounts:bao('sys/mounts/secret',{'type':'kv','options':{'version':'2'}},root_token)
bao('transit/keys/conhecerse',{'type':'aes256-gcm96','derived':True,'exportable':False,'allow_plaintext_backup':False},root_token)
bao('transit/keys/conhecerse-video',{'type':'hmac','exportable':False,'allow_plaintext_backup':False},root_token)
policy='''path "transit/encrypt/conhecerse" { capabilities = ["update"] }
path "transit/decrypt/conhecerse" { capabilities = ["update"] }
path "transit/hmac/conhecerse-video/sha2-256" { capabilities = ["update"] }
path "secret/data/conhecerse/dev/*" { capabilities = ["read"] }
path "auth/token/lookup-self" { capabilities = ["read"] }
path "auth/token/renew-self" { capabilities = ["update"] }
'''
bao('sys/policies/acl/conhecerse-local',{'policy':policy},root_token)
token_path=Path(env['BAO_TOKEN_FILE'])
if not token_path.is_file():save(token_path,bao('auth/token/create',{'policies':['conhecerse-local'],'no_default_policy':True,'period':'24h','display_name':'conhecerse-local'},root_token)['auth']['client_token'])
else:bao('auth/token/renew-self',{},token_path.read_text().strip())

def sign(key,value):return hmac.new(key,value.encode(),hashlib.sha256).digest()
def s3(method,suffix='',body=b''):
    parsed=urlparse(env['S3_ENDPOINT']);host=parsed.netloc;path='/'+env['S3_BUCKET']
    query=suffix;now=datetime.datetime.now(datetime.timezone.utc);stamp=now.strftime('%Y%m%dT%H%M%SZ');day=stamp[:8]
    digest=hashlib.sha256(body).hexdigest();headers={'host':host,'x-amz-content-sha256':digest,'x-amz-date':stamp}
    names=';'.join(sorted(headers));canonical='\n'.join([method,quote(path,safe='/'),query,''.join(f'{k}:{headers[k]}\n' for k in sorted(headers)),names,digest])
    region=env.get('S3_REGION','sa-east-1');scope=f'{day}/{region}/s3/aws4_request';string='AWS4-HMAC-SHA256\n'+stamp+'\n'+scope+'\n'+hashlib.sha256(canonical.encode()).hexdigest()
    key=sign(sign(sign(sign(('AWS4'+env['MINIO_ROOT_PASSWORD']).encode(),day),region),'s3'),'aws4_request')
    headers['Authorization']=f'AWS4-HMAC-SHA256 Credential={env["MINIO_ROOT_USER"]}/{scope}, SignedHeaders={names}, Signature='+hmac.new(key,string.encode(),hashlib.sha256).hexdigest()
    try:
        with urlopen(Request(env['S3_ENDPOINT']+path+('?' +query if query else ''),data=body if method=='PUT' else None,headers=headers,method=method),timeout=15) as response:return response.status
    except HTTPError as e:
        if method=='PUT' and not suffix and e.code==409:return 409
        raise RuntimeError(f'S3: HTTP {e.code} ao configurar bucket local.') from None

region=env.get('S3_REGION','sa-east-1')
s3('PUT',body=f'<CreateBucketConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/"><LocationConstraint>{region}</LocationConstraint></CreateBucketConfiguration>'.encode())
s3('PUT','versioning=',b'<VersioningConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/"><Status>Enabled</Status></VersioningConfiguration>')
origin=env['APP_ORIGIN']
s3('PUT','cors=',f'<CORSConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/"><CORSRule><AllowedOrigin>{origin}</AllowedOrigin><AllowedMethod>PUT</AllowedMethod><AllowedHeader>content-type</AllowedHeader><MaxAgeSeconds>600</MaxAgeSeconds></CORSRule></CORSConfiguration>'.encode())
s3('PUT','lifecycle=',b'<LifecycleConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/"><Rule><ID>quarantine-expiry</ID><Filter><Prefix>quarantine/</Prefix></Filter><Status>Enabled</Status><Expiration><Days>1</Days></Expiration></Rule></LifecycleConfiguration>')
# The loopback-only local account is not a production workload credential.
append={k:v for k,v in {'AWS_ACCESS_KEY_ID':env['MINIO_ROOT_USER'],'AWS_SECRET_ACCESS_KEY':env['MINIO_ROOT_PASSWORD'],'ENCRYPTION_PROVIDER':'openbao'}.items() if k not in env}
with env_path.open('a',encoding='utf-8') as file:
    for key,value in append.items():file.write(f'{key}={value}\n')
print('OpenBao inicializado/deslacrado; token restrito e bucket privado/versionado configurados. Nenhum segredo exibido.')
