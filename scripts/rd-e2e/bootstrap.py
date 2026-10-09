"""Starts isolated Java instances against rd_*_fresh databases and synthetic providers."""
import hashlib,json,os,subprocess,time,urllib.request
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3];OUT=Path('/tmp/rd-e2e');OUT.mkdir(exist_ok=True)
USER='11111111-1111-4111-8111-111111111111';TENANT='22222222-2222-4222-8222-222222222222';ADMIN='33333333-3333-4333-8333-333333333333';VIEWER='44444444-4444-4444-8444-444444444444'
USER_TOKEN='rd-fixture-user-token';ADMIN_TOKEN='rd-fixture-admin-token';VIEW_TOKEN='rd-fixture-view-token';SERVICE_TOKEN='rd-fixture-ir-service-token';CONTROL_TOKEN='rd-fixture-release-token';CLIENT='rd-fixture-client';SECRET='rd-fixture-client-secret'
def sql(container,user,db,statement):
 return subprocess.check_output(['docker','exec','-i',container,'psql','-U',user,'-d',db,'-v','ON_ERROR_STOP=1','-At'],input=statement.encode()).decode().strip()
def request(method,url,data=None,token=None,headers=None):
 h={'Content-Type':'application/json',**(headers or {})}
 if token:h['Authorization']='Bearer '+token
 req=urllib.request.Request(url,data=json.dumps(data).encode() if data is not None else None,headers=h,method=method)
 with urllib.request.urlopen(req,timeout=20) as response:
  raw=response.read();return json.loads(raw) if raw else None
def start(name,cmd,env):
 log=open(OUT/(name+'.log'),'w');process=subprocess.Popen(cmd,cwd=ROOT,env={**os.environ,**env},stdout=log,stderr=log,start_new_session=True);PIDS[name]=process.pid;log.close();return process
PIDS={}
def wait(port):
 for _ in range(120):
  try:request('GET',f'http://127.0.0.1:{port}/actuator/health');return
  except Exception:time.sleep(.5)
 raise RuntimeError('Health failed on '+str(port))
def main():
 from storage import prepare,ENDPOINT,ACCESS,SECRET as STORAGE_SECRET
 prepare()
 import signal
 previous=OUT/'processes.json'
 if previous.exists():
  for pid in json.loads(previous.read_text()).values():
   try:os.killpg(pid,signal.SIGTERM)
   except ProcessLookupError:pass
  time.sleep(2)
 hashes=[hashlib.sha256(t.encode()).hexdigest() for t in [USER_TOKEN,ADMIN_TOKEN,VIEW_TOKEN]]
 seed=f"""
 INSERT INTO users(id,phone_hash,phone_last_four,display_name) VALUES('{USER}',repeat('1',64),'0001','RD Synthetic User');
 INSERT INTO tenants(id,product_domain,tenant_type,name,status) VALUES('{TENANT}','RECRUITMENT','PERSONAL','RD Synthetic Tenant','ACTIVE');
 INSERT INTO tenant_ownership(tenant_id,owner_user_id) VALUES('{TENANT}','{USER}');
 INSERT INTO tenant_memberships(id,tenant_id,user_id,role_code,status,join_method) VALUES(gen_random_uuid(),'{TENANT}','{USER}','OWNER','ACTIVE','REGISTRATION');
 INSERT INTO tenant_feature_settings(tenant_id) VALUES('{TENANT}');
 INSERT INTO tenant_subscriptions(id,tenant_id,kind,entitlement_snapshot,seat_capacity,credit_amount,starts_at,ends_at,status) VALUES(gen_random_uuid(),'{TENANT}','PLAN','{{"AI_RECRUITMENT_USE":true,"JOB_LIBRARY_EDIT":true,"JOB_LIBRARY_VIEW":true,"TALENT_LIBRARY_EDIT":true,"TALENT_LIBRARY_VIEW":true}}',1,10000,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP+INTERVAL '1 day','ACTIVE');
 INSERT INTO credit_lots(id,tenant_id,source_type,original_amount,available_amount,granted_at,expires_at,status) VALUES(gen_random_uuid(),'{TENANT}','ADJUSTMENT',10000,10000,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP+INTERVAL '1 day','ACTIVE');
 INSERT INTO access_tokens(id,user_id,token_hash,expires_at) VALUES(gen_random_uuid(),'{USER}','{hashes[0]}',CURRENT_TIMESTAMP+INTERVAL '1 day');
 INSERT INTO platform_admins(id,display_name,role,status,username) VALUES('{ADMIN}','Synthetic Admin','SUPER_ADMIN','ACTIVE','rd-synthetic-admin'),('{VIEWER}','Synthetic Viewer','OPERATOR','ACTIVE','rd-synthetic-viewer');
 INSERT INTO platform_admin_tokens(id,admin_id,token_hash,expires_at) VALUES(gen_random_uuid(),'{ADMIN}','{hashes[1]}',CURRENT_TIMESTAMP+INTERVAL '1 day'),(gen_random_uuid(),'{VIEWER}','{hashes[2]}',CURRENT_TIMESTAMP+INTERVAL '1 day');
 INSERT INTO platform_admin_role_permissions(id,admin_id,permission_code) VALUES(gen_random_uuid(),'{VIEWER}','ADMIN_PRODUCT_VIEW'),(gen_random_uuid(),'{VIEWER}','ADMIN_SCREENING_RULE_VIEW');
 """
 if sql('infra-postgres-1','boss','rd_boss_fresh',f"SELECT count(*) FROM users WHERE id='{USER}';")=='0':sql('infra-postgres-1','boss','rd_boss_fresh',seed)
 subprocess.run(['docker','exec','intelligent-recruitment-rabbitmq-1','rabbitmqctl','add_vhost','/rd-validation'],check=True,stdout=subprocess.DEVNULL)
 subprocess.run(['docker','exec','intelligent-recruitment-rabbitmq-1','rabbitmqctl','set_permissions','-p','/rd-validation','recruitment','.*','.*','.*'],check=True,stdout=subprocess.DEVNULL)
 start('fake',['python3',str(Path(__file__).parent/'fake_providers.py')],{})
 common={'BOSS_INTERNAL_CLIENT_ID':CLIENT,'BOSS_INTERNAL_CLIENT_SECRET':SECRET,'AI_AGENT_IR_SERVICE_TOKEN':SERVICE_TOKEN,'SPRING_PROFILES_ACTIVE':'local','WEBHOOK_SIGNING_SECRET':'rd-fixture-webhook','PII_ENCRYPTION_KEY':'rd-fixture-pii'}
 start('boss',['java','-jar',str(ROOT/'BOSS/target/boss-service-0.1.0-SNAPSHOT.jar')],{**common,'SERVER_PORT':'18091','BOSS_DATABASE_URL':'jdbc:postgresql://localhost:55432/rd_boss_fresh','BOSS_AI_GRANT_ALLOW_EPHEMERAL_KEY':'true','RECRUITMENT_SAAS_BASE_URL':'http://127.0.0.1:18092'})
 wait(18091)
 machine=request('POST','http://127.0.0.1:18091/internal/v1/oauth/token',{'client_id':CLIENT,'client_secret':SECRET})['access_token']
 jwks=request('GET','http://127.0.0.1:18091/internal/ai/grants/jwks',token=machine)
 for capability,operation in [('JD_GENERATION','create'),('RESUME_PARSING','analyze'),('CANDIDATE_SCREENING','match')]:
  for agent in ['complex_recruitment_agent','simple_recruitment_agent']:
   simple=agent.startswith('simple');body={'capability_code':capability,'permission_code':'AI_RECRUITMENT_USE','entitlement_feature_code':'AI_RECRUITMENT_USE','reservation_credits':7 if not simple else 100,'agent_id':agent,'operation':operation,'billing_method':'FIXED_PER_EXECUTION' if not simple else 'TOKEN','fixed_credits_per_unit':7 if not simple else None,'model_id':'deepseek-v4-flash' if simple else None,'tokenizer_id':'deepseek-v4-flash' if simple else None,'max_input_tokens':20000 if simple else None,'max_output_tokens':4000 if simple else None,'input_tokens_per_credit':1000 if simple else None,'output_tokens_per_credit':1000 if simple else None}
   request('POST','http://127.0.0.1:18091/api/v1/platform/recruitment/ai-feature-rules',body,ADMIN_TOKEN)
 start('aep',['java','-jar',str(ROOT/'AIAgentPlatform/target/ai-agent-platform-0.1.0-SNAPSHOT.jar'),'--spring.rabbitmq.virtual-host=/rd-validation','--spring.data.redis.database=15'],{**common,'SERVER_PORT':'18093','DB_URL':'jdbc:postgresql://localhost:5432/rd_aep_fresh','DEEPSEEK_API_KEY':'phase0-fake-key','DEEPSEEK_BASE_URL':'http://127.0.0.1:18081','AI_AGENT_BOSS_GRANT_JWKS_JSON':json.dumps(jwks),'AI_AGENT_TASK_ENCRYPTION_KEY':'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=','AI_AGENT_REQUIRE_SECRETS':'true','RECRUITMENT_DISTRIBUTION_BASE_URL':'http://127.0.0.1:18081','RECRUITMENT_DISTRIBUTION_SERVICE_API_KEY':'phase0-fake-key','IR_BASE_URL':'http://127.0.0.1:18092'})
 start('ir',['java','-jar',str(ROOT/'IntelligentRecruitment/services/recruitment-service/target/recruitment-service-0.1.0-SNAPSHOT.jar'),'--spring.rabbitmq.virtual-host=/rd-validation','--spring.data.redis.database=15'],{**common,'SERVER_PORT':'18092','DATABASE_URL':'jdbc:postgresql://localhost:5432/rd_ir_fresh','BOSS_BASE_URL':'http://127.0.0.1:18091','AI_AGENT_PLATFORM_URL':'http://127.0.0.1:18093','RECRUITMENT_COMPLEX_AGENT_ENABLED':'true','RECRUITMENT_ROUTE_CONFIG_VERSION':'rd-e2e-v1','S3_BUCKET':'rd-validation','S3_ENDPOINT':ENDPOINT,'S3_ACCESS_KEY':ACCESS,'S3_SECRET_KEY':STORAGE_SECRET,'RECRUITMENT_RELEASE_CONTROL_TOKEN':CONTROL_TOKEN,'IR_RELEASE_SHA':subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT/'IntelligentRecruitment').decode().strip()})
 wait(18093);wait(18092)
 matrix={repo:subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT/repo).decode().strip() for repo in ['BOSS','AIAgentPlatform','IntelligentRecruitment']}
 import uuid
 cutover=str(uuid.uuid4());change={'state':'DRAINING','cutover_id':cutover,'route_config_version':'rd-e2e-v1','protocol_upgrade':True,'version_matrix':matrix}
 request('POST','http://127.0.0.1:18092/internal/recruitment/maintenance',change,CONTROL_TOKEN)
 change['state']='VALIDATING';request('POST','http://127.0.0.1:18092/internal/recruitment/maintenance',change,CONTROL_TOKEN)
 validation=request('POST','http://127.0.0.1:18092/internal/recruitment/maintenance/validation-runs',{'cutover_id':cutover,'tenant_id':TENANT,'actor_id':USER,'input':{'title':'Synthetic Engineer','companyName':'Synthetic Co','requirement':'Build reliable Python services'}},CONTROL_TOKEN)['id']
 for _ in range(120):
  ready=sql('intelligent-recruitment-postgres-1','recruitment','rd_ir_fresh',f"SELECT count(*) FROM recruitment_release_validation_runs v JOIN ai_execution_records e ON e.business_task_id=v.id::text WHERE v.id='{validation}' AND v.status='COMPLETED' AND e.status='SETTLED';")
  if ready=='1':break
  time.sleep(.5)
 else:raise RuntimeError('Controlled validation did not settle')
 change['state']='OPEN';request('POST','http://127.0.0.1:18092/internal/recruitment/maintenance',change,CONTROL_TOKEN)
 (OUT/'state.json').write_text(json.dumps({'pids':PIDS,'tenant':TENANT,'actor':USER,'bucket':'rd-validation'}))
 print('Isolated BOSS, IR, AIAgentPlatform and Fake providers are healthy.',flush=True)
if __name__=='__main__':
 try:main()
 finally:(OUT/'processes.json').write_text(json.dumps(PIDS))
