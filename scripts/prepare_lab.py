"""Prepare files in the homelab repository. Does not contact or publish to the cluster."""
import argparse,base64,json,re,secrets,shutil
from pathlib import Path
import yaml

ROOT=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser();parser.add_argument('--lab',type=Path,required=True);args=parser.parse_args();lab=args.lab.resolve()
if not (lab/'docs/QUICKSTART-APP.md').is_file():raise SystemExit('Repositório do laboratório não encontrado.')
def update(relative,fn):
    path=lab/relative;old=path.read_text(encoding='utf-8');new=fn(old)
    if new!=old:path.write_text(new,encoding='utf-8',newline='\n')
def append_once(old,marker,content):return old if marker in old else old.rstrip()+'\n\n'+content.strip()+'\n'
for src,dst in [('infra/k3s/dev/conhecerse.yaml','infra/k3s/dev/conhecerse.yaml'),('infra/images/conhecerse/Dockerfile','infra/images/conhecerse/Dockerfile'),('infra/images/conhecerse/Dockerfile.dockerignore','infra/images/conhecerse/Dockerfile.dockerignore'),('pipelines/deploy-conhecerse-dev.Jenkinsfile','pipelines/deploy-conhecerse-dev.Jenkinsfile')]:
    target=lab/dst;target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(ROOT/src,target)
update('infra/k3s/platform/jenkins-rbac.yaml',lambda text: re.sub(r'(resourceNames: \[[^\n]*)(\])',lambda m:m[0] if '"conhecerse"' in m[0] else m[1]+', "conhecerse", "conhecerse-api", "conhecerse-worker", "conhecerse-iam", "conhecerse-av"'+m[2],text))

def catalog(text):
    data=json.loads(text)
    # Catalog is an array in the current QUICKSTART contract.
    entries=data if isinstance(data,list) else data['jobs']
    item={'name':'conhecerse-dev','file':'deploy-conhecerse-dev.Jenkinsfile','sandbox':True,'description':'Build, testes e deploy do Conhecer-se no namespace dev (http://conhecerse.lab/).'}
    existing=next((e for e in entries if e['name']==item['name']),None)
    if existing:existing.update(item)
    else:entries.append(item)
    return json.dumps(data,ensure_ascii=False,indent=2)+'\n'
update('pipelines/catalog.json',catalog)

def topology(text):
    text=re.sub(r'conhecerse_dev: [^\n]+','conhecerse_dev: 3840       # web 256 + API 512 + worker 512 + IAM 512 + AV 2048; validar capacidade antes de aplicar',text)
    if 'conhecerse_dev:' not in text:text=text.replace('      lume_dev:', '      conhecerse_dev: 3840       # web 256 + API 512 + worker 512 + IAM 512 + AV 2048; validar capacidade antes de aplicar\n      lume_dev:')
    if 'conhecerse.lab: ingress' not in text:text=text.replace('    lume.lab: ingress','    conhecerse.lab: ingress\n    conhecerse-auth.lab: ingress\n    lume.lab: ingress')
    text=re.sub(r'(^    dev: [^\n]+)',lambda m:m[0] if 'conhecerse' in m[0] else m[0]+', conhecerse (web/API/worker/IAM/AV)',text,flags=re.M)
    if '    conhecerse-dev:' not in text:text=text.replace('    lume-dev:', '    conhecerse-dev: build no K3s, deploy no namespace dev (http://conhecerse.lab/)\n    lume-dev:')
    return text
update('infra/topology.yml',topology)

db_file=lab/'infra/secrets/db.yml';db_text=db_file.read_text(encoding='utf-8')
db_values={'postgres_conhecerse_db':'conhecerse','postgres_conhecerse_runtime_user':'conhecerse_runtime','postgres_conhecerse_migrator_user':'conhecerse_migrator','postgres_conhecerse_web_user':'conhecerse_web','postgres_conhecerse_iam_user':'conhecerse_iam','postgres_conhecerse_iam_db':'conhecerse_iam'}
for component in ['runtime','migrator','web','iam']:db_values[f'postgres_conhecerse_{component}_password']=secrets.token_urlsafe(36)
for key,value in db_values.items():
    if not re.search(r'^'+re.escape(key)+r'\s*:',db_text,re.M):db_text+='\n'+key+': "'+value+'"\n'
db_file.write_text(db_text,encoding='utf-8')

env_file=lab/'infra/secrets/apps/conhecerse.env';env_file.parent.mkdir(parents=True,exist_ok=True)
if not env_file.exists():
    env={'APP_ENV':'dev','APP_ORIGIN':'http://conhecerse.lab','PUBLIC_URL':'http://conhecerse.lab','OIDC_ISSUER':'http://conhecerse-auth.lab/realms/conhecerse','OIDC_CLIENT_ID':'conhecerse-web','OIDC_AUDIENCE':'conhecerse-api','OIDC_CLIENT_SECRET':secrets.token_urlsafe(36),'BFF_ENCRYPTION_KEY':base64.b64encode(secrets.token_bytes(32)).decode(),'LOOKUP_HMAC_KEY':base64.b64encode(secrets.token_bytes(32)).decode(),'COOKIE_SECURE':'false','ENCRYPTION_PROVIDER':'openbao','BAO_URL':'http://openbao.platform.svc.cluster.local:8200','BAO_TOKEN_FILE':'/var/run/secrets/openbao/token','BAO_TOKEN':'','S3_ENDPOINT':'http://minio.dev.svc.cluster.local:9000','S3_PUBLIC_ENDPOINT':'https://minio.luizfelipeborges.dev','S3_REGION':'sa-east-1','S3_BUCKET':'conhecerse-private','AWS_ACCESS_KEY_ID':'','AWS_SECRET_ACCESS_KEY':'','CLAMAV_HOST':'conhecerse-av.dev.svc.cluster.local','CLAMAV_PORT':'3310','VIDEO_APPROVED':'false','FISCAL_APPROVED':'false','TEMPLATES_APPROVED':'false','KC_BOOTSTRAP_ADMIN_USERNAME':'admin-conhecerse','KC_BOOTSTRAP_ADMIN_PASSWORD':secrets.token_urlsafe(36)}
    env_file.write_text('\n'.join(k+'='+v for k,v in env.items())+'\n',encoding='utf-8')

secrets_block=r'''
    # Conhecer-se: runtime, BFF, IAM and migrator never share database credentials.
    $conFile = Join-Path $infra 'secrets\apps\conhecerse.env'
    if (Test-Path $conFile) {
        $con = Read-EnvFile $conFile
        $conDb = 'jdbc:postgresql://postgres.data.svc.cluster.local:5432/conhecerse'
        $api = @{}
        foreach ($key in @('APP_ENV','PUBLIC_URL','OIDC_ISSUER','OIDC_AUDIENCE','ENCRYPTION_PROVIDER','LOOKUP_HMAC_KEY','BAO_URL','BAO_TOKEN_FILE','S3_ENDPOINT','S3_PUBLIC_ENDPOINT','S3_REGION','S3_BUCKET','AWS_ACCESS_KEY_ID','AWS_SECRET_ACCESS_KEY','CLAMAV_HOST','CLAMAV_PORT','VIDEO_APPROVED','FISCAL_APPROVED','TEMPLATES_APPROVED','LIVEKIT_URL','LIVEKIT_API_KEY','LIVEKIT_API_SECRET')) {
            if ($con.ContainsKey($key)) { $api[$key] = $con[$key] }
        }
        $api['DB_URL'] = $conDb
        $api['DB_USER'] = $s['postgres_conhecerse_runtime_user']
        $api['DB_PASSWORD'] = $s['postgres_conhecerse_runtime_password']
        $api['MIGRATIONS_ENABLED'] = 'false'
        Apply-Secret 'dev' 'conhecerse-app' $api
        $webPassword = [uri]::EscapeDataString($s['postgres_conhecerse_web_password'])
        Apply-Secret 'dev' 'conhecerse-web' @{
            'APP_ENV' = $con['APP_ENV']; 'APP_ORIGIN' = $con['APP_ORIGIN']; 'COOKIE_SECURE' = $con['COOKIE_SECURE']
            'OIDC_ISSUER' = $con['OIDC_ISSUER']; 'OIDC_CLIENT_ID' = $con['OIDC_CLIENT_ID']; 'OIDC_CLIENT_SECRET' = $con['OIDC_CLIENT_SECRET']
            'BFF_ENCRYPTION_KEY' = $con['BFF_ENCRYPTION_KEY']; 'BFF_DATABASE_URL' = "postgresql://conhecerse_web:${webPassword}@postgres.data.svc.cluster.local:5432/conhecerse"
            'API_BASE_URL' = 'http://conhecerse-api.dev.svc.cluster.local:8080'; 'S3_PUBLIC_ENDPOINT' = $con['S3_PUBLIC_ENDPOINT']; 'LIVEKIT_URL' = $con['LIVEKIT_URL']
        }
        Apply-Secret 'dev' 'conhecerse-iam' @{
            'KC_DB' = 'postgres'; 'KC_DB_URL' = 'jdbc:postgresql://postgres.data.svc.cluster.local:5432/conhecerse_iam'
            'KC_DB_USERNAME' = $s['postgres_conhecerse_iam_user']; 'KC_DB_PASSWORD' = $s['postgres_conhecerse_iam_password']
            'KC_BOOTSTRAP_ADMIN_USERNAME' = $con['KC_BOOTSTRAP_ADMIN_USERNAME']; 'KC_BOOTSTRAP_ADMIN_PASSWORD' = $con['KC_BOOTSTRAP_ADMIN_PASSWORD']
            'OIDC_CLIENT_SECRET' = $con['OIDC_CLIENT_SECRET']; 'APP_ORIGIN' = $con['APP_ORIGIN']
        }
        Apply-Secret 'platform' 'conhecerse-dev-db' @{
            'DB_URL' = $conDb; 'MIGRATOR_USER' = $s['postgres_conhecerse_migrator_user']; 'MIGRATOR_PASSWORD' = $s['postgres_conhecerse_migrator_password']
        }
        if ($con['BAO_TOKEN']) { Apply-Secret 'dev' 'conhecerse-bao-token' @{ 'token' = $con['BAO_TOKEN'] } }
    }
'''
def apply_script(text):
    if "'conhecerse-dockerfile'" not in text:
        marker="if ($Only -in 'all', 'jenkins') {"
        block="\n    $conImg = Join-Path $infra 'images\\conhecerse'\n    Apply-ConfigMap 'platform' 'conhecerse-dockerfile' @{\n        'Dockerfile' = [IO.File]::ReadAllText((Join-Path $conImg 'Dockerfile'), $utf8)\n        'Dockerfile.dockerignore' = [IO.File]::ReadAllText((Join-Path $conImg 'Dockerfile.dockerignore'), $utf8)\n    }\n"
        text=text.replace(marker,marker+block)
    if '# Conhecer-se: runtime' not in text:
        marker="    $s = Read-Secrets (Join-Path $infra 'secrets\\db.yml')"
        if marker not in text:raise SystemExit('Bloco de secrets mudou; revisar k3s-apply.ps1.')
        text=text.replace(marker,marker+'\n'+secrets_block,1)
    text=text.replace("'CLAMAV_PORT','VIDEO_APPROVED','FISCAL_APPROVED','TEMPLATES_APPROVED'))", "'CLAMAV_PORT','VIDEO_APPROVED','FISCAL_APPROVED','TEMPLATES_APPROVED','LIVEKIT_URL','LIVEKIT_API_KEY','LIVEKIT_API_SECRET'))")
    text=text.replace("'API_BASE_URL' = 'http://conhecerse-api.dev.svc.cluster.local:8080'; 'S3_PUBLIC_ENDPOINT' = $con['S3_PUBLIC_ENDPOINT']", "'API_BASE_URL' = 'http://conhecerse-api.dev.svc.cluster.local:8080'; 'S3_PUBLIC_ENDPOINT' = $con['S3_PUBLIC_ENDPOINT']; 'LIVEKIT_URL' = $con['LIVEKIT_URL']") if "'LIVEKIT_URL' = $con['LIVEKIT_URL']" not in text else text
    return text
update('infra/bin/k3s-apply.ps1',apply_script)

tasks='''
- name: Papéis conhecerse (separação runtime, migrator, BFF, IAM)
  community.postgresql.postgresql_user:
    name: "{{ item.user }}"
    password: "{{ item.password }}"
    role_attr_flags: NOSUPERUSER,NOCREATEDB,NOCREATEROLE,NOBYPASSRLS
  loop:
    - { user: "{{ postgres_conhecerse_runtime_user }}", password: "{{ postgres_conhecerse_runtime_password }}" }
    - { user: "{{ postgres_conhecerse_migrator_user }}", password: "{{ postgres_conhecerse_migrator_password }}" }
    - { user: "{{ postgres_conhecerse_web_user }}", password: "{{ postgres_conhecerse_web_password }}" }
    - { user: "{{ postgres_conhecerse_iam_user }}", password: "{{ postgres_conhecerse_iam_password }}" }
  become_user: postgres
  no_log: true
  tags: [conhecerse]
- name: Banco conhecerse
  community.postgresql.postgresql_db:
    name: "{{ postgres_conhecerse_db }}"
    owner: "{{ postgres_conhecerse_migrator_user }}"
  become_user: postgres
  tags: [conhecerse]
- name: Banco conhecerse IAM
  community.postgresql.postgresql_db:
    name: "{{ postgres_conhecerse_iam_db }}"
    owner: "{{ postgres_conhecerse_iam_user }}"
  become_user: postgres
  tags: [conhecerse]
- name: Schema public do conhecerse
  community.postgresql.postgresql_privs:
    login_db: "{{ postgres_conhecerse_db }}"
    type: schema
    objs: public
    privs: ALL
    roles: "{{ postgres_conhecerse_migrator_user }}"
  become_user: postgres
  tags: [conhecerse]
'''
update('infra/ansible/roles/postgresql/tasks/main.yml',lambda text:append_once(text,'Papéis conhecerse',tasks))
update('infra/ansible/roles/postgresql/defaults/main.yml',lambda text:append_once(text,'postgres_conhecerse_db:','postgres_conhecerse_db: conhecerse\npostgres_conhecerse_iam_db: conhecerse_iam'))
update('playbooks/credenciais.md',lambda text:append_once(text,'apps/conhecerse.env','''## Conhecer-se (preparado, apply pendente)
| Fonte | Materialização |
| --- | --- |
| `db.yml`, `postgres_conhecerse_*` | Papéis separados runtime, migrator, BFF e IAM; bancos `conhecerse` e `conhecerse_iam` no db01 |
| `apps/conhecerse.env` | `dev/conhecerse-app`, `dev/conhecerse-web`, `dev/conhecerse-iam`, `platform/conhecerse-dev-db`; `dev/conhecerse-bao-token` quando o token restrito for provisionado |

Segredos de fornecedores ficam no OpenBao. Bucket privado e credencial limitada do MinIO devem ser provisionados antes do primeiro uso de arquivos. Os campos ausentes não são substituídos por valores de demonstração. Não publicar estes valores em logs.'''))
for relative in ['infra/dns/adguard/config/AdGuardHome.yaml','infra/dns/adguard/runtime/conf/AdGuardHome.yaml']:
    if (lab/relative).exists():
        def dns(text):
            for host in ['conhecerse.lab','conhecerse-auth.lab']:
                rule='||'+host+'^$dnsrewrite=NOERROR;A;192.168.15.252'
                if rule not in text:
                    if 'user_rules: []' in text:text=text.replace('user_rules: []',"user_rules:\n  - '"+rule+"'")
                    elif 'user_rules:\n' in text:text=text.replace('user_rules:\n',"user_rules:\n  - '"+rule+"'\n",1)
                    else:raise SystemExit('Formato de user_rules desconhecido; não alterado.')
            return text
        update(relative,dns)
print('Arquivos preparados. Nenhum apply, push, build remoto, restart ou alteração de ESTADO.md foi executado.')
