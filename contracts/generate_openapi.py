from collections import OrderedDict
from pathlib import Path
import yaml,re
ROOT=Path(__file__).parent

def ref(name): return {'$ref':f'#/components/schemas/{name}'}
def props(**kwargs): return {k:{'type':v} if isinstance(v,str) else v for k,v in kwargs.items()}
def obj(required=(),**fields):
 d={'type':'object','additionalProperties':False,'properties':props(**fields)}
 if required:d['required']=list(required)
 return d
uuid={'type':'string','format':'uuid'}
instant={'type':'string','format':'date-time'}
date={'type':'string','format':'date'}
positive={'type':'integer','format':'int64','minimum':0}
enm=lambda *v:{'type':'string','enum':list(v)}
string={'type':'string','maxLength':500}

schemas={
 'Problem':obj(('type','title','status','code','requestId'),type='string',title='string',status='integer',code='string',detail='string',requestId='string',violations={'type':'array','items':obj(('field','message'),field='string',message='string')}),
 'Ok':obj(('id','version'),id=uuid,version='integer'),
 'Page':obj(('items','nextCursor'),items={'type':'array','items':{'type':'object'}},nextCursor={'type':['string','null']}),
 'OrganizationCreate':obj(('slug','displayName','legalPersonType'),slug={'type':'string','pattern':'^[a-z0-9-]{3,40}$'},displayName={'type':'string','minLength':2,'maxLength':160},legalPersonType=enm('PF','PJ'),timezone={'type':'string','default':'America/Sao_Paulo'}),
 'OrganizationPatch':obj((),displayName=string,timezone='string',status=enm('ACTIVE','SUSPENDED')),
 'Organization':obj(('id','displayName','timezone','status'),id=uuid,displayName='string',timezone='string',status='string',settingsVersion='integer'),
 'MemberInvite':obj(('email','roles'),email={'type':'string','format':'email'},roles={'type':'array','minItems':1,'items':enm('OWNER','ADMIN','PSYCHOLOGIST','SECRETARY','BILLING')}),
 'ProfessionalCreate':obj(('userId','crpUf','crpNumber','displayName'),userId=uuid,crpUf={'type':'string','pattern':'^[A-Z]{2}$'},crpNumber={'type':'string','minLength':2,'maxLength':25},displayName=string,publicProfileEnabled='boolean'),
 'Professional':obj(('id','displayName','crpUf','crpNumber','verificationStatus'),id=uuid,displayName='string',crpUf='string',crpNumber='string',verificationStatus=enm('PENDING','VERIFIED','REJECTED','SUSPENDED'),publicProfileEnabled='boolean'),
 'PatientCreate':obj(('fullName',),fullName={'type':'string','minLength':2,'maxLength':200},socialName={'type':'string','maxLength':200},cpf={'type':'string','pattern':'^[0-9]{11}$'},email={'type':'string','format':'email'},phone={'type':'string','pattern':'^\\+[1-9][0-9]{7,14}$'},birthDate=date,communicationPreference=enm('EMAIL','WHATSAPP','NONE'),professionalId=uuid),
 'PatientPatch':obj((),fullName='string',socialName='string',email={'type':'string','format':'email'},phone='string',communicationPreference=enm('EMAIL','WHATSAPP','NONE')),
 'Patient':obj(('id','displayName','status'),id=uuid,displayName='string',status=enm('ACTIVE','INACTIVE','DECEASED'),birthDate=date,assignedProfessionals={'type':'array','items':uuid}),
 'PatientInvite':obj(('channel',),channel=enm('EMAIL','WHATSAPP')),
 'CareAssignmentCreate':obj(('professionalId',),professionalId=uuid,startAt=instant),
 'CareAssignment':obj(('id','professionalId','patientId','startAt'),id=uuid,professionalId=uuid,patientId=uuid,startAt=instant,endAt=instant),
 'AppointmentCreate':obj(('patientId','professionalId','startAt','endAt','modality'),patientId=uuid,professionalId=uuid,startAt=instant,endAt=instant,modality=enm('REMOTE','IN_PERSON'),priceCents=positive,reasonCode={'type':'string','maxLength':60}),
 'Appointment':obj(('id','patientId','professionalId','startAt','endAt','status','version','modality'),id=uuid,patientId=uuid,professionalId=uuid,startAt=instant,endAt=instant,status=enm('REQUESTED','SCHEDULED','CONFIRMED','IN_PROGRESS','COMPLETED','NO_SHOW','CANCELLED'),version='integer',modality=enm('REMOTE','IN_PERSON'),rescheduledFromId=uuid,priceCents=positive),
 'AppointmentTransition':obj(('reasonCode',),reasonCode={'type':'string','minLength':2,'maxLength':60},note={'type':'string','maxLength':500}),
 'RecurrenceCreate':obj(('patientId','professionalId','rrule','localStartTime','timezone','startsOn'),patientId=uuid,professionalId=uuid,rrule={'type':'string','maxLength':200},localStartTime={'type':'string','pattern':'^[0-2][0-9]:[0-5][0-9]$'},timezone='string',startsOn=date,endsOn=date,maxCount={'type':'integer','minimum':1,'maximum':52}),
 'RescheduleCreate':obj(('desiredStartAt','desiredEndAt'),desiredStartAt=instant,desiredEndAt=instant,message={'type':'string','maxLength':500}),
 'Reschedule':obj(('id','appointmentId','status','desiredStartAt','desiredEndAt'),id=uuid,appointmentId=uuid,status=enm('PENDING','ACCEPTED','DECLINED','EXPIRED','WITHDRAWN'),desiredStartAt=instant,desiredEndAt=instant,newAppointmentId=uuid),
 'VideoAccess':obj(('serverUrl','accessToken','expiresAt','e2eeKey'),serverUrl={'type':'string','format':'uri'},accessToken='string',expiresAt=instant,e2eeKey={'type':'string','description':'Chave efêmera distribuída no canal autenticado; nunca logar ou persistir'}),
 'ClinicalNoteCreate':obj(('patientId','occurredAt','narrative'),patientId=uuid,appointmentId=uuid,occurredAt=instant,narrative={'type':'string','minLength':1,'maxLength':50000},plan={'type':'string','maxLength':20000},referrals={'type':'string','maxLength':20000},noteType=enm('EVOLUTION','INITIAL_ASSESSMENT','REFERRAL','ADDENDUM'),revisionOfId=uuid),
 'ClinicalNotePatch':obj((),narrative={'type':'string','minLength':1,'maxLength':50000},plan='string',referrals='string'),
 'ClinicalNote':obj(('id','patientId','occurredAt','status','version'),id=uuid,patientId=uuid,occurredAt=instant,status=enm('DRAFT','SIGNED'),version='integer',signedAt=instant,narrative='string',plan='string',referrals='string',sha256='string'),
 'PrivacyRequestCreate':obj(('type',),type=enm('ACCESS','RECTIFY','PORTABILITY','ERASURE','REVIEW'),reason={'type':'string','maxLength':1000}),
 'FileInit':obj(('filename','contentType','sizeBytes','sha256','classification'),filename={'type':'string','minLength':1,'maxLength':255},contentType=enm('application/pdf','image/png','image/jpeg'),sizeBytes={'type':'integer','minimum':1,'maximum':26214400},sha256={'type':'string','pattern':'^[a-f0-9]{64}$'},classification=enm('SENSITIVE_CLINICAL','PERSONAL','INTERNAL','PUBLIC','FINANCIAL')),
 'FileUpload':obj(('id','uploadUrl','expiresAt'),id=uuid,uploadUrl={'type':'string','format':'uri'},expiresAt=instant),
 'FileDownload':obj(('id','downloadUrl','expiresAt'),id=uuid,downloadUrl={'type':'string','format':'uri'},expiresAt=instant),
 'InvoiceCreate':obj(('payerId','dueOn','items'),payerId=uuid,dueOn=date,items={'type':'array','minItems':1,'items':obj(('descriptionCode','amountCents'),descriptionCode='string',amountCents=positive,appointmentId=uuid)}),
 'Invoice':obj(('id','payerId','amountCents','paidCents','dueOn','status'),id=uuid,payerId=uuid,amountCents=positive,paidCents=positive,dueOn=date,status=enm('DRAFT','OPEN','PARTIALLY_PAID','PAID','OVERDUE','CANCELLED','REFUNDED','DISPUTED')),
 'PayerCreate':obj(('fullName','type'),fullName='string',type=enm('PF','PJ'),cpfCnpj={'type':'string','minLength':11,'maxLength':14},patientId=uuid),
 'PaymentCreate':obj(('method',),method=enm('PIX','BOLETO','CREDIT_CARD'),returnUrl={'type':'string','format':'uri'}),
 'PaymentCheckout':obj(('paymentId','checkoutUrl','status'),paymentId=uuid,checkoutUrl={'type':'string','format':'uri'},status=enm('PENDING','FAILED')),
 'ManualPayment':obj(('amountCents','reason'),amountCents=positive,reason={'type':'string','minLength':8,'maxLength':1000},receivedOn=date),
 'ReceiptReference':obj(('paymentId','patientId','status'),paymentId=uuid,patientId=uuid,status=enm('RECEIPT_PENDING','EXTERNAL_ISSUED','NOT_APPLICABLE','REVIEW_REQUIRED','VOIDED_EXTERNAL'),officialReceiptId='string',externalIssuedOn=date,amountCents=positive),
 'ReceiptRegister':obj(('officialReceiptId','externalIssuedOn','amountCents','payerId'),officialReceiptId={'type':'string','minLength':2,'maxLength':100},externalIssuedOn=date,amountCents=positive,payerId=uuid,evidenceFileId=uuid),
 'DocumentCreate':obj(('patientId','type','templateVersion','content'),patientId=uuid,type=enm('DECLARATION','CERTIFICATE','REPORT','OPINION','PSYCHOLOGICAL_REPORT'),templateVersion='string',content={'type':'string','minLength':1,'maxLength':50000}),
 'DocumentSignedUpload':obj(('signedFileId','signatureMethod','verificationStatus'),signedFileId=uuid,signatureMethod=enm('GOV_BR_EXTERNAL','ICP_BRASIL_EXTERNAL','PHYSICAL_SIGNED_DIGITIZED'),verificationStatus=enm('AUTOMATIC_VALIDATED','MANUAL_REVIEWED'),verificationEvidence={'type':'string','maxLength':2000}),
 'Document':obj(('id','patientId','type','status','version'),id=uuid,patientId=uuid,type='string',status=enm('DRAFT','PENDING_SIGNATURE','ISSUED','REVOKED'),version='integer',issuedAt=instant,downloadAvailable='boolean'),
 'ContentCreate':obj(('entryType','title','visibility'),entryType=enm('BLOG','NEWSLETTER','MATERIAL'),title={'type':'string','minLength':1,'maxLength':180},bodyHtml={'type':'string','maxLength':100000},visibility=enm('PUBLIC','PRIVATE_TEAM','PATIENT_ASSIGNED','PATIENT_SHARED'),slug={'type':'string','pattern':'^[a-z0-9-]{3,100}$'}),
 'Content':obj(('id','entryType','title','status','visibility'),id=uuid,entryType='string',title='string',status=enm('DRAFT','REVIEW','PUBLISHED','ARCHIVED'),visibility='string',publishedAt=instant),
 'MaterialShare':obj(('patientId',),patientId=uuid,validUntil=instant),
 'NewsletterSubscribe':obj(('email','noticeVersion'),email={'type':'string','format':'email'},noticeVersion='string'),
 'DailyReport':obj(('localDate','status'),localDate=date,status=enm('PENDING','GENERATED','DELAYED','FAILED'),generatedAt=instant,aggregate={'type':'object','additionalProperties':True}),
 'SettingsPatch':obj((),minNoticeHours={'type':'integer','minimum':0,'maximum':720},cancelChargePercent={'type':'integer','minimum':0,'maximum':100},defaultTimezone='string',emailEnabled='boolean',whatsappEnabled='boolean'),
 'PublicRequest':obj(('professionalId','patientName','patientEmail','startAt','endAt'),professionalId=uuid,patientName='string',patientEmail={'type':'string','format':'email'},startAt=instant,endAt=instant,noticeVersion='string'),
 'PreferencePatch':obj((),marketingEmail='boolean',marketingWhatsapp='boolean',appointmentEmail='boolean',appointmentWhatsapp='boolean'),
 'WebhookAck':obj(('accepted',),accepted='boolean')
}

paths={}
orgparam={'name':'orgId','in':'path','required':True,'schema':uuid}
uidparam=lambda n:{'name':n,'in':'path','required':True,'schema':{'type':'string','pattern':'^[a-z0-9-]{3,40}$'} if n=='orgSlug' else uuid}
queryparams=[{'name':'limit','in':'query','schema':{'type':'integer','minimum':1,'maximum':100,'default':25}},{'name':'cursor','in':'query','schema':{'type':'string'}}]

def add(method,path,tag,summary,permission,response='Ok',request=None,mode='authenticated',idem=False,etag=False,paged=False,extra_params=None,code=None):
 target=paths.setdefault(path,{})
 params=[]
 for key in re.findall(r'\{(\w+)\}',path):params.append(orgparam if key=='orgId' else uidparam(key))
 if paged:params+=queryparams
 if extra_params:params+=extra_params
 if idem:params.append({'name':'Idempotency-Key','in':'header','required':True,'schema':uuid})
 if etag:params.append({'name':'If-Match','in':'header','required':True,'schema':{'type':'string','pattern':'^"v[1-9][0-9]*"$'}})
 status=code or (201 if method=='post' and not path.endswith('/actions/confirm') else 200)
 op={'tags':[tag], 'summary':summary, 'operationId':method+'_'+re.sub(r'\W+','_',path).strip('_'),
     'description':f'Permissão obrigatória: {permission}. ABAC, tenant e estado do recurso validados no servidor.',
     'x-required-permission':permission,
     'responses':{str(status):{'description':'Sucesso','content':{'application/json':{'schema':ref(response)}}},
                  '400':{'$ref':'#/components/responses/Problem'},'401':{'$ref':'#/components/responses/Problem'},'403':{'$ref':'#/components/responses/Problem'},'404':{'$ref':'#/components/responses/Problem'},'409':{'$ref':'#/components/responses/Problem'},'422':{'$ref':'#/components/responses/Problem'}}}
 if mode=='public':op['security']=[]
 elif mode=='webhook':op['security']=[{'AsaasWebhookSecret':[]}]
 else:op['security']=[{'BffSession':[]}]
 if params:op['parameters']=params
 if request:op['requestBody']={'required':True,'content':{'application/json':{'schema':ref(request)}}}
 if etag:op['responses']['412']={'$ref':'#/components/responses/Problem'};op['responses']['428']={'$ref':'#/components/responses/Problem'}
 target[method]=op

# Public and identity
add('get','/v1/me/organizations','Identity','Listar organizações do usuário','LOGIN','Page')
add('post','/v1/orgs','Tenancy','Criar consultório/clínica','USER_REGISTERED','Organization','OrganizationCreate',idem=True)
add('get','/v1/orgs/{orgId}','Tenancy','Detalhar organização','MEMBER','Organization')
add('patch','/v1/orgs/{orgId}','Tenancy','Atualizar organização','OWNER_OR_ADMIN','Organization','OrganizationPatch',etag=True)
add('get','/v1/orgs/{orgId}/settings','Tenancy','Ler políticas vigentes','MEMBER','SettingsPatch')
add('patch','/v1/orgs/{orgId}/settings','Tenancy','Versionar políticas','OWNER_OR_ADMIN','SettingsPatch','SettingsPatch',etag=True)
add('post','/v1/orgs/{orgId}/members/invitations','Tenancy','Convidar membro','OWNER_OR_ADMIN','Ok','MemberInvite',idem=True)
add('get','/v1/orgs/{orgId}/members','Tenancy','Listar equipe','OWNER_OR_ADMIN','Page',paged=True)
add('get','/v1/orgs/{orgId}/professionals','Professionals','Listar profissionais','STAFF','Page',paged=True)
add('post','/v1/orgs/{orgId}/professionals','Professionals','Cadastrar psicólogo','OWNER_OR_ADMIN','Professional','ProfessionalCreate',idem=True)
add('get','/v1/orgs/{orgId}/professionals/{professionalId}','Professionals','Dados do profissional','MEMBER','Professional')
add('get','/v1/public/professionals','Public','Pesquisar perfis opt-in','PUBLIC','Page',mode='public',paged=True)
add('get','/v1/public/professionals/{professionalId}','Public','Ler perfil publicado','PUBLIC','Professional',mode='public')
add('post','/v1/public/booking-requests','Public','Solicitar primeira consulta','PUBLIC','Ok','PublicRequest',mode='public',idem=True)
# patient
add('get','/v1/orgs/{orgId}/patients','Patients','Buscar pacientes','PATIENT_DIRECTORY','Page',paged=True)
add('post','/v1/orgs/{orgId}/patients','Patients','Cadastrar paciente','PATIENT_WRITE','Patient','PatientCreate',idem=True)
add('get','/v1/orgs/{orgId}/patients/{patientId}','Patients','Consultar cadastro','PATIENT_READ_SCOPED','Patient')
add('patch','/v1/orgs/{orgId}/patients/{patientId}','Patients','Atualizar cadastro','PATIENT_WRITE_SCOPED','Patient','PatientPatch',etag=True)
add('post','/v1/orgs/{orgId}/patients/{patientId}/invite','Patients','Convidar ao portal','PATIENT_WRITE_SCOPED','Ok','PatientInvite',idem=True)
add('get','/v1/orgs/{orgId}/patients/{patientId}/assignments','Patients','Listar vínculos','PATIENT_READ_SCOPED','Page',paged=True)
add('post','/v1/orgs/{orgId}/patients/{patientId}/assignments','Patients','Designar profissional','ASSIGNMENT_MANAGE','CareAssignment','CareAssignmentCreate',idem=True)
add('post','/v1/orgs/{orgId}/patients/{patientId}/privacy-requests','Privacy','Abrir requerimento LGPD','SUBJECT_OR_AUTHORIZED','Ok','PrivacyRequestCreate',idem=True)
add('get','/v1/orgs/{orgId}/patients/{patientId}/privacy-requests','Privacy','Listar pedidos','SUBJECT_OR_AUTHORIZED','Page',paged=True)
# schedule
add('get','/v1/orgs/{orgId}/appointments','Scheduling','Listar agenda','APPOINTMENT_READ_SCOPED','Page',paged=True)
add('post','/v1/orgs/{orgId}/appointments','Scheduling','Criar sessão','APPOINTMENT_WRITE','Appointment','AppointmentCreate',idem=True)
add('get','/v1/orgs/{orgId}/appointments/{appointmentId}','Scheduling','Detalhar sessão','APPOINTMENT_READ_SCOPED','Appointment')
add('post','/v1/orgs/{orgId}/appointments/{appointmentId}/actions/confirm','Scheduling','Confirmar presença','PATIENT_OR_AUTHORIZED','Appointment',idem=True)
for command in ['accept','reject','start','complete','no-show','cancel']:
 add('post',f'/v1/orgs/{{orgId}}/appointments/{{appointmentId}}/actions/{command}','Scheduling',f'Comando {command} sessão','APPOINTMENT_COMMAND_SCOPED','Appointment','AppointmentTransition' if command in ('reject','no-show','cancel') else None,idem=True)
add('post','/v1/orgs/{orgId}/recurrence-series','Scheduling','Criar recorrência','APPOINTMENT_WRITE','Ok','RecurrenceCreate',idem=True)
add('post','/v1/orgs/{orgId}/appointments/{appointmentId}/reschedule-requests','Scheduling','Solicitar remarcação','PATIENT_OR_STAFF','Reschedule','RescheduleCreate',idem=True)
add('get','/v1/orgs/{orgId}/reschedule-requests','Scheduling','Fila de solicitações','APPOINTMENT_READ_SCOPED','Page',paged=True)
add('post','/v1/orgs/{orgId}/reschedule-requests/{rescheduleId}/actions/accept','Scheduling','Aceitar e trocar horário atomicamente','APPOINTMENT_COMMAND_SCOPED','Reschedule',idem=True)
add('post','/v1/orgs/{orgId}/reschedule-requests/{rescheduleId}/actions/decline','Scheduling','Recusar remarcação','APPOINTMENT_COMMAND_SCOPED','Reschedule',idem=True)
add('get','/v1/orgs/{orgId}/appointments/{appointmentId}/history','Scheduling','Histórico de agenda','APPOINTMENT_READ_SCOPED','Page',paged=True)
# video
add('post','/v1/orgs/{orgId}/appointments/{appointmentId}/video/access','Video','Emitir token efêmero de entrada E2EE','VIDEO_PARTICIPANT','VideoAccess',idem=True)
# clinical
add('get','/v1/orgs/{orgId}/patients/{patientId}/clinical-notes','Clinical','Listar evoluções autorizadas','CLINICIAN_ASSIGNED','Page',paged=True)
add('post','/v1/orgs/{orgId}/patients/{patientId}/clinical-notes','Clinical','Criar rascunho clínico','CLINICIAN_ASSIGNED','ClinicalNote','ClinicalNoteCreate',idem=True)
add('get','/v1/orgs/{orgId}/clinical-notes/{noteId}','Clinical','Ler evolução','CLINICIAN_ASSIGNED','ClinicalNote')
add('patch','/v1/orgs/{orgId}/clinical-notes/{noteId}','Clinical','Salvar rascunho','NOTE_AUTHOR','ClinicalNote','ClinicalNotePatch',etag=True)
add('post','/v1/orgs/{orgId}/clinical-notes/{noteId}/sign','Clinical','Assinar evolução','NOTE_AUTHOR_MFA','ClinicalNote',idem=True,etag=True)
add('post','/v1/orgs/{orgId}/clinical-notes/{noteId}/addenda','Clinical','Incluir adendo imutável','CLINICIAN_ASSIGNED','ClinicalNote','ClinicalNoteCreate',idem=True)
# assets /docs
add('post','/v1/orgs/{orgId}/files/uploads','Files','Criar upload temporário','FILE_WRITE_SCOPED','FileUpload','FileInit',idem=True)
add('post','/v1/orgs/{orgId}/files/{fileId}/complete','Files','Confirmar antivírus e checksum','FILE_WRITE_SCOPED','Ok',idem=True)
add('post','/v1/orgs/{orgId}/files/{fileId}/download','Files','URL assinada após ABAC','FILE_READ_SCOPED','FileDownload',idem=True)
add('get','/v1/orgs/{orgId}/documents','Documents','Listar documentos','DOCUMENT_READ_SCOPED','Page',paged=True)
add('post','/v1/orgs/{orgId}/documents','Documents','Criar documento psicológico','CLINICIAN_ASSIGNED','Document','DocumentCreate',idem=True)
add('get','/v1/orgs/{orgId}/documents/{documentId}','Documents','Detalhar documento','DOCUMENT_READ_SCOPED','Document')
add('post','/v1/orgs/{orgId}/documents/{documentId}/actions/render-draft','Documents','Gerar preview PDF não oficial','DOCUMENT_AUTHOR','FileDownload',idem=True)
add('post','/v1/orgs/{orgId}/documents/{documentId}/actions/request-signature','Documents','Abrir pendência de assinatura externa','DOCUMENT_AUTHOR','Document',idem=True)
add('post','/v1/orgs/{orgId}/documents/{documentId}/actions/submit-signature','Documents','Enviar para assinatura autorizada','DOCUMENT_AUTHOR','Document','DocumentSignedUpload',idem=True)
add('post','/v1/orgs/{orgId}/documents/{documentId}/actions/revoke','Documents','Revogar documento emitido','DOCUMENT_AUTHOR','Document','AppointmentTransition',idem=True)
add('post','/v1/orgs/{orgId}/documents/{documentId}/actions/publish','Documents','Disponibilizar ao paciente','DOCUMENT_AUTHOR','Document',idem=True)
# finance
add('post','/v1/orgs/{orgId}/payers','Billing','Cadastrar pagador','BILLING_WRITE','Ok','PayerCreate',idem=True)
add('get','/v1/orgs/{orgId}/invoices','Billing','Listar faturas','BILLING_READ_SCOPED','Page',paged=True)
add('post','/v1/orgs/{orgId}/invoices','Billing','Criar fatura','BILLING_WRITE','Invoice','InvoiceCreate',idem=True)
add('get','/v1/orgs/{orgId}/invoices/{invoiceId}','Billing','Detalhar fatura','BILLING_READ_SCOPED','Invoice')
add('post','/v1/orgs/{orgId}/invoices/{invoiceId}/actions/issue','Billing','Emitir fatura','BILLING_WRITE','Invoice',idem=True)
add('post','/v1/orgs/{orgId}/invoices/{invoiceId}/payments','Billing','Solicitar checkout PSP','BILLING_WRITE_OR_PAYER','PaymentCheckout','PaymentCreate',idem=True)
add('post','/v1/orgs/{orgId}/invoices/{invoiceId}/manual-payments','Billing','Registrar pagamento manual','BILLING_PRIVILEGED','Invoice','ManualPayment',idem=True)
add('get','/v1/orgs/{orgId}/financial-reports','Billing','Financeiro agregado sem dados clínicos','BILLING_READ','Page',paged=True)
add('get','/v1/orgs/{orgId}/receipt-references','Receipts','Listar recibos a registrar','BILLING_OR_CLINICIAN','Page',paged=True)
add('post','/v1/orgs/{orgId}/receipt-references/{receiptId}/actions/register','Receipts','Registrar recibo externo emitido','CLINICIAN_OR_FISCAL','ReceiptReference','ReceiptRegister',idem=True)
add('post','/v1/webhooks/asaas/{connectionId}','Webhooks','Receber callback oficial PSP','WEBHOOK_SECRET','WebhookAck',mode='webhook',code=200)
# content & reporting
add('get','/v1/orgs/{orgId}/content','Content','Listar conteúdos','CONTENT_READ_SCOPED','Page',paged=True)
add('post','/v1/orgs/{orgId}/content','Content','Criar conteúdo','CONTENT_WRITE','Content','ContentCreate',idem=True)
add('get','/v1/orgs/{orgId}/content/{contentId}','Content','Detalhar conteúdo','CONTENT_READ_SCOPED','Content')
add('post','/v1/orgs/{orgId}/content/{contentId}/actions/publish','Content','Publicar post ou material','CONTENT_PUBLISH','Content',idem=True)
add('post','/v1/orgs/{orgId}/content/{contentId}/shares','Content','Compartilhar material com paciente','CONTENT_SHARE','Ok','MaterialShare',idem=True)
add('get','/v1/public/blog/{orgSlug}','Public','Listar artigos públicos','PUBLIC','Page',mode='public',paged=True)
add('post','/v1/public/newsletter/subscribe','Public','Inscrever email com opt-in','PUBLIC','Ok','NewsletterSubscribe',mode='public',idem=True)
add('get','/v1/orgs/{orgId}/daily-reports','Reporting','Histórico de relatórios','REPORT_READ','Page',paged=True)
add('get','/v1/orgs/{orgId}/daily-reports/{reportId}','Reporting','Consultar relatório','REPORT_READ','DailyReport')
add('post','/v1/orgs/{orgId}/daily-reports/actions/regenerate','Reporting','Regenerar relatório auditado','REPORT_MANAGE','DailyReport',idem=True)
# portal
add('get','/v1/orgs/{orgId}/portal/me','Portal','Dados do portal vinculado','PATIENT_LINKED','Patient')
add('get','/v1/orgs/{orgId}/portal/appointments','Portal','Minhas sessões','PATIENT_LINKED','Page',paged=True)
add('get','/v1/orgs/{orgId}/portal/invoices','Portal','Minhas faturas autorizadas','PAYER_LINKED','Page',paged=True)
add('get','/v1/orgs/{orgId}/portal/documents','Portal','Meus documentos publicados','PATIENT_LINKED','Page',paged=True)
add('get','/v1/orgs/{orgId}/portal/materials','Portal','Meus materiais compartilhados','PATIENT_LINKED','Page',paged=True)
add('patch','/v1/orgs/{orgId}/portal/preferences','Portal','Configurar comunicações','PATIENT_LINKED','PreferencePatch','PreferencePatch',etag=True)

# ADR-011: explicit operations needed by the normative UI and operational workflows.
schemas.update({
 'ProfessionalReview':obj(('status','evidence'),status=enm('VERIFIED','REJECTED','SUSPENDED'),evidence={'type':'string','minLength':8,'maxLength':1000}),
 'AvailabilityCreate':obj(('weekday','localStart','localEnd','effectiveFrom','timezone'),weekday={'type':'integer','minimum':1,'maximum':7},localStart='string',localEnd='string',effectiveFrom=date,effectiveUntil=date,timezone='string'),
 'AvailabilityException':obj(('fromAt','toAt','reasonCode'),fromAt=instant,toAt=instant,reasonCode=string),
 'PriceCreate':obj(('modality','amountCents','effectiveFrom'),modality=enm('REMOTE','IN_PERSON'),amountCents={'type':'integer','minimum':0,'maximum':2000000},effectiveFrom=date,effectiveUntil=date),
 'IntegrationCreate':obj(('provider','label','secretsPath'),provider=enm('ASAAS','EMAIL','WHATSAPP','LIVEKIT','DOC_SIGNING'),label=string,secretsPath={'type':'string','pattern':'^conhecerse/(dev|staging|production)/[a-zA-Z0-9/_-]+$'},externalAccountRef=string,capabilities={'type':'object','additionalProperties':True}),
 'ReviewDecision':obj(('decisionCode','evidence'),decisionCode=string,evidence={'type':'string','minLength':8,'maxLength':2000}),
 'TemplateCreate':obj(('type','version','content'),type=enm('DECLARATION','CERTIFICATE','REPORT','OPINION','PSYCHOLOGICAL_REPORT'),version=string,content={'type':'string','maxLength':50000}),
 'PrivacyDecision':obj(('status','decisionCode'),status=enm('IN_REVIEW','FULFILLED','DENIED_WITH_REASON'),decisionCode=string),
 'GuardianCreate':obj(('guardianUserId','relationship','accessScope','evidence'),guardianUserId=uuid,relationship=string,accessScope={'type':'array','items':enm('SCHEDULE','BILLING')},evidence={'type':'string','minLength':8,'maxLength':2000}),
 'TokenAccept':obj(('token',),token={'type':'string','minLength':40,'maxLength':100}),
 'ApprovalCreate':obj(('gateCode','evidenceReference'),gateCode=enm('GATE-LEGAL-01','GATE-CLINICAL-01','GATE-FISCAL-01','GATE-PSP-01','GATE-VIDEO-01','GATE-BUSINESS-01'),evidenceReference={'type':'string','minLength':8,'maxLength':2000}),
})
schemas['FileInit']['properties']['patientId']=uuid
schemas['NewsletterSubscribe']['properties']['orgSlug']={'type':'string','pattern':'^[a-z0-9-]{3,40}$'}
schemas['NewsletterSubscribe']['required'].append('orgSlug')
schemas['RecurrenceCreate']['properties'].update(durationMinutes={'type':'integer','minimum':30,'maximum':180},modality=enm('REMOTE','IN_PERSON'),priceCents={'type':'integer','minimum':0,'maximum':2000000})
schemas['Invoice']['properties']['version']={'type':'integer'}
schemas['Patient']['properties']['version']={'type':'integer'}
schemas['Professional']['properties']['version']={'type':'integer'}
schemas['AppointmentCreate']['properties']['priceCents']['maximum']=2000000
schemas['OrganizationCreate']['properties']['ownerIsPsychologist']={'type':'boolean','default':False}
schemas['Organization']['properties'].update(version={'type':'integer'},roles={'type':'array','items':{'type':'string'}})
schemas['Patient']['properties'].update(fullName={'type':'string'},socialName={'type':'string'},email={'type':'string'},phone={'type':'string'},communicationPreference={'type':'string'})
schemas['CareAssignmentCreate']['properties']['handoffEvidence']={'type':'string','minLength':8,'maxLength':2000}
schemas['ContentCreate']['properties']['fileId']=uuid
schemas['Content']['properties'].update(version={'type':'integer'},bodySanitizedHtml={'type':'string'},slug={'type':'string'},fileAssetId=uuid)
schemas['PreferencePatch']['properties']['version']={'type':'integer'}
schemas['PreferencePatch']['properties']['noticeVersion']={'type':'string','minLength':4,'maxLength':100}
add('get','/v1/orgs/{orgId}/payers','Billing','Pagadores autorizados','BILLING_READ','Page',paged=True)
add('get','/v1/orgs/{orgId}/payments/{paymentId}','Billing','Estado e checkout da cobrança','BILLING_READ_SCOPED','PaymentCheckout')
add('get','/v1/orgs/{orgId}/booking-requests','Scheduling','Solicitações iniciais ainda sem prontuário','APPOINTMENT_WRITE','Page',paged=True)
add('post','/v1/orgs/{orgId}/booking-requests/{requestId}/actions/accept','Scheduling','Aceitar lead e criar paciente, vínculo e sessão atomicamente','APPOINTMENT_WRITE','Appointment',idem=True)
add('post','/v1/orgs/{orgId}/booking-requests/{requestId}/actions/reject','Scheduling','Rejeitar solicitação inicial','APPOINTMENT_WRITE','Ok',idem=True)
add('get','/v1/orgs/{orgId}/dashboard','Reporting','Resumo operacional privado','MEMBER','Page')
add('post','/v1/orgs/{orgId}/professionals/{professionalId}/review','Professionals','Revisão humana de CRP','OWNER_OR_ADMIN','Professional','ProfessionalReview',idem=True)
add('get','/v1/orgs/{orgId}/professionals/{professionalId}/availability','Scheduling','Disponibilidade local','STAFF','Page')
add('post','/v1/orgs/{orgId}/professionals/{professionalId}/availability','Scheduling','Definir regra de disponibilidade','OWNER_OR_ADMIN','Ok','AvailabilityCreate',idem=True)
add('post','/v1/orgs/{orgId}/professionals/{professionalId}/exceptions','Scheduling','Bloquear intervalo','OWNER_OR_ADMIN','Ok','AvailabilityException',idem=True)
add('get','/v1/orgs/{orgId}/professionals/{professionalId}/prices','Billing','Tabela de preços','STAFF','Page')
add('post','/v1/orgs/{orgId}/professionals/{professionalId}/prices','Billing','Definir preço com vigência','OWNER_OR_ADMIN','Ok','PriceCreate',idem=True)
add('post','/v1/orgs/{orgId}/patients/{patientId}/guardians','Patients','Vínculo de responsável com evidência','OWNER_OR_ADMIN','Ok','GuardianCreate',idem=True)
add('post','/v1/orgs/{orgId}/privacy-requests/{requestId}/review','Privacy','Revisar direito do titular sem expurgo automático','OWNER_OR_ADMIN','Ok','PrivacyDecision',idem=True)
add('get','/v1/orgs/{orgId}/integrations','Integrations','Conexões e estado sem segredos','OWNER_OR_ADMIN','Page')
add('post','/v1/orgs/{orgId}/integrations','Integrations','Registrar referência ao cofre','OWNER_OR_ADMIN','Ok','IntegrationCreate',idem=True)
add('post','/v1/orgs/{orgId}/integrations/{connectionId}/activate','Integrations','Ativar conexão homologada','OWNER_OR_ADMIN','Ok','ReviewDecision',idem=True)
add('get','/v1/orgs/{orgId}/document-templates','Documents','Modelos aprovados','STAFF','Page')
add('post','/v1/orgs/{orgId}/document-templates','Documents','Criar versão de modelo','CLINICIAN_ASSIGNED','Ok','TemplateCreate',idem=True)
add('post','/v1/orgs/{orgId}/document-templates/{templateId}/approve','Documents','Homologar modelo com evidência','OWNER_OR_ADMIN','Ok','ReviewDecision',idem=True)
add('patch','/v1/orgs/{orgId}/content/{contentId}','Content','Editar conteúdo não publicado','CONTENT_WRITE','Content','ContentCreate',etag=True)
add('post','/v1/orgs/{orgId}/content/{contentId}/actions/review','Content','Submeter conteúdo para revisão','CONTENT_WRITE','Content',idem=True)
add('post','/v1/orgs/{orgId}/content/{contentId}/actions/archive','Content','Arquivar conteúdo','CONTENT_WRITE','Content',idem=True)
add('get','/v1/orgs/{orgId}/audit','Audit','Trilha sem conteúdo clínico','OWNER_OR_ADMIN','Page',paged=True)
add('get','/v1/orgs/{orgId}/release-approvals','Integrations','Evidências de homologação','OWNER_OR_ADMIN','Page')
add('post','/v1/orgs/{orgId}/release-approvals','Integrations','Registrar aprovação humana','OWNER_OR_ADMIN','Ok','ApprovalCreate',idem=True)
add('post','/v1/orgs/{orgId}/invoices/{invoiceId}/actions/cancel','Billing','Cancelar fatura sem recebimentos','BILLING_WRITE','Invoice','AppointmentTransition',idem=True)
add('post','/v1/invitations/accept','Identity','Vincular convite ao email OIDC verificado','LOGIN','Ok','TokenAccept')
add('post','/v1/public/confirm','Scheduling','Confirmar com token de ação','PUBLIC','Ok','TokenAccept',mode='public')
add('post','/v1/public/newsletter/unsubscribe','Content','Descadastro em um clique','PUBLIC','Ok','TokenAccept',mode='public')

schemas['AppointmentPatch']=obj((),reasonCode={'type':'string','minLength':2,'maxLength':60},modality=enm('REMOTE','IN_PERSON'))
schemas['MemberPatch']=obj(('status','roles'),status=enm('ACTIVE','REVOKED'),roles={'type':'array','minItems':1,'uniqueItems':True,'items':enm('OWNER','ADMIN','PSYCHOLOGIST','SECRETARY','BILLING')})
schemas['PaymentCheckout']['required']=['paymentId','status']
schemas['PaymentCheckout']['properties']['checkoutUrl']={'type':['string','null'],'format':'uri'}
schemas['PaymentCheckout']['properties']['failureCode']={'type':['string','null']}
schemas['PaymentCheckout']['properties']['status']=enm('PENDING','AUTHORIZED','CONFIRMED','SETTLED','FAILED','CANCELLED','REFUNDED','CHARGEBACK')
add('get','/v1/orgs/{orgId}/portal/preferences','Portal','Preferências atuais do titular','PATIENT_LINKED','PreferencePatch')
add('patch','/v1/orgs/{orgId}/appointments/{appointmentId}','Scheduling','Atualizar detalhes sem alterar horário ocupado','APPOINTMENT_WRITE','Appointment','AppointmentPatch',etag=True)
add('patch','/v1/orgs/{orgId}/members/{memberId}','Identity','Alterar funções ou revogar vínculo de equipe','OWNER_OR_ADMIN','Ok','MemberPatch',etag=True)
add('post','/v1/public/newsletter/confirm','Content','Confirmar consentimento pelo endereço de email','PUBLIC','Ok','TokenAccept',mode='public')

schemas['FileInit']['properties']['classification']['enum'].append('RESTRICTED_TEST')
schemas['ClinicalFileCreate']=obj(('fileId',),fileId=uuid)
for suffix in ('clinical-files','restricted-test-materials'):
 add('get','/v1/orgs/{orgId}/patients/{patientId}/'+suffix,'ClinicalAssets','Anexos clínicos segregados por finalidade','CLINICIAN_ASSIGNED','Page',paged=True)
 add('post','/v1/orgs/{orgId}/patients/{patientId}/'+suffix,'ClinicalAssets','Vincular arquivo validado à finalidade clínica','CLINICIAN_ASSIGNED','Ok','ClinicalFileCreate',idem=True)

for path in ('/v1/orgs/{orgId}/invoices','/v1/orgs/{orgId}/financial-reports'):
 for name,schema in [('from',date),('to',date),('status',{'type':'string'}),('professionalId',uuid),('method',{'type':'string'})]:
  paths[path]['get'].setdefault('parameters',[]).append({'name':name,'in':'query','required':False,'schema':schema})

schemas['PatientPatch']['properties']['email']={'type':['string','null'],'format':'email'}

bounded=lambda n,minimum=1:{'type':'string','minLength':minimum,'maxLength':n}
schemas['ApprovalCreate']['properties']['gateCode']['enum'].append('GATE-SECURITY-01')
schemas['ApprovalCreate']['properties']['gateCode']['enum']+=['GATE-COMMUNICATION-01','GATE-SIGNATURE-01']
add('get','/v1/orgs/{orgId}/portal/patients','Patients','Pacientes vinculados ao titular ou responsável','PATIENT_LINKED','Page')
add('get','/v1/orgs/{orgId}/patients/{patientId}/guardians','Patients','Responsáveis e liberação clínica individual','CLINICIAN_OR_ADMIN','Page')
array=lambda items,n=20:{'type':'array','items':items,'maxItems':n,'uniqueItems':True}
schemas['Address']=obj(('street','city','state','postalCode'),street=bounded(200),number=bounded(30),complement=bounded(100),city=bounded(100),state={'type':'string','pattern':'^[A-Z]{2}$'},postalCode={'type':'string','pattern':'^[0-9]{8}$'})
schemas['EmergencyContact']=obj(('name','phone','clinicalIndication'),name=bounded(200),phone={'type':'string','pattern':'^\\+[1-9][0-9]{7,14}$'},relationship=bounded(100),clinicalIndication=bounded(500,8))
schemas['Registration']=obj(('responsibleName','responsibleTaxId','address'),responsibleName=bounded(200),responsibleTaxId={'type':'string','pattern':'^([0-9]{11}|[0-9]{14})$'},legalName=bounded(200),address=ref('Address'))
for name in ('OrganizationCreate','OrganizationPatch','Organization'):
 schemas[name]['properties']['registration']=ref('Registration')
for name in ('PatientCreate','PatientPatch','Patient'):
 schemas[name]['properties'].update(address={'anyOf':[ref('Address'),{'type':'null'}]},emergencyContact={'anyOf':[ref('EmergencyContact'),{'type':'null'}]})
schemas['PatientPatch']['properties']['birthDate']={'type':['string','null'],'format':'date'}
profile={'languages':array(bounded(60)),'specialties':array(bounded(120)),'appointmentTypes':array(enm('REMOTE','IN_PERSON'),2),'publicBio':bounded(2000,0),'publicProfileEnabled':{'type':'boolean'}}
schemas['ProfessionalCreate']['properties'].update(profile)
schemas['ProfessionalPatch']=obj((),**profile)
schemas['Professional']['properties'].update(profile)
schemas['Professional']['properties']['reviewedSpecialties']=array(bounded(120))
schemas['ProfessionalReview']['properties']['reviewedSpecialties']=array(bounded(120))
schemas['OpeningHours']=obj(('weekday','start','end'),weekday={'type':'integer','minimum':1,'maximum':7},start={'type':'string','pattern':'^([01][0-9]|2[0-3]):[0-5][0-9]$'},end={'type':'string','pattern':'^([01][0-9]|2[0-3]):[0-5][0-9]$'})
schemas['SettingsPatch']['properties'].update(maxAppointmentsPerDay={'type':'integer','minimum':1,'maximum':100},openingHours=array(ref('OpeningHours'),30),holidays=array(date,366),policyVersion=bounded(60),cancellationPolicy=bounded(4000,0),privacyNoticeVersion=bounded(60),contingencyChannel=bounded(500),communicationDailyLimit={'type':'integer','minimum':1,'maximum':10000})
schemas['RemoteProtocolCreate']=obj(('interruptionPlan','acuteRiskPlan','privacyGuidance','privacyAcknowledged'),interruptionPlan=bounded(4000,8),acuteRiskPlan=bounded(4000,8),privacyGuidance=bounded(4000,8),privacyAcknowledged='boolean',currentLocation=bounded(500),emergencyContact=ref('EmergencyContact'),clinicalIndication=bounded(500,8))
schemas['RemoteContingency']=obj((),interruptionPlan='string',privacyGuidance='string',acuteRiskPlan='string',currentLocation='string',emergencyContact=ref('EmergencyContact'),contingencyChannel='string')
add('get','/v1/orgs/{orgId}/appointments/{appointmentId}/remote-contingency','Video','Combinados disponíveis durante falha de vídeo','VIDEO_PARTICIPANT','RemoteContingency')
schemas['WorkflowReview']=obj(('decision','evidence'),decision=enm('APPROVE','REJECT'),evidence=bounded(2000,8))
schemas['GuardianClinicalReview']=obj(('evidence','expiresAt'),evidence=bounded(2000,8),expiresAt=instant)
schemas['RecordExportCreate']=obj(('noteIds','reviewedSummary'),noteIds=array(uuid,500),reviewedSummary=bounded(50000,8))
schemas['RecordExportRelease']=obj(('evidence','expiresAt'),evidence=bounded(2000,8),expiresAt=instant)
schemas['BreakGlassCreate']=obj(('patientId','reason','scope','durationMinutes'),patientId=uuid,reason=bounded(2000,8),scope=enm('SIGNED_NOTES_READ'),durationMinutes={'type':'integer','minimum':1,'maximum':15})
schemas['BreakGlassApproval']=obj(('reviewRole','evidence'),reviewRole=enm('SECURITY','CLINICAL'),evidence=bounded(2000,8))
schemas['SubscriptionCreate']=obj(('planCode','termsVersion','customer'),planCode=bounded(60),termsVersion=bounded(60),customer=obj(('name','email','cpfCnpj'),name=bounded(200),email={'type':'string','format':'email'},cpfCnpj={'type':'string','pattern':'^([0-9]{11}|[0-9]{14})$'}))
schemas['SignatureVerify']=obj(('fileId',),fileId=uuid)
schemas['FileValidation']=obj(('id','version','status'),id=uuid,version='integer',status=enm('READY','REJECTED'),failureCode='string')
paths['/v1/orgs/{orgId}/files/{fileId}/complete']['post']['responses']['201']['content']['application/json']['schema']=ref('FileValidation')
add('patch','/v1/orgs/{orgId}/professionals/{professionalId}','Professionals','Atualizar perfil e reiniciar revisão de especialidades','OWNER_OR_ADMIN','Professional','ProfessionalPatch',etag=True)
add('get','/v1/orgs/{orgId}/settings/history','OrganizationHistory','Histórico de políticas e canais','OWNER_OR_ADMIN','Page',paged=True)
for method in ('get','post'):
 add(method,'/v1/orgs/{orgId}/patients/{patientId}/remote-protocols','CareWorkflows','Protocolo remoto protegido e versionado','SUBJECT_OR_CLINICIAN','Page' if method=='get' else 'Ok','RemoteProtocolCreate' if method=='post' else None,idem=method=='post',paged=method=='get')
add('post','/v1/orgs/{orgId}/patients/{patientId}/remote-protocols/{protocolId}/review','CareWorkflows','Revisar protocolo pelo profissional vinculado','CLINICIAN_ASSIGNED','Ok','WorkflowReview',idem=True)
for stage in ('legal-review','clinical-review'):
 add('post','/v1/orgs/{orgId}/patients/{patientId}/guardians/{guardianId}/'+stage,'CareWorkflows','Revisão individual de liberação clínica ao responsável','OWNER_OR_ADMIN' if stage=='legal-review' else 'CLINICIAN_ASSIGNED','Ok','GuardianClinicalReview',idem=True)
add('post','/v1/orgs/{orgId}/patients/{patientId}/guardians/{guardianId}/revoke-clinical','CareWorkflows','Revogar liberação clínica do responsável','OWNER_OR_ADMIN','Ok',idem=True)
add('get','/v1/orgs/{orgId}/privacy-requests','CareWorkflows','Fila de direitos do titular e prazo operacional','CLINICIAN_OR_ADMIN','Page',paged=True)
add('post','/v1/orgs/{orgId}/privacy-requests/{requestId}/prepare-export','CareWorkflows','Gerar pacote revisável sem material de teste','CLINICIAN_ASSIGNED','Ok','RecordExportCreate',idem=True)
add('get','/v1/orgs/{orgId}/record-exports','CareWorkflows','Pacotes para revisão ou entrega ao destinatário','SUBJECT_OR_CLINICIAN','Page',paged=True)
add('post','/v1/orgs/{orgId}/record-exports/{exportId}/release','CareWorkflows','Revisar e liberar pacote ao titular autenticado','CLINICIAN_ASSIGNED','Ok','RecordExportRelease',idem=True)
add('post','/v1/orgs/{orgId}/record-exports/{exportId}/preview','CareWorkflows','Conferir pacote preparado antes da liberação','CLINICIAN_ASSIGNED','FileDownload',idem=True)
add('post','/v1/orgs/{orgId}/record-exports/{exportId}/download','CareWorkflows','Entrega autenticada com ticket de 60 segundos','SUBJECT_OR_CLINICIAN','FileDownload',code=200)
add('post','/v1/orgs/{orgId}/record-exports/{exportId}/revoke','CareWorkflows','Revogar pacote liberado','CLINICIAN_ASSIGNED','Ok',idem=True)
for method in ('get','post'):
 add(method,'/v1/orgs/{orgId}/break-glass','EmergencyAccess','Acesso emergencial interno com dupla aprovação','EMERGENCY_STAFF','Page' if method=='get' else 'Ok','BreakGlassCreate' if method=='post' else None,idem=method=='post',paged=method=='get')
add('post','/v1/orgs/{orgId}/break-glass/{accessId}/approve','EmergencyAccess','Aprovação independente de segurança ou clínica','EMERGENCY_STAFF','Ok','BreakGlassApproval',idem=True)
for action in ('revoke','deny'):
 add('post','/v1/orgs/{orgId}/break-glass/{accessId}/'+action,'EmergencyAccess','Encerrar acesso emergencial','EMERGENCY_STAFF','Ok',idem=True)
add('get','/v1/orgs/{orgId}/break-glass/{accessId}/notes','EmergencyAccess','Leitura mínima de notas assinadas durante autorização','EMERGENCY_STAFF','Page',paged=True)
add('post','/v1/public/blog/{orgSlug}/{contentId}/read','ContentMetrics','Contar leitura sem identificador de visitante','PUBLIC','WebhookAck',mode='public',code=200)
add('get','/v1/orgs/{orgId}/content-metrics','ContentMetrics','Contagem diária agregada sem perfis','CONTENT_WRITE','Page',paged=True)
for method in ('get','post'):
 add(method,'/v1/orgs/{orgId}/platform-subscription','Subscriptions','Assinatura da plataforma em conta separada','OWNER_OR_ADMIN','Page' if method=='get' else 'Ok','SubscriptionCreate' if method=='post' else None,idem=method=='post')
for action in ('reconcile','cancel'):
 add('post','/v1/orgs/{orgId}/platform-subscription/'+action,'Subscriptions','Conciliar ou cancelar mensalidade SaaS','OWNER_OR_ADMIN','Ok',idem=True)
add('get','/v1/orgs/{orgId}/platform-subscription/plans','Subscriptions','Planos homologados da plataforma','OWNER_OR_ADMIN','Page')
add('post','/v1/orgs/{orgId}/documents/{documentId}/verify-signature','Documents','Verificar CMS PAdES, cadeia e revogação','DOCUMENT_AUTHOR','Page','SignatureVerify',idem=True)
add('get','/v1/orgs/{orgId}/communications/deliveries','Delivery','Estados e falhas das mensagens','OWNER_OR_ADMIN','Page',paged=True)
add('post','/v1/orgs/{orgId}/communications/{notificationId}/retry','Delivery','Reprocessar falha confirmada com auditoria','OWNER_OR_ADMIN','Ok','WorkflowReview',idem=True)
add('get','/v1/orgs/{orgId}/communications/dlq','Delivery','Fila de jobs de comunicação em DLQ sem payload','OWNER_OR_ADMIN','Page',paged=True)
add('post','/v1/orgs/{orgId}/communications/jobs/{jobId}/retry','Delivery','Reprocessar job de comunicação após revisão','OWNER_OR_ADMIN','Ok','WorkflowReview',idem=True)
add('post','/v1/webhooks/communications/{connectionId}','DeliveryWebhook','Callback assinado de email ou WhatsApp','PUBLIC','WebhookAck',mode='public',code=200)

openapi={'openapi':'3.1.0','info':{'title':'Plataforma Psicologia Clínica API','version':'2.2.0','description':'Contrato baseline de implementação: respostas versionadas, RBAC/ABAC e idempotência. OpenAPI exige geração de testes e SDK; schemas opcionais não anulam regras documentadas em SPECIFICACAO_TECNICA.md.'},
 'servers':[{'url':'https://api.example.invalid','description':'Configurar domínio real por ambiente'}],
 'security':[{'BffSession':[]}],
 'tags':[{'name':n} for n in sorted({k for path in paths.values() for op in path.values() for k in op['tags']})],
 'paths':paths,
 'components':{'securitySchemes':{'BffSession':{'type':'apiKey','in':'cookie','name':'__Host-psico-session','description':'Cookie de sessão BFF; frontend não armazena bearer em localStorage.'},'AsaasWebhookSecret':{'type':'apiKey','in':'header','name':'asaas-access-token','description':'Exemplo de chave; confirmar header/token no contrato oficial Asaas antes do deploy.'}},
               'responses':{'Problem':{'description':'Erro padronizado RFC 9457','content':{'application/problem+json':{'schema':ref('Problem')}}}},
               'schemas':schemas}}
with open(ROOT/'openapi.yaml','w',encoding='utf-8') as f:
 yaml.safe_dump(openapi,f,sort_keys=False,allow_unicode=True,width=115)
print('wrote paths=',len(paths),'operations=',sum(len(p) for p in paths.values()),'schemas=',len(schemas))
