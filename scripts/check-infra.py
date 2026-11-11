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
        for c in pod.get('initContainers',[]):
            # Native sidecars stay resident; count them in the declared ceiling.
            assert c.get('restartPolicy')=='Always' and 'startupProbe' in c,c['name']
            assert c['securityContext']['runAsNonRoot'] and not c['securityContext'].get('privileged')
            memory+=int(c['resources']['limits']['memory'].removesuffix('Mi'))
        if obj['metadata']['name'] in ('conhecerse-api','conhecerse-worker'):
            # SEC-012: OpenBao token comes from agent auto-auth with a projected workload token, never a static Secret.
            assert pod['serviceAccountName']=='conhecerse-workload' and [c['name'] for c in pod['initContainers']]==['bao-agent']
            volumes={v['name']:v for v in pod['volumes']}
            assert volumes['bao-token']['emptyDir']['medium']=='Memory' and 'secret' not in volumes['bao-token']
            assert volumes['workload-jwt']['projected']['sources'][0]['serviceAccountToken']['audience']=='openbao'
    if obj['kind']=='Service':assert obj['spec'].get('type','ClusterIP')=='ClusterIP'
    if obj['kind']=='Ingress':assert obj['spec']['ingressClassName']=='traefik'
assert memory==4096,memory
kinds={(o['kind'],o['metadata']['name']):o for o in docs}
assert kinds[('ServiceAccount','conhecerse-workload')]['automountServiceAccountToken'] is False
assert 'role = "conhecerse-dev"' in kinds[('ConfigMap','conhecerse-bao-agent')]['data']['agent.hcl']
assert 'conhecerse-bao-token' not in (root/'infra/k3s/dev/conhecerse.yaml').read_text(encoding='utf-8')
pipeline=(root/'pipelines/deploy-conhecerse-dev.Jenkinsfile').read_text(encoding='utf-8')
for value in ['agent none','moby/buildkit:v0.23.2-rootless','lab-deployer','github-ssh','DRY_RUN','SKIP_TEST','conhecerse-dockerfile','rollout status']:
    assert value in pipeline,value
# SEC-013: one OCI digest is built, scanned, signed, verified and rolled out.
for value in ['--metadata-file','image-digests.env','cosign sign --yes --key','--tlog-upload=false','cosign verify --key cosign.pub','conhecerse-cosign-key','rollout-images.txt','@${env.API_DIGEST}','5368709120']:
    assert value in pipeline,value
assert ':$IMAGE_TAG"' not in pipeline.split("stage('SCA e SBOM das imagens')")[1],'downstream stages must use digests'
assert 'docker.sock' not in pipeline and 'privileged: true' not in pipeline
print(f'Infraestrutura: {len(docs)} objetos; {memory} MiB; contratos do QUICKSTART presentes.')
