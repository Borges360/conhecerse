"""Remove enrolled second factors and their login requirements from an existing realm.
Uses the Keycloak Admin API so credentials are removed from the identity database.
Set KEYCLOAK_URL, KEYCLOAK_ADMIN and KEYCLOAK_ADMIN_PASSWORD before running.
"""
import json, os, urllib.request, urllib.parse

def main():
    base=os.environ['KEYCLOAK_URL'].rstrip('/')
    realm=os.environ.get('KEYCLOAK_REALM','conhecerse')
    data=urllib.parse.urlencode({'grant_type':'password','client_id':'admin-cli','username':os.environ['KEYCLOAK_ADMIN'],'password':os.environ['KEYCLOAK_ADMIN_PASSWORD']}).encode()
    with urllib.request.urlopen(base+'/realms/master/protocol/openid-connect/token',data=data,timeout=30) as response:
        token=json.load(response)['access_token']
    def api(path,method='GET',body=None):
        request=urllib.request.Request(base+'/admin/realms/'+realm+path,headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'},method=method,data=json.dumps(body).encode() if body is not None else None)
        with urllib.request.urlopen(request,timeout=30) as response:
            raw=response.read()
            return json.loads(raw) if raw else None
    providers={'auth-otp-form','auth-conditional-otp-form','webauthn-authenticator','recovery-authn-code-form'}
    actions={'CONFIGURE_TOTP','CONFIGURE_RECOVERY_AUTHN_CODES','webauthn-register'}
    removed=0
    for flow in api('/authentication/flows'):
        if flow.get('builtIn'):continue
        path='/authentication/flows/'+urllib.parse.quote(flow['alias'],safe='')+'/executions'
        for execution in api(path):
            if execution.get('providerId') in providers:
                api('/authentication/executions/'+execution['id'],'DELETE')
    for action in api('/authentication/required-actions'):
        if action['alias'] in actions:
            api('/authentication/required-actions/'+action['alias'],'DELETE')
    for client in api('/clients?clientId=conhecerse-web'):
        path='/clients/'+client['id']+'/protocol-mappers/models'
        for mapper in api(path):
            if mapper.get('protocolMapper')=='oidc-amr-mapper':api(path+'/'+mapper['id'],'DELETE')
    first=0
    while True:
        users=api('/users?first='+str(first)+'&max=100')
        if not users:break
        for user in users:
            path='/users/'+user['id']
            current=api(path)
            pending=current.get('requiredActions',[])
            if any(a in actions for a in pending):
                api(path,'PUT',{'requiredActions':[a for a in pending if a not in actions]})
            for credential in api(path+'/credentials'):
                if credential['type'] in {'otp','recovery-authn-codes','webauthn'}:
                    api(path+'/credentials/'+credential['id'],'DELETE');removed+=1
            assert not any(c['type'] in {'otp','recovery-authn-codes','webauthn'} for c in api(path+'/credentials'))
        first+=len(users)
    print(json.dumps({'realm':realm,'usersVerified':first,'credentialsDeleted':removed,'remainingSecondFactors':0}))

if __name__=='__main__':main()
