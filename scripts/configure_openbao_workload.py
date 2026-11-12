"""SEC-012: apply or verify the OpenBao workload role and policy declared in infra/openbao/*.json.

Operator use only, with an authorized OpenBao token read from BAO_TOKEN_FILE (never argv, never printed).
Only HTTPS is accepted. The auth mount itself belongs to the platform and is never created here.

  BAO_ADDR=https://openbao.lab:8202 BAO_CACERT=ca.crt BAO_TOKEN_FILE=token \
    python scripts/configure_openbao_workload.py --check
  ... --apply   # writes the policy and the role, then runs the same verification
"""
import argparse
import json
import os
import ssl
import sys
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ROLE_FIELDS = ('role_type', 'bound_subject', 'bound_audiences', 'user_claim', 'token_policies',
               'token_no_default_policy', 'token_type', 'token_ttl', 'token_max_ttl')


class BaoError(RuntimeError):
    pass


class Bao:
    def __init__(self, addr, token, cafile=None):
        if not addr.startswith('https://'):
            raise BaoError('BAO_ADDR deve usar https://.')
        self.addr = addr.rstrip('/')
        self._token = token
        self._context = ssl.create_default_context(cafile=cafile)

    def request(self, method, path, body=None):
        data = None if body is None else json.dumps(body).encode()
        request = urllib.request.Request(f'{self.addr}/v1/{path}', data=data, method=method)
        request.add_header('X-Vault-Token', self._token)
        if data is not None:
            request.add_header('Content-Type', 'application/json')
        try:
            with urllib.request.urlopen(request, context=self._context, timeout=15) as response:
                raw = response.read()
        except urllib.error.HTTPError as error:
            if error.code == 404:
                return None
            # Provider bodies are not echoed: they may describe policy internals.
            raise BaoError(f'{method} {path} falhou com HTTP {error.code}.') from None
        except urllib.error.URLError as error:
            raise BaoError(f'OpenBao indisponível: {error.reason}.') from None
        return json.loads(raw) if raw else {}


def load_definition(path):
    definition = json.loads(Path(path).read_text(encoding='utf-8'))
    for key in ('mount', 'mountType', 'roleName', 'policyName', 'role', 'policy'):
        if key not in definition:
            raise BaoError(f'Definição sem o campo {key}.')
    role = definition['role']
    if definition['policyName'] not in role.get('token_policies', []):
        raise BaoError('O role precisa receber a política declarada.')
    if role.get('token_no_default_policy') is not True:
        raise BaoError('token_no_default_policy deve ser true.')
    if not role.get('bound_subject') or not role.get('bound_audiences'):
        raise BaoError('O role precisa de bound_subject e bound_audiences.')
    return definition


def normalize_policy(text):
    return '\n'.join(line.rstrip() for line in text.strip().splitlines())


def verify(bao, definition):
    """Return a list of drift messages; empty means the cluster matches the definition."""
    drift = []
    mounts = bao.request('GET', 'sys/auth') or {}
    mounts = mounts.get('data', mounts)
    mount = mounts.get(definition['mount'] + '/')
    if not mount:
        return [f"auth mount {definition['mount']} ausente; ele pertence à plataforma"]
    if mount.get('type') != definition['mountType']:
        drift.append(f"auth mount {definition['mount']} é {mount.get('type')}, esperado {definition['mountType']}")
    policy = bao.request('GET', f"sys/policies/acl/{definition['policyName']}")
    current = ((policy or {}).get('data') or {}).get('policy')
    if current is None:
        drift.append(f"política {definition['policyName']} ausente")
    elif normalize_policy(current) != normalize_policy(definition['policy']):
        drift.append(f"política {definition['policyName']} diverge da definição")
    role = bao.request('GET', f"auth/{definition['mount']}/role/{definition['roleName']}")
    data = (role or {}).get('data')
    if data is None:
        drift.append(f"role {definition['roleName']} ausente")
    else:
        for field in ROLE_FIELDS:
            if field in definition['role'] and data.get(field) != definition['role'][field]:
                drift.append(f"role {definition['roleName']}: {field} diverge")
    for key in definition.get('requiredTransitKeys', []):
        if bao.request('GET', f'transit/keys/{key}') is None:
            drift.append(f'chave Transit {key} ausente')
    return drift


def apply(bao, definition):
    mounts = bao.request('GET', 'sys/auth') or {}
    mounts = mounts.get('data', mounts)
    if definition['mount'] + '/' not in mounts:
        raise BaoError(f"auth mount {definition['mount']} ausente; configure-o pela plataforma antes.")
    bao.request('PUT', f"sys/policies/acl/{definition['policyName']}", {'policy': definition['policy']})
    bao.request('POST', f"auth/{definition['mount']}/role/{definition['roleName']}", definition['role'])
    return verify(bao, definition)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--definition', default=str(ROOT / 'infra/openbao/conhecerse-dev.json'))
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--apply', action='store_true')
    mode.add_argument('--check', action='store_true')
    args = parser.parse_args(argv)
    try:
        definition = load_definition(args.definition)
        token_file = os.environ.get('BAO_TOKEN_FILE')
        if not token_file:
            raise BaoError('Defina BAO_TOKEN_FILE com o caminho do token autorizado.')
        token = Path(token_file).read_text(encoding='utf-8').strip()
        if not token:
            raise BaoError('BAO_TOKEN_FILE está vazio.')
        bao = Bao(os.environ.get('BAO_ADDR', ''), token, os.environ.get('BAO_CACERT') or None)
        drift = apply(bao, definition) if args.apply else verify(bao, definition)
    except (BaoError, OSError, ValueError) as error:
        print(f'ERRO: {error}', file=sys.stderr)
        return 2
    target = f"{definition['mount']}/role/{definition['roleName']}"
    if drift:
        for message in drift:
            print(f'DIVERGÊNCIA: {message}')
        return 1
    print(f"OK: {target} e política {definition['policyName']} conferem com a definição versionada.")
    return 0


if __name__ == '__main__':
    sys.exit(main())
