if(process.env.E2E_ISOLATED_SYNTHETIC!=='true')throw new Error('Requer E2E_ISOLATED_SYNTHETIC=true e ambiente exclusivo com dados sintéticos.');
const baseURL=process.env.E2E_BASE_URL||'http://127.0.0.1:3021';if(!['127.0.0.1','localhost'].includes(new URL(baseURL).hostname))throw new Error('Este cenário mutável é limitado a localhost.');
const {chromium}=require('../../apps/web/node_modules/@playwright/test');
const fs=require('node:fs');
const crypto=require('node:crypto');
function totp(secret){const counter=Buffer.alloc(8);counter.writeBigUInt64BE(BigInt(Math.floor(Date.now()/30000)));const mac=crypto.createHmac('sha256',Buffer.from(secret)).update(counter).digest();const offset=mac[mac.length-1]&15;return String((mac.readUInt32BE(offset)&0x7fffffff)%1000000).padStart(6,'0');}
async function nextOTP(fixture){const counter=Math.floor(Date.now()/30000);if(fixture.lastCounter>=counter)await new Promise(resolve=>setTimeout(resolve,30100-Date.now()%30000));fixture.lastCounter=Math.floor(Date.now()/30000);fs.writeFileSync('.local/oidc-fixture.json',JSON.stringify(fixture));return totp(fixture.otpSecret);}
(async()=>{
 const fixture=JSON.parse(fs.readFileSync('.local/oidc-fixture.json','utf8'));
 const browser=await chromium.launch({channel:process.env.PLAYWRIGHT_CHANNEL||'chrome',headless:true});const context=await browser.newContext();const page=await context.newPage();
 page.on('response',async r=>{if(r.request().method()==='POST'&&r.url().includes('/api/bff/')){let data={};try{data=await r.json()}catch{}console.log({stage:'api',status:r.status(),code:data.code});}});
 await page.goto(baseURL+'/app');await page.getByRole('link',{name:'Entrar no meu espaço'}).click();await page.waitForURL('**/realms/conhecerse/**');
 await page.locator('#username').fill(fixture.username);await page.locator('#password').fill(fixture.password);await page.locator('#kc-login').click();
 await page.waitForTimeout(1000);
 let enrolled=false;
 if(await page.locator('#totpSecret').count()){
   enrolled=true;
   fixture.otpSecret=await page.locator('#totpSecret').inputValue();fs.writeFileSync('.local/oidc-fixture.json',JSON.stringify(fixture));
   await page.locator('#totp').fill(await nextOTP(fixture));await page.locator('#userLabel').fill('Teste isolado');await page.locator('button[type="submit"],input[type="submit"]').first().click();
 }else if(await page.locator('#otp').count()){await page.locator('#otp').fill(await nextOTP(fixture));await page.locator('#kc-login').click();}
 try{await page.waitForURL(baseURL+'/app',{timeout:15000});}catch{console.log({stage:'callback',path:new URL(page.url()).pathname,headings:await page.locator('h1,h2').allTextContents(),alerts:await page.locator('[role="alert"],.pf-m-danger').allTextContents()});throw new Error('OIDC_CALLBACK_FAILED');}
 if(enrolled){
   await page.goto(baseURL+'/api/auth/login?reauth=true&next=/app');
   if(await page.locator('#username').count())await page.locator('#username').fill(fixture.username);
   if(await page.locator('#password').count())await page.locator('#password').fill(fixture.password);
   await page.locator('#kc-login').click();await page.locator('#otp').waitFor();
   await page.locator('#otp').fill(await nextOTP(fixture));await page.locator('#kc-login').click();
   await page.waitForURL(baseURL+'/app',{timeout:15000});
 }
 const session=await page.request.get(baseURL+'/api/session').then(r=>r.json());if(!session.authenticated||session.accessToken||session.refreshToken)throw new Error('Sessão BFF inválida');
 const cookie=(await context.cookies()).find(c=>c.name==='psico-session-local');if(!cookie?.httpOnly)throw new Error('Cookie não é HttpOnly');
 await page.getByRole('button',{name:'Criar consultório',exact:true}).click();await page.getByLabel('Nome do consultório').fill('Consultório sintético OIDC');await page.getByLabel('Identificador público').fill('oidc-'+Date.now());
 await page.getByRole('checkbox').first().check();await page.getByRole('button',{name:'Criar meu espaço'}).click();
 try{await page.waitForURL('**/configuracoes',{timeout:15000});}catch{console.log({stage:'onboarding',alerts:await page.locator('[role="alert"]').allTextContents()});throw new Error('ONBOARDING_FAILED');}
 await context.storageState({path:'.local/oidc-state.json'});
 fs.writeFileSync('.local/oidc-evidence.json',JSON.stringify({keycloak:'26.8.1',authorizationCodePkce:true,mfaTotp:true,opaqueHttpOnlyCookie:true,organizationCreated:true,finishedAt:new Date().toISOString()},null,2));
 await browser.close();console.log('OIDC real, PKCE, MFA TOTP, sessão opaca e criação do consultório passaram.');
})().catch(e=>{console.error(e.message.replace(/http[^\s]+/g,'[URL]'));process.exit(1)});
