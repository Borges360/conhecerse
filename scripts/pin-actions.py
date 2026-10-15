"""Resolve GitHub Actions to immutable upstream commits. Does not modify GitHub."""
import json,re,urllib.request
from pathlib import Path
root=Path(__file__).resolve().parents[1]
def get(url):
    with urllib.request.urlopen(urllib.request.Request(url,headers={'User-Agent':'conhecerse-build-lock'}),timeout=20) as response:return json.load(response)
locks={}
for path in sorted((root/'.github/workflows').glob('*.yml')):
    text=path.read_text()
    for match in list(re.finditer(r'uses: ([\w.-]+/[\w./-]+)@([\w.-]+)',text)):
        repo,version=match.groups()
        if re.fullmatch('[a-f0-9]{40}',version):
            tag=re.match(r' # ([\w.-]+)',text[match.end():]);locks[repo+'@'+(tag[1] if tag else version)]=version;continue
        origin='/'.join(repo.split('/')[:2]);print(repo+'@'+version)
        data=get(f'https://api.github.com/repos/{origin}/git/ref/tags/{version}')['object']
        while data['type']=='tag':data=get(f'https://api.github.com/repos/{origin}/git/tags/{data["sha"]}')['object']
        assert data['type']=='commit';locks[repo+'@'+version]=data['sha']
        text=text.replace(repo+'@'+version,repo+'@'+data['sha']+' # '+version)
    path.write_text(text,encoding='utf-8')
(root/'.github/actions.lock.json').write_text(json.dumps(locks,indent=2)+'\n',encoding='utf-8')
print(f'{len(locks)} Actions fixadas em commits upstream.')
