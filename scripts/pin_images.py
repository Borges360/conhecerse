"""Resolve OCI tags to immutable digests, without building or publishing images."""
import json,re,urllib.request,urllib.error,urllib.parse
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
ACCEPT='application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json, application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json'
def digest(image):
    base=image.split('@')[0];name,tag=base.rsplit(':',1);parts=name.split('/')
    if '.' in parts[0]:host=parts.pop(0)
    else:host='registry-1.docker.io'
    repository='/'.join(parts)
    if host=='registry-1.docker.io' and '/' not in repository:repository='library/'+repository
    url=f'https://{host}/v2/{repository}/manifests/{tag}'
    headers={'Accept':ACCEPT}
    try:response=urllib.request.urlopen(urllib.request.Request(url,headers=headers),timeout=30)
    except urllib.error.HTTPError as e:
        if e.code!=401:raise
        auth=dict(re.findall(r'(\w+)="([^"]*)"',e.headers['WWW-Authenticate']))
        token_url=auth['realm']+'?'+urllib.parse.urlencode({'service':auth.get('service',''),'scope':auth.get('scope','repository:'+repository+':pull')})
        token=json.load(urllib.request.urlopen(token_url));headers['Authorization']='Bearer '+token.get('token',token.get('access_token'))
        response=urllib.request.urlopen(urllib.request.Request(url,headers=headers),timeout=30)
    value=response.headers.get('Docker-Content-Digest');assert value and value.startswith('sha256:'),base
    return value
paths=[ROOT/'infra/images/conhecerse/Dockerfile',ROOT/'infra/local/compose.yaml',ROOT/'infra/local/minio.Dockerfile',ROOT/'pipelines/deploy-conhecerse-dev.Jenkinsfile',ROOT/'infra/helm/conhecerse/values.yaml',ROOT/'infra/opentofu/video-init.sh.tftpl']
images={}
for p in paths:
    text=p.read_text(encoding='utf-8')
    for m in re.finditer(r'(?:FROM |image:\s*)([^\s\n]+)|(livekit/livekit-server:[^\s\n]+)',text):
        image=next(g for g in m.groups() if g).split('@')[0]
        if '${' in image or ':' not in image or image.startswith('conhecerse/minio-local:'):continue
        if image not in images:
            images[image]=digest(image);print(image+' -> '+images[image])
    for name,hash in images.items():text=re.sub(re.escape(name)+r'(?:@sha256:[a-f0-9]{64})?(?=[\s\n])',name+'@'+hash,text)
    p.write_text(text,encoding='utf-8')
(ROOT/'infra/images/images.lock.json').write_text(json.dumps(images,indent=2)+'\n',encoding='utf-8')
