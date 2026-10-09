"""Creates a dedicated disposable MinIO instance and synthetic-only private bucket."""
import subprocess,time,datetime,hashlib,hmac,urllib.request,urllib.error
NAME='rd-validation-minio';ENDPOINT='http://127.0.0.1:19000';ACCESS='rd-fixture-storage';SECRET='rd-fixture-storage-secret';BUCKET='rd-validation'
def prepare():
 existing=subprocess.run(['docker','inspect',NAME],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
 if existing.returncode:
  image=subprocess.check_output(['docker','inspect','--format','{{.Image}}','intelligent-recruitment-minio-1']).decode().strip()
  subprocess.run(['docker','run','-d','--name',NAME,'-p','19000:9000','-e','MINIO_ROOT_USER='+ACCESS,'-e','MINIO_ROOT_PASSWORD='+SECRET,image,'server','/data'],check=True,stdout=subprocess.DEVNULL)
 for _ in range(60):
  try:urllib.request.urlopen(ENDPOINT+'/minio/health/ready',timeout=2);break
  except Exception:time.sleep(.5)
 else:raise RuntimeError('Dedicated test storage not ready')
 now=datetime.datetime.now(datetime.timezone.utc);stamp=now.strftime('%Y%m%dT%H%M%SZ');date=now.strftime('%Y%m%d');digest=hashlib.sha256(b'').hexdigest()
 headers={'host':'127.0.0.1:19000','x-amz-content-sha256':digest,'x-amz-date':stamp}
 names=';'.join(sorted(headers));canonical_headers=''.join(k+':'+headers[k]+'\n' for k in sorted(headers))
 canonical='PUT\n/'+BUCKET+'\n\n'+canonical_headers+'\n'+names+'\n'+digest
 scope=date+'/us-east-1/s3/aws4_request';string='AWS4-HMAC-SHA256\n'+stamp+'\n'+scope+'\n'+hashlib.sha256(canonical.encode()).hexdigest()
 def sign(key,data):return hmac.new(key,data.encode(),hashlib.sha256).digest()
 key=sign(sign(sign(sign(('AWS4'+SECRET).encode(),date),'us-east-1'),'s3'),'aws4_request');signature=hmac.new(key,string.encode(),hashlib.sha256).hexdigest()
 headers['Authorization']='AWS4-HMAC-SHA256 Credential='+ACCESS+'/'+scope+', SignedHeaders='+names+', Signature='+signature
 req=urllib.request.Request(ENDPOINT+'/'+BUCKET,data=b'',headers=headers,method='PUT')
 try:urllib.request.urlopen(req,timeout=5)
 except urllib.error.HTTPError as e:
  if e.code!=409:raise
if __name__=='__main__':prepare();print('Dedicated test storage and private bucket ready.')
