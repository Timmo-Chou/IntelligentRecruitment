"""Local synthetic RD transport; loopback only, never calls a provider."""
import argparse,copy,json
from email.parser import BytesParser
from email.policy import default
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from contract_tools import load,validate_external
MAX_FILE=10485760
MAX_BODY=5*MAX_FILE+1048576
class Handler(BaseHTTPRequestHandler):
 def log_message(self,*args):pass
 def reply(self,status,value):
  data=json.dumps(value,ensure_ascii=False).encode();self.send_response(status);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data)
 def reject(self,status):self.reply(status,load('fixtures/error-response.json'))
 def auth(self):
  if self.headers.get('Authorization')!='Bearer phase0-fake-key':self.reject(401);return False
  return True
 def do_GET(self):
  if not self.auth():return
  if self.path!='/api/v1/ready':self.reject(404);return
  self.reply(200,load('fixtures/ready-response.json'))
 def do_POST(self):
  if not self.auth():return
  try:
   size=int(self.headers.get('Content-Length','0'))
   if not 0<size<=MAX_BODY:self.reject(413);return
   content=self.rfile.read(size)
   if self.path=='/api/v1/recruitment/jd/generate':
    if self.headers.get_content_type()!='application/json':self.reject(415);return
    value=json.loads(content);validate_external('DirectJDRequestDTO',value)
    title=value['slots']['job_title']
    if title.get('status')!='answered' or not isinstance(title.get('value'),str) or not title['value'].strip():self.reject(422);return
    self.reply(200,load('fixtures/jd-response.json'));return
   if self.path not in ['/api/v1/recruitment/resume/analyze','/api/v1/recruitment/resume/match']:self.reject(404);return
   if self.headers.get_content_type()!='multipart/form-data':self.reject(415);return
   msg=BytesParser(policy=default).parsebytes(('Content-Type: '+self.headers['Content-Type']+'\r\nMIME-Version: 1.0\r\n\r\n').encode()+content)
   if not msg.is_multipart():self.reject(422);return
   parts=list(msg.iter_parts());names=[p.get_param('name',header='content-disposition') for p in parts]
   match=self.path.endswith('/match');field='resumes' if match else 'file'
   files=[p for p in parts if p.get_param('name',header='content-disposition')==field]
   allowed={'job_text','resumes'} if match else {'file'}
   if set(names)-allowed or not (1<=len(files)<=5 if match else len(files)==1):self.reject(422);return
   for part in files:
    if part.get_content_type() not in ['application/pdf','application/vnd.openxmlformats-officedocument.wordprocessingml.document']:self.reject(415);return
    if not 0<len(part.get_payload(decode=True))<=MAX_FILE:self.reject(413);return
   if not match:self.reply(200,load('fixtures/resume-response.json'));return
   texts=[p for p in parts if p.get_param('name',header='content-disposition')=='job_text']
   if len(texts)!=1:self.reject(422);return
   jd=texts[0].get_payload(decode=True).decode('utf-8')
   if not jd.strip() or len(jd)>100000:self.reject(422);return
   value=load('fixtures/match-response.json');batch=value['business_result'];template=batch['candidates'][0];batch['candidates']=[]
   for i in range(1,len(files)+1):
    item=copy.deepcopy(template);item.update(input_order=i,attachment_ref=f'attachment_synthetic_{i:03}',rank=i);item['result']['result_ref']=f'candidate_match_synthetic_{i:03}';batch['candidates'].append(item)
   batch.update(input_count=len(files),succeeded_count=len(files),failed_count=0,hard_filtered_count=0)
   validate_external('DirectResumeMatchResponseDTO',value);self.reply(200,value)
  except Exception:self.reject(422)
class LoopbackServer(ThreadingHTTPServer):
 def server_bind(self):
  # Avoid reverse DNS during local-only fixture startup.
  self.socket.bind(self.server_address)
  self.server_address=self.socket.getsockname()
  self.server_name='localhost'
  self.server_port=self.server_address[1]
def server(port=0):return LoopbackServer(('127.0.0.1',port),Handler)
if __name__=='__main__':
 parser=argparse.ArgumentParser();parser.add_argument('--port',type=int,default=18081);args=parser.parse_args();srv=server(args.port);print(f'Fake RD: http://127.0.0.1:{srv.server_port}',flush=True)
 try:srv.serve_forever()
 except KeyboardInterrupt:pass
 finally:srv.server_close()
