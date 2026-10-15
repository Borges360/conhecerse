import http from 'k6/http';
import {check} from 'k6';
// Each fixture is an independent synthetic identity, with its own scoped BFF cookie.
if(__ENV.LOAD_AUTHORIZED!=='true')throw new Error('Requer autorização explícita para carga em ambiente isolado.');
const base=__ENV.LOAD_BASE_URL,fixtures=JSON.parse(__ENV.LOAD_FIXTURES_JSON||'[]');
if(!base||fixtures.length<50)throw new Error('Informe URL staging isolada e pelo menos 50 vínculos sintéticos independentes.');
export const options={scenarios:{agenda:{executor:'constant-arrival-rate',rate:100,timeUnit:'1s',duration:__ENV.LOAD_DURATION||'60m',preAllocatedVUs:50,maxVUs:200}},thresholds:{http_req_failed:['rate<0.001'],http_req_duration:['p(95)<400','p(99)<1200'],checks:['rate==1']}};
export default function(){const fixture=fixtures[(__VU-1)%fixtures.length];const response=http.get(base+'/api/bff/v1/orgs/'+fixture.orgId+'/appointments?limit=25',{headers:{Cookie:fixture.cookie},tags:{name:'agenda-read'}});check(response,{'agenda autorizada responde200':r=>r.status===200,'resposta paginada':r=>{try{return Array.isArray(r.json('items'));}catch{return false;}}});}
