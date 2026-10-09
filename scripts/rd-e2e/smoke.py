"""Real HTTP smoke checks; every URL is an isolated loopback test instance."""
import json,time,uuid,urllib.request,urllib.error
from pathlib import Path
from bootstrap import request,sql,TENANT,USER_TOKEN,ADMIN_TOKEN,VIEW_TOKEN
IR='http://127.0.0.1:18092/api/v1/tenants/'+TENANT
BOSS='http://127.0.0.1:18091/api/v1'
OUT=Path('/tmp/rd-e2e')
def poll(call,ready,timeout=60):
 end=time.monotonic()+timeout;last=None
 while time.monotonic()<end:
  last=call()
  if ready(last):return last
  time.sleep(.5)
 raise AssertionError('Timed out: '+json.dumps(last,ensure_ascii=False)[:600])
def api(method,path,data=None,token=USER_TOKEN,extra=None):return request(method,IR+path,data,token,extra)
def multipart(path,filename,body):
 boundary='rd-fixture-'+uuid.uuid4().hex
 payload=(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{filename}"\r\nContent-Type: application/pdf\r\n\r\n').encode()+body+(f'\r\n--{boundary}--\r\n').encode()
 req=urllib.request.Request(IR+path,data=payload,headers={'Authorization':'Bearer '+USER_TOKEN,'Content-Type':'multipart/form-data; boundary='+boundary},method='POST')
 with urllib.request.urlopen(req,timeout=30) as response:return json.load(response)
def key():return {'Idempotency-Key':'rd-fixture-'+uuid.uuid4().hex}
def main():
 task=api('POST','/recruitment-tasks',{'title':'RD synthetic job','initialRequirement':'Build Python services','featureType':'JD_GENERATION'},extra=key())['task']['id']
 print('Created business task',task,flush=True)
 api('POST',f'/recruitment-tasks/{task}/jd-runs',{'title':'Synthetic Engineer','companyName':'Synthetic Co','requirement':'Build reliable Python services','location':'Shanghai','jobType':'full_time','skills':'Python'},extra=key())
 detail=poll(lambda:api('GET',f'/recruitment-tasks/{task}'),lambda d:d.get('latestAiRun',{}).get('status') in ['COMPLETED','FAILED'])
 assert detail['latestAiRun']['status']=='COMPLETED',detail['latestAiRun']
 draft=detail['jdDraft']['id'];job=api('POST',f'/recruitment-tasks/{task}/jd-draft/confirm?draftId={draft}')
 print('JD stored and confirmed',job['id'],flush=True)
 # Give retries a distinct synthetic asset; the tenant+SHA dedupe intentionally
 # returns an existing failed parse for an identical upload.
 candidate=multipart('/candidates/resumes','synthetic-retry.pdf',(OUT/'synthetic.pdf').read_bytes()+('\n%% rd-e2e retry '+uuid.uuid4().hex+'\n').encode())
 candidate=poll(lambda:api('GET','/candidates/'+candidate['id']),lambda c:c['parseStatus'] in ['PARSED','FAILED'])
 assert candidate['parseStatus']=='PARSED',candidate
 assert candidate['yearsExperience']==1.5,candidate['yearsExperience']
 assert isinstance(candidate['structuredResume']['work_experience'][0],dict)
 print('Resume stored with decimal years and structured experience',candidate['id'],flush=True)
 dimensions=[{'name':'岗位技能','weight':100,'description':'根据职位要求核对技能','required':False,'exclusionRule':'','missingPolicy':'REVIEW'}]
 path=BOSS+'/platform/screening-rules/tenants/'+TENANT
 try:request('POST',path,{'jobId':job['id'],'name':'Synthetic rules','dimensions':dimensions,'recruitmentTaskId':task},VIEW_TOKEN)
 except urllib.error.HTTPError as error:assert error.code==403,error.code
 else:raise AssertionError('VIEW-only operator was allowed to edit')
 try:api('POST','/screening-plans',{'jobId':job['id'],'dimensions':dimensions})
 except urllib.error.HTTPError as error:assert error.code==403,error.code
 else:raise AssertionError('Tenant Owner was allowed to edit internal rules')
 plan=request('POST',path,{'jobId':job['id'],'name':'Synthetic rules','dimensions':dimensions,'recruitmentTaskId':task},ADMIN_TOKEN)
 run=api('POST','/screening-runs',{'planId':plan['id'],'candidateIds':[candidate['id']]},extra=key())
 result=poll(lambda:api('GET','/screening-runs/'+run['id']),lambda row:row['status']!='RUNNING')
 assert result['items'][0]['status']=='SUCCEEDED',result
 ledger=sql('intelligent-recruitment-postgres-1','recruitment','rd_ir_fresh',"SELECT count(*) FROM ai_execution_records WHERE final_decision IS NOT NULL AND status NOT IN ('SETTLED','RELEASED');")
 poll(lambda:sql('intelligent-recruitment-postgres-1','recruitment','rd_ir_fresh',"SELECT count(*) FROM ai_execution_records WHERE final_decision IS NOT NULL AND status NOT IN ('SETTLED','RELEASED');"),lambda count:count=='0')
 stats=request('GET','http://127.0.0.1:18081/__stats')
 proof={'task':task,'job':job['id'],'candidate':candidate['id'],'plan':plan['id'],'run':run['id'],'fake_calls':stats,'checks':['JD business save and confirmation','controlled private file download','decimal years','structured experience objects','internal Admin writes','VIEW operator denied','tenant Owner rules write denied','P0 per-candidate match','settlement delivery']}
 (OUT/'smoke-proof.json').write_text(json.dumps(proof,ensure_ascii=False,indent=2))
 print('PASS: real three-system HTTP smoke checks',json.dumps(stats),flush=True)
if __name__=='__main__':
 try:main()
 except urllib.error.HTTPError as error:
  print('HTTP failure',error.code,error.read().decode()[:1000],flush=True);raise
