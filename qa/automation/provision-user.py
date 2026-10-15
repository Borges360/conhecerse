"""Create an isolated synthetic OIDC fixture; never use against a shared realm."""
import json,os,secrets,urllib.request,urllib.parse
from pathlib import Path
if os.environ.get('E2E_ISOLATED_SYNTHETIC')!='true':raise SystemExit('Requer E2E_ISOLATED_SYNTHETIC=true.')
env_file=Path(os.environ.get('E2E_ENV_FILE','.local/oidc-runtime.env'))
env=dict(line.split('=',1) for line in env_file.read_text().splitlines() if line and not line.startswith('#'))
issuer=urllib.parse.urlparse(env['OIDC_ISSUER'])
if issuer.hostname not in ('localhost','127.0.0.1') or env['APP_ENV']!='test':raise SystemExit('Somente localhost e APP_ENV=test.')
host=f'{issuer.scheme}://{issuer.netloc}';realm=issuer.path.rsplit('/',1)[-1]
body=urllib.parse.urlencode({'grant_type':'password','client_id':'admin-cli','username':env['KC_BOOTSTRAP_ADMIN_USERNAME'],'password':env['KC_BOOTSTRAP_ADMIN_PASSWORD']}).encode()
with urllib.request.urlopen(host+'/realms/master/protocol/openid-connect/token',data=body,timeout=10) as response:token=json.load(response)['access_token']
fixture={'username':'synthetic-'+secrets.token_hex(8),'password':secrets.token_urlsafe(32),'email':'synthetic-'+secrets.token_hex(8)+'@example.invalid'}
user={'username':fixture['username'],'email':fixture['email'],'enabled':True,'emailVerified':True,'firstName':'Pessoa','lastName':'Sintética','requiredActions':['CONFIGURE_TOTP'],'credentials':[{'type':'password','value':fixture['password'],'temporary':False}]}
request=urllib.request.Request(host+'/admin/realms/'+realm+'/users',data=json.dumps(user).encode(),headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'},method='POST')
with urllib.request.urlopen(request,timeout=10) as response:assert response.status==201
target=Path('.local/oidc-fixture.json');target.parent.mkdir(exist_ok=True);target.write_text(json.dumps(fixture),encoding='utf-8');os.chmod(target,0o600)
print('Usuário sintético criado no realm local; senha não exibida.')
