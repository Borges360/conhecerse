"""Unit tests for configure_openbao_workload.py with an in-memory OpenBao double (no network)."""
import copy
import importlib.util
import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('configure', ROOT / 'scripts/configure_openbao_workload.py')
configure = importlib.util.module_from_spec(spec)
spec.loader.exec_module(configure)
DEFINITION = configure.load_definition(ROOT / 'infra/openbao/conhecerse-dev.json')


class FakeBao:
    def __init__(self, mounts=None):
        self.store = {'sys/auth': {'data': mounts if mounts is not None else {'jenkins-jwt/': {'type': 'jwt'}}},
                      'transit/keys/conhecerse': {'data': {}}, 'transit/keys/conhecerse-video': {'data': {}}}
        self.writes = []

    def request(self, method, path, body=None):
        if method == 'GET':
            return copy.deepcopy(self.store.get(path))
        self.writes.append(path)
        if path.startswith('sys/policies/acl/'):
            self.store[path] = {'data': {'policy': body['policy']}}
        else:
            self.store[path] = {'data': copy.deepcopy(body)}
        return {}


class ConfigureTest(unittest.TestCase):
    def test_apply_writes_policy_and_role_then_verifies(self):
        bao = FakeBao()
        self.assertEqual(configure.apply(bao, DEFINITION), [])
        self.assertEqual(bao.writes, ['sys/policies/acl/conhecerse-dev', 'auth/jenkins-jwt/role/conhecerse-dev'])
        self.assertEqual(configure.verify(bao, DEFINITION), [])

    def test_check_reports_drift_without_writing(self):
        bao = FakeBao()
        configure.apply(bao, DEFINITION)
        bao.store['auth/jenkins-jwt/role/conhecerse-dev']['data']['bound_audiences'] = ['https://kubernetes.default.svc.cluster.local']
        bao.writes.clear()
        drift = configure.verify(bao, DEFINITION)
        self.assertEqual(drift, ['role conhecerse-dev: bound_audiences diverge'])
        self.assertEqual(bao.writes, [])

    def test_missing_mount_is_never_created(self):
        bao = FakeBao(mounts={})
        with self.assertRaises(configure.BaoError):
            configure.apply(bao, DEFINITION)
        self.assertEqual(bao.writes, [])

    def test_definition_is_least_privilege(self):
        role = DEFINITION['role']
        self.assertEqual(role['bound_subject'], 'system:serviceaccount:dev:conhecerse-workload')
        self.assertEqual(role['bound_audiences'], ['openbao'])
        self.assertTrue(role['token_no_default_policy'])
        self.assertNotIn('sudo', DEFINITION['policy'])
        self.assertNotIn('"*"', DEFINITION['policy'])
        for line in DEFINITION['policy'].strip().splitlines():
            self.assertRegex(line, r'^path "(transit/(encrypt|decrypt)/conhecerse|transit/hmac/conhecerse-video/sha2-256|'
                                   r'secret/data/conhecerse/dev/\*|auth/token/(lookup|renew)-self)" ')

    def test_plain_http_is_refused(self):
        with self.assertRaises(configure.BaoError):
            configure.Bao('http://openbao.platform.svc.cluster.local:8200', 'x')

    def test_definition_matches_lab_manifest(self):
        manifest = (ROOT / 'infra/k3s/dev/conhecerse.yaml').read_text(encoding='utf-8')
        self.assertIn(f'mount_path = "auth/{DEFINITION["mount"]}"', manifest)
        self.assertIn(f'role = "{DEFINITION["roleName"]}"', manifest)
        self.assertIn('name: conhecerse-workload', manifest)
        self.assertEqual(manifest.count('audience: openbao'), 2)


if __name__ == '__main__':
    unittest.main()
