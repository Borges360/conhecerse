import json,re
from pathlib import Path
import yaml
root=Path(__file__).resolve().parents[1]
docs=list(yaml.safe_load_all((root/'infra/k3s/dev/conhecerse.yaml').read_text(encoding='utf-8')))
memory=0
for obj in docs:
    assert obj['metadata']['namespace']=='dev'
    if obj['kind']=='Deployment':
        assert obj['spec']['replicas']==1
        pod=obj['spec']['template']['spec'];assert not pod.get('hostNetwork')
        for c in pod['containers']:
            assert 'readinessProbe' in c and not c['securityContext'].get('privileged')
            assert c['securityContext']['runAsNonRoot'];memory+=int(c['resources']['limits']['memory'].removesuffix('Mi'))
    if obj['kind']=='Service':assert obj['spec'].get('type','ClusterIP')=='ClusterIP'
    if obj['kind']=='Ingress':assert obj['spec']['ingressClassName']=='traefik'
assert memory==3840,memory
pipeline=(root/'pipelines/deploy-conhecerse-dev.Jenkinsfile').read_text(encoding='utf-8')
for value in ['agent none','moby/buildkit:v0.23.2-rootless','lab-deployer','github-ssh','DRY_RUN','SKIP_TEST','conhecerse-dockerfile','rollout status']:
    assert value in pipeline,value
assert 'docker.sock' not in pipeline and 'privileged: true' not in pipeline
print(f'Infraestrutura: {len(docs)} objetos; {memory} MiB; contratos do QUICKSTART presentes.')
