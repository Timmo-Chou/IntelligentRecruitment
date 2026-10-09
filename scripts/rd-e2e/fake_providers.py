"""Loopback synthetic providers. This server has no external-network client."""
import copy,json,time,threading
from pathlib import Path
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
ROOT=Path(__file__).parent/'fixtures'
COUNTS={};CONFIG={'mode':'normal','delay':0};LOCK=threading.Lock()
def fixture(name):return json.loads((ROOT/name).read_text())
class Handler(BaseHTTPRequestHandler):
 def log_message(self,*args):pass
 def reply(self,status,body):
  data=json.dumps(body).encode();self.send_response(status);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data)
 def do_GET(self):
  if self.path=='/__stats':self.reply(200,COUNTS);return
  if self.path=='/api/v1/ready':self.reply(200,fixture('ready-response.json'));return
  self.reply(404,{})
 def do_POST(self):
  if 'chunked' in self.headers.get('Transfer-Encoding','').lower():
   chunks=[]
   while True:
    size=int(self.rfile.readline().split(b';',1)[0].strip(),16)
    if not size:
     self.rfile.readline();break
    chunks.append(self.rfile.read(size));self.rfile.read(2)
   body=b''.join(chunks)
  else:body=self.rfile.read(int(self.headers.get('Content-Length','0')))
  if self.path=='/__control':CONFIG.update(json.loads(body));self.reply(200,CONFIG);return
  with LOCK:COUNTS[self.path]=COUNTS.get(self.path,0)+1
  mode=CONFIG['mode'];delay=CONFIG.get('delay',0)
  if delay:time.sleep(delay)
  if mode=='disconnect':self.connection.close();return
  if self.path.endswith('/chat/completions'):
   req=json.loads(body);data={'title':'Synthetic Engineer','company_name':'Synthetic Co','location':'Shanghai','experience_level':'1.5 years','education':'Bachelor','job_type':'full_time','salary_range':'','responsibilities':['Build services'],'requirements':['Python'],'skills':['Python'],'nice_to_haves':'','benefits':'','talent_profile':'Synthetic profile','warnings':[]}
   self.reply(200,{'id':'fake-chat','model':req.get('model'),'choices':[{'message':{'role':'assistant','content':json.dumps(data)}}],'usage':{'prompt_tokens':100,'completion_tokens':200}});return
  if self.headers.get('Authorization')!='Bearer phase0-fake-key':self.reply(401,{});return
  if self.path.endswith('/jd/generate'):
   value=fixture('jd-response.json');value['business_result']['structured_job']['custom_fields']=json.loads(body).get('custom_fields',{})
   if mode=='mapping-failure':value['business_result'].pop('jd_text',None)
  elif self.path.endswith('/resume/analyze'):
   value=fixture('resume-response.json');value['business_result']['structured_resume']['data']['experience_summary']['total_years']=1.5;value['business_result']['structured_resume']['data']['summary']=None
  elif self.path.endswith('/resume/match'):
   value=fixture('match-response.json');candidate=value['business_result']['candidates'][0]
   if mode=='hard-filter':candidate.update(eligibility='ineligible',hard_filter_failed=True,ai_match_executed=False,vector_retrieval_executed=False,result=None)
   elif mode=='not-evaluated':candidate['result'].update(overall_score=None,result_status='not_evaluated')
   elif mode=='failed':candidate.update(status='failed',result=None,error_code='SYNTHETIC_CONFIRMED_FAILURE')
   elif mode=='unknown':candidate.update(eligibility='unknown',hard_filter_failed=False,ai_match_executed=False,result=None)
   elif mode=='mapping-failure':candidate.pop('input_order',None)
  else:self.reply(404,{});return
  self.reply(200,value)
if __name__=='__main__':ThreadingHTTPServer(('127.0.0.1',18081),Handler).serve_forever()
