"""Runs P0 deterministic result/settlement and unknown-outcome cases against isolated HTTP services."""
import json,time,urllib.request
from pathlib import Path
from bootstrap import request,sql,TENANT,USER_TOKEN
BASE='http://127.0.0.1:18092/api/v1/tenants/'+TENANT
PROOF=json.loads(Path('/tmp/rd-e2e/smoke-proof.json').read_text())
def api(method,path,data=None,extra=None):return request(method,BASE+path,data,USER_TOKEN,extra)
def set_mode(mode):request('POST','http://127.0.0.1:18081/__control',{'mode':mode})
def stats():return request('GET','http://127.0.0.1:18081/__stats')
def wait_run(run,expected,timeout=45):
 end=time.monotonic()+timeout;last=None
 while time.monotonic()<end:
  last=api('GET','/screening-runs/'+run)
  state=last['items'][0]['status']
  if state==expected:return last
  time.sleep(.25)
 raise AssertionError(f'{run} expected {expected}, got {json.dumps(last,ensure_ascii=False)[:500]}')
def execute(mode,expected,decision,units):
 before=stats().get('/api/v1/recruitment/resume/match',0);set_mode(mode)
 run=api('POST','/screening-runs',{'planId':PROOF['plan'],'candidateIds':[PROOF['candidate']]},extra={'Idempotency-Key':'rd-fixture-'+mode+'-'+str(time.time_ns())})
 value=wait_run(run['id'],expected)
 if mode=='unknown':time.sleep(2)
 item=value['items'][0]
 assert stats().get('/api/v1/recruitment/resume/match',0)-before==1, (mode,stats())
 end=time.monotonic()+30;row=None
 while time.monotonic()<end:
  if decision is None:
   row=sql('intelligent-recruitment-postgres-1','recruitment','rd_ir_fresh',f"SELECT reservation_id::text||':'||coalesce(final_decision,'NULL')||':'||status FROM ai_execution_records WHERE business_task_id='{item['id']}' ORDER BY created_at DESC LIMIT 1;")
   reservation=row.split(':',1)[0] if row else ''
   boss_status=sql('infra-postgres-1','boss','rd_boss_fresh',f"SELECT status||':'||coalesce(final_decision,'NULL') FROM credit_reservations WHERE id='{reservation}';") if reservation else ''
   if row.endswith(':NULL:RECONCILIATION_REQUIRED') and boss_status=='RECONCILIATION_HOLD:NULL':break
  else:
   row=sql('intelligent-recruitment-postgres-1','recruitment','rd_ir_fresh',f"SELECT coalesce(final_decision,'NULL')||':'||status||':'||coalesce(billable_unit_count,-1)::text FROM ai_execution_records WHERE business_task_id='{item['id']}' ORDER BY created_at DESC LIMIT 1;")
   expected_suffix=f'{decision}:SETTLED:{units}' if decision=='CAPTURE' else f'{decision}:RELEASED:{units}'
   if row.endswith(expected_suffix):break
  time.sleep(.25)
 if decision is None:
  assert row.endswith(':NULL:RECONCILIATION_REQUIRED') and boss_status=='RECONCILIATION_HOLD:NULL',(mode,row,boss_status,item)
 else:
  assert row.endswith(expected_suffix),(mode,row,expected_suffix,item)
 result={'mode':mode,'run':run['id'],'item':item['id'],'item_status':item['status'],'ledger':row,'fake_call_delta':1}
 if mode in ('mapping-failure','disconnect'):
  task=sql('intelligent-recruitment-postgres-1','recruitment','rd_ir_fresh',f"SELECT agent_task_id FROM ai_execution_records WHERE business_task_id='{item['id']}' ORDER BY created_at DESC LIMIT 1;")
  aep=sql('intelligent-recruitment-postgres-1','recruitment','rd_aep_fresh',f"SELECT status||':'||dispatch_phase||':'||(raw_external_response_ciphertext IS NOT NULL)::text FROM ai_tasks WHERE id='{task}';")
  expected='RESULT_MAPPING_FAILED:RESPONSE_RECEIVED:true' if mode=='mapping-failure' else 'RECONCILIATION_REQUIRED:REQUEST_DISPATCHING:false'
  assert aep==expected,(mode,aep,expected)
  result['platform_evidence']=aep
 print('PASS',json.dumps(result),flush=True)
 return result
def main():
 initial_match=stats().get('/api/v1/recruitment/resume/match',0)
 cases=[]
 cases.append(execute('hard-filter','HARD_FILTERED','CAPTURE',1))
 cases.append(execute('not-evaluated','NOT_EVALUATED','CAPTURE',1))
 cases.append(execute('failed','FAILED','RELEASE',0))
 cases.append(execute('unknown','RECONCILIATION_REQUIRED',None,None))
 cases.append(execute('mapping-failure','RECONCILIATION_REQUIRED',None,None))
 cases.append(execute('disconnect','RECONCILIATION_REQUIRED',None,None))
 time.sleep(15)
 assert stats().get('/api/v1/recruitment/resume/match',0)==initial_match+6,stats()
 for row in cases:
  if row['mode'] in ('mapping-failure','disconnect'):row['late_repeat_check']='no_second_RD_call_after_15s'
 Path('/tmp/rd-e2e/mode-proof.json').write_text(json.dumps(cases,indent=2))
 set_mode('normal')
if __name__=='__main__':main()
