"""Offline schemas, semantic negative cases and real loopback HTTP checks."""
import copy,hashlib,json,threading,unittest,urllib.request,urllib.error
from contract_tools import *
from fake_rd import server
from jsonschema.exceptions import ValidationError
class ContractChecks(unittest.TestCase):
 def test_external_lock(self):
  contract=load('external/04-openapi-v1.1.0.json')
  self.assertEqual(contract['info']['version'],'1.1.0');self.assertEqual(len(contract['paths']),13);self.assertEqual(len(contract['components']['schemas']),75)
  # Canonical RD hash has its own rule; unlike business input it can contain floats.
  encoded=json.dumps(contract,ensure_ascii=False,sort_keys=True,separators=(',',':')).encode()
  self.assertEqual(hashlib.sha256(encoded).hexdigest(),'25c840aed0f2eb637ff589caf34b5901efee4b7646ef4a6ad7a10b95d61625a4')
 def test_external_samples(self):
  for file,schema in [('jd-request','DirectJDRequestDTO'),('jd-response','DirectJDGenerateResponseDTO'),('resume-response','DirectResumeAnalyzeResponseDTO'),('match-response','DirectResumeMatchResponseDTO'),('ready-response','ReadinessResponseDTO'),('error-response','ErrorEnvelopeDTO')]:
   with self.subTest(file=file):validate_external(schema,load('fixtures/'+file+'.json'))
 def test_internal_schemas(self):
  for schema in INTERNAL['$defs'].values():Draft202012Validator.check_schema({'$defs':INTERNAL['$defs'],**schema})
  for file,schema in [('grant-complex','grant'),('grant-simple','grant'),('execution-request','execution_request'),('unified-jd-result','result'),('unified-resume-result','result'),('unified-match-result','result')]:validate_internal(schema,load('fixtures/'+file+'.json'))
 def test_binding_and_hash(self):
  req=load('fixtures/execution-request.json');g=load('fixtures/grant-complex.json');validate_binding(req,g)
  v=load('fixtures/hash-vector.json');self.assertEqual(input_hash(v['material']),v['sha256']);self.assertEqual(canonical(v['material']).decode(),v['canonical_utf8'])
  self.assertEqual(input_hash(dict(reversed(list(v['material'].items())))),v['sha256'])
  changed=copy.deepcopy(req);changed['input']['slots']['job_title']['value']='changed'
  with self.assertRaises(ValueError):validate_binding(changed,g)
  with self.assertRaises(ValueError):canonical({'decimal':1.5})
 def test_agent_constraints(self):
  for file,remove in [('grant-complex','external_api_constraints'),('grant-simple','model_constraints')]:
   g=load('fixtures/'+file+'.json');g.pop(remove)
   with self.assertRaises(ValidationError):validate_internal('grant',g)
  g=load('fixtures/grant-complex.json');g.update(capability='JD_IN_PLACE_REVISION',operation='revise')
  with self.assertRaises(ValidationError):validate_internal('grant',g)
  g=load('fixtures/grant-complex.json');g['model_constraints']=load('fixtures/grant-simple.json')['model_constraints']
  with self.assertRaises(ValidationError):validate_internal('grant',g)
 def test_input_mismatch(self):
  for path in ['agent_id','operation','input_hash','authorization_id']:
   req=load('fixtures/execution-request.json');req[path]='0'*64 if path=='input_hash' else '00000000-0000-4000-8000-000000000002' if path=='authorization_id' else 'invalid'
   with self.assertRaises((ValueError,ValidationError)):validate_binding(req,load('fixtures/grant-complex.json'))
 def test_generated_job_is_not_match_input(self):
  value=load('fixtures/jd-response.json')['business_result']['structured_job']
  with self.assertRaises(ValidationError):validate_external('DirectStructuredJobDTO',value)
 def test_nullable_usage_and_resume(self):
  for f,schema in [('jd-response','DirectJDGenerateResponseDTO'),('resume-response','DirectResumeAnalyzeResponseDTO'),('match-response','DirectResumeMatchResponseDTO')]:
   value=load('fixtures/'+f+'.json');value['execution']['usage']=None;validate_external(schema,value)
  value=load('fixtures/resume-response.json');value['business_result']['structured_resume']['data']['experience_summary']['total_years']=None;validate_external('DirectResumeAnalyzeResponseDTO',value)
 def test_states(self):
  states=load('contracts/states.json');self.assertEqual(sum(x['terminal'] for x in states),3)
  task={'ai_task_id':'00000000-0000-4000-8000-000000000001','attempt_id':'00000000-0000-4000-8000-000000000001','authorization_id':'00000000-0000-4000-8000-000000000001','tenant_id':'00000000-0000-4000-8000-000000000001','actor_id':'00000000-0000-4000-8000-000000000001','agent_id':'complex_recruitment_agent','capability':'jd_generation','operation':'create','input_hash':'1'*64,'status':'queued','state_version':1,'cancellation_requested':False,'result_reference':None,'error_code':None}
  for state in states:
   task['status']=state['http'];validate_event({'event_id':task['ai_task_id'],'event_type':state['event_types'][0],'occurred_at':'2026-10-07T14:00:00Z','task':task})
  task['status']='unknown'
  with self.assertRaises(ValidationError):validate_internal('task',task)
 def test_unscored_results(self):
  value=load('fixtures/unified-match-result.json');validate_result(value)
  item=value['data']['candidates'][0];item.update(evaluation_status='HARD_FILTERED',score=None,level=None);validate_result(value)
  item.update(evaluation_status='NOT_EVALUATED');validate_result(value)
  item.update(evaluation_status='RECONCILIATION_REQUIRED',result_validity='UNVERIFIED_RESULT',billable_unit_count=None)
  with self.assertRaises(ValueError):validate_result(value)
  value['status']='reconciliation_required';validate_result(value)
  item['billable_unit_count']=0
  with self.assertRaises(ValueError):validate_result(value)
  item['billing_reason_code']='RECONCILIATION_DEADLINE_EXPIRED';item['evaluation_status']='FAILED';value['status']='failed';validate_result(value)
 def test_semantic_conflicts(self):
  req=load('fixtures/execution-request.json');req['policy_decision']['actor_id']='00000000-0000-4000-8000-000000000002'
  with self.assertRaises(ValueError):validate_binding(req,load('fixtures/grant-complex.json'))
  value=load('fixtures/unified-match-result.json');value['data']['candidates'][0]['level']='STRONG_MATCH'
  with self.assertRaises(ValueError):validate_result(value)
 def test_settlement(self):
  g=load('fixtures/grant-complex.json');keys=['tenant_id','actor_id','task_id','attempt_id','product_domain','capability','operation','agent_id','idempotency_key','route_config_version','input_hash','authorization_id','reservation_id']
  decision={**{k:g[k] for k in keys},'accepted_task_id':g['attempt_id'],'decision_version':1,'decision_fingerprint':'1'*64,'final_decision':'CAPTURE','executed_unit_count':1,'billing_reason_code':'VALID_RESULT','evidence_reference':'encrypted://synthetic/evidence','result_reference':'result-001','task_status':'completed','retry_count':0,'usage':None,'unit_decisions':[{'unit_id':'unit-001','result_validity':'VALID_BUSINESS_RESULT','billable_unit_count':1,'billing_reason_code':'VALID_RESULT','evidence_reference':'encrypted://synthetic/evidence'}]}
  validate_settlement(decision,g)
  changed=copy.deepcopy(decision);changed['unit_decisions'][0]['result_validity']='UNVERIFIED_RESULT'
  with self.assertRaises(ValueError):validate_settlement(changed,g)
  changed['unit_decisions'][0].update(billable_unit_count=0,billing_reason_code='RECONCILIATION_DEADLINE_EXPIRED');changed.update(executed_unit_count=0,final_decision='RELEASE');validate_settlement(changed,g)
class HttpChecks(unittest.TestCase):
 @classmethod
 def setUpClass(cls):
  cls.srv=server();cls.thread=threading.Thread(target=cls.srv.serve_forever,daemon=True);cls.thread.start();cls.base=f'http://127.0.0.1:{cls.srv.server_port}'
 @classmethod
 def tearDownClass(cls):cls.srv.shutdown();cls.srv.server_close();cls.thread.join()
 def call(self,path,body=None,ctype='application/json',key='phase0-fake-key'):
  req=urllib.request.Request(self.base+path,data=body,headers={'Authorization':'Bearer '+key,'Content-Type':ctype})
  try:
   with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req,timeout=5) as response:return response.status,json.load(response)
  except urllib.error.HTTPError as error:
   with error:return error.code,json.load(error)
 def multipart(self,count=1,match=False,field=None,text='Final confirmed synthetic JD'):
  boundary='rd001syntheticboundary';parts=[]
  if match:parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="job_text"\r\n\r\n{text}\r\n'.encode())
  for i in range(count):parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{field or ("resumes" if match else "file")}"; filename="synthetic-{i}.pdf"\r\nContent-Type: application/pdf\r\n\r\n%PDF-1.4 synthetic fixture\r\n'.encode())
  parts.append(f'--{boundary}--\r\n'.encode());return b''.join(parts),'multipart/form-data; boundary='+boundary
 def test_ready_and_auth(self):
  status,data=self.call('/api/v1/ready');self.assertEqual(status,200);validate_external('ReadinessResponseDTO',data)
  status,data=self.call('/api/v1/ready',key='wrong');self.assertEqual(status,401);validate_external('ErrorEnvelopeDTO',data)
 def test_jd(self):
  status,data=self.call('/api/v1/recruitment/jd/generate',json.dumps(load('fixtures/jd-request.json')).encode());self.assertEqual(status,200);validate_external('DirectJDGenerateResponseDTO',data)
  status,_=self.call('/api/v1/recruitment/jd/generate',b'{}');self.assertEqual(status,422)
 def test_resume(self):
  body,ctype=self.multipart();status,data=self.call('/api/v1/recruitment/resume/analyze',body,ctype);self.assertEqual(status,200);validate_external('DirectResumeAnalyzeResponseDTO',data)
  body,ctype=self.multipart(field='resumes');status,_=self.call('/api/v1/recruitment/resume/analyze',body,ctype);self.assertEqual(status,422)
 def test_match(self):
  for count in [1,5]:
   body,ctype=self.multipart(count,True);status,data=self.call('/api/v1/recruitment/resume/match',body,ctype);self.assertEqual(status,200);validate_external('DirectResumeMatchResponseDTO',data);self.assertEqual(data['business_result']['input_count'],count)
  for count in [0,6]:
   body,ctype=self.multipart(count,True);status,_=self.call('/api/v1/recruitment/resume/match',body,ctype);self.assertEqual(status,422)
  body,ctype=self.multipart(1,True,text='');status,_=self.call('/api/v1/recruitment/resume/match',body,ctype);self.assertEqual(status,422)
if __name__=='__main__':unittest.main(verbosity=2)
