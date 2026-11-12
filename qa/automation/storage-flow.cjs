// Real S3/AV/KMS must be configured on the local API. This never records a simulated pass.
if(process.env.E2E_ISOLATED_SYNTHETIC!=='true')throw new Error('Requer ambiente local exclusivo com dados sintéticos.');
const baseURL=process.env.E2E_BASE_URL||'http://127.0.0.1:3021';if(!['localhost','127.0.0.1'].includes(new URL(baseURL).hostname))throw new Error('Somente localhost.');
const {chromium}=require('../../apps/web/node_modules/@playwright/test');const fs=require('node:fs');const crypto=require('node:crypto');
function eicarZip(){
 const sample=Buffer.from('X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*','ascii');
 const name=Buffer.from('eicar.com','ascii');let crc=0xffffffff;
 for(const byte of sample){crc^=byte;for(let bit=0;bit<8;bit++)crc=(crc>>>1)^((crc&1)?0xedb88320:0);}
 crc=(crc^0xffffffff)>>>0;
 const local=Buffer.alloc(30);local.writeUInt32LE(0x04034b50,0);local.writeUInt16LE(20,4);local.writeUInt32LE(crc,14);local.writeUInt32LE(sample.length,18);local.writeUInt32LE(sample.length,22);local.writeUInt16LE(name.length,26);
 const central=Buffer.alloc(46);central.writeUInt32LE(0x02014b50,0);central.writeUInt16LE(20,4);central.writeUInt16LE(20,6);central.writeUInt32LE(crc,16);central.writeUInt32LE(sample.length,20);central.writeUInt32LE(sample.length,24);central.writeUInt16LE(name.length,28);
 const localRecord=Buffer.concat([local,name,sample]),centralRecord=Buffer.concat([central,name]),end=Buffer.alloc(22);
 end.writeUInt32LE(0x06054b50,0);end.writeUInt16LE(1,8);end.writeUInt16LE(1,10);end.writeUInt32LE(centralRecord.length,12);end.writeUInt32LE(localRecord.length,16);
 return Buffer.concat([localRecord,centralRecord,end]);
}
(async()=>{
 const browser=await chromium.launch({channel:process.env.PLAYWRIGHT_CHANNEL||'chrome',headless:true});const context=await browser.newContext({storageState:'.local/oidc-state.json'});const page=await context.newPage();await page.goto(baseURL+'/app');
 async function api(path,body,method='POST'){return page.evaluate(async({path,body,method})=>{const session=await fetch('/api/session').then(r=>r.json());const response=await fetch('/api/bff'+path,{method,headers:{'Content-Type':'application/json','X-CSRF-Token':session.csrfToken,'Idempotency-Key':crypto.randomUUID()},body:body===undefined?undefined:JSON.stringify(body)});const data=await response.json();if(!response.ok)throw new Error('API_'+response.status+'_'+data.code);return data;},{path,body,method});}
 const org=await api('/v1/orgs',{displayName:'Storage sintético',slug:'storage-'+Date.now(),legalPersonType:'PF',ownerIsPsychologist:true});const base='/v1/orgs/'+org.id;const owner=(await api(base+'/members',undefined,'GET')).items.find(m=>m.roles.includes('OWNER'));
 const professional=await api(base+'/professionals',{userId:owner.userId,crpUf:'RS',crpNumber:'SYNTHETIC-STORAGE',displayName:'Profissional sintético'});await api(base+'/professionals/'+professional.id+'/review',{status:'VERIFIED',evidence:'SYNTHETIC_STORAGE_TEST_ONLY'});
 const patient=await api(base+'/patients',{fullName:'Paciente sintético storage',professionalId:professional.id});
 const clean=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6N3sAAAAASUVORK5CYII=','base64');
 async function upload(bytes){const result=await api(base+'/files/uploads',{filename:'synthetic.png',contentType:'image/png',sizeBytes:bytes.length,sha256:crypto.createHash('sha256').update(bytes).digest('hex'),classification:'SENSITIVE_CLINICAL',patientId:patient.id});const put=await page.request.put(result.uploadUrl,{headers:{'Content-Type':'image/png'},data:bytes});if(!put.ok())throw new Error('STORAGE_PUT_FAILED');return {...result,...await api(base+'/files/'+result.id+'/complete')};}
 const good=await upload(clean);if(good.status!=='READY')throw new Error('CLEAN_NOT_READY');const ticket=await api(base+'/files/'+good.id+'/download');const response=await page.request.get(ticket.downloadUrl);if(response.status()!==200||!Buffer.from(await response.body()).equals(clean))throw new Error('DOWNLOAD_MISMATCH');
 if((await page.request.get(ticket.downloadUrl)).status()!==403)throw new Error('SINGLE_USE_NOT_ENFORCED');
 const anonymous=await browser.newContext();if((await anonymous.request.get(ticket.downloadUrl)).status()!==401)throw new Error('ANONYMOUS_DOWNLOAD_ALLOWED');await anonymous.close();
 // Embed the canonical EICAR member in a ZIP appended to a supported PNG; a raw signature after PNG bytes is not a valid EICAR sample.
 const infected=await upload(Buffer.concat([clean,eicarZip()]));if(infected.status!=='REJECTED'||infected.failureCode!=='MALWARE_DETECTED')throw new Error('EICAR_NOT_QUARANTINED');
 let rejectedDownloadDenied=false;try{await api(base+'/files/'+infected.id+'/download');}catch(e){rejectedDownloadDenied=String(e.message).startsWith('API_422_FILE_NOT_READY');}if(!rejectedDownloadDenied)throw new Error('REJECTED_FILE_DOWNLOAD_ALLOWED');
 const fresh=await api(base+'/files/'+good.id+'/download');await new Promise(resolve=>setTimeout(resolve,61000));if((await page.request.get(fresh.downloadUrl)).status()!==403)throw new Error('TTL_NOT_ENFORCED');
 fs.writeFileSync('ops/evidencias/storage-local.json',JSON.stringify({recordedAt:new Date().toISOString(),realProviders:true,releaseApproved:false,uploadValidated:true,downloadChecksum:true,anonymousDenied:true,singleUseEnforced:true,ttl60SecondsEnforced:true,eicarQuarantined:true,rejectedDownloadDenied:true},null,2));await browser.close();console.log('Storage real: upload, checksum, autenticação, TTL, uso único, EICAR e bloqueio do arquivo rejeitado passaram.');
})().catch(()=>{console.error('Storage real não validado. Confira os serviços e as credenciais locais; nenhuma homologação foi registrada.');process.exit(1);});
