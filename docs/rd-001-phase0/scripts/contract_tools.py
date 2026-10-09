"""RD-001 offline contract helpers. No provider or production calls."""
import hashlib,json
from pathlib import Path
from jsonschema import Draft202012Validator,FormatChecker
ROOT=Path(__file__).resolve().parents[1]
def load(path):return json.loads((ROOT/path).read_text())
INTERNAL=load('contracts/internal-v2.schema.json')
EXTERNAL=load('external/04-openapi-v1.1.0.json')
def validate_internal(name,value):
 Draft202012Validator({'$ref':'#/$defs/'+name,'$defs':INTERNAL['$defs']},format_checker=FormatChecker()).validate(value)
def validate_external(name,value):
 Draft202012Validator({'$ref':'#/components/schemas/'+name,'components':EXTERNAL['components']},format_checker=FormatChecker()).validate(value)
def _hash_values(value):
 if isinstance(value,float):raise ValueError('hash material decimals must be normalized decimal strings')
 if isinstance(value,str):
  if any(0xd800<=ord(c)<=0xdfff for c in value):raise ValueError('unpaired surrogate')
 if isinstance(value,list):
  for item in value:_hash_values(item)
 if isinstance(value,dict):
  for key,item in value.items():_hash_values(key);_hash_values(item)
def canonical(value):
 _hash_values(value)
 return json.dumps(value,ensure_ascii=False,sort_keys=True,separators=(',',':'),allow_nan=False).encode('utf-8')
def input_hash(material):
 validate_internal('hash_material',material)
 return hashlib.sha256(canonical(material)).hexdigest()
def validate_binding(request,grant):
 validate_internal('execution_request',request);validate_internal('grant',grant)
 ctx=request['request_context']
 for key in ['tenant_id','actor_id','attempt_id','idempotency_key']:
  if ctx[key]!=grant[key]:raise ValueError('GRANT_BINDING_MISMATCH:'+key)
 for key in ['agent_id','operation','route_config_version','input_hash','authorization_id','grant_id','reservation_id']:
  if request[key]!=grant[key]:raise ValueError('GRANT_BINDING_MISMATCH:'+key)
 if ctx['business_task_id']!=grant['task_id']:raise ValueError('GRANT_BINDING_MISMATCH:task_id')
 route=next(r for r in load('contracts/routes.json') if r['platform_capability']==request['capability'])
 if route['boss_capability']!=grant['capability']:raise ValueError('GRANT_BINDING_MISMATCH:capability')
 material=request['hash_material']
 for key in ['agent_id','capability','operation']:
  if request[key]!=material[key]:raise ValueError('HASH_BINDING_MISMATCH:'+key)
 if material['business_task_id']!=ctx['business_task_id'] or request['input_versions']!=material['input_versions'] or request['input']!=material['input']:raise ValueError('HASH_BINDING_MISMATCH:input')
 policy=request['policy_decision']
 if policy['tenant_id']!=ctx['tenant_id'] or policy['actor_id']!=ctx['actor_id'] or policy['capability']!=request['capability']:raise ValueError('POLICY_BINDING_MISMATCH')
 if request['execution_id']!=ctx['attempt_id']:raise ValueError('ATTEMPT_BINDING_MISMATCH')
 for key,expected in [('jti',grant['grant_id']),('sub',grant['task_id'])]:
  if key in grant and grant[key]!=expected:raise ValueError('GRANT_BINDING_MISMATCH:'+key)
 if input_hash(material)!=request['input_hash']:raise ValueError('INPUT_HASH_MISMATCH')
 if request['selection_source']!=route['selection_source']:raise ValueError('SELECTION_SOURCE_MISMATCH')
 if grant['exp']<=grant['iat'] or grant['nbf']>grant['exp']:raise ValueError('GRANT_TIME_INVALID')
 if grant['authorized_unit_count']!=1 and request['capability']!='candidate_screening':raise ValueError('UNIT_COUNT_INVALID')
 pricing=grant['pricing_snapshot']
 if pricing['billing_method']!='TOKEN' and pricing['fixed_credits_per_unit']*grant['authorized_unit_count']>pricing['maximum_charge']:raise ValueError('PRICE_RESERVATION_INVALID')
 if pricing['billing_method']=='FIXED_PER_EXECUTION' and grant['authorized_unit_count']!=1:raise ValueError('UNIT_COUNT_INVALID')
 if pricing['billing_method']=='FIXED_PER_CANDIDATE' and request['capability']!='candidate_screening':raise ValueError('BILLING_METHOD_INVALID')
def validate_settlement(decision,grant):
 validate_internal('settlement',decision)
 for key in ['tenant_id','actor_id','task_id','attempt_id','product_domain','capability','operation','agent_id','idempotency_key','route_config_version','input_hash','authorization_id','reservation_id']:
  if decision[key]!=grant[key]:raise ValueError('SETTLEMENT_BINDING_MISMATCH:'+key)
 units=decision['unit_decisions']
 if len(units)!=grant['authorized_unit_count'] or len({x['unit_id'] for x in units})!=len(units):raise ValueError('UNIT_COUNT_INVALID')
 if sum(x['billable_unit_count'] for x in units)!=decision['executed_unit_count']:raise ValueError('UNIT_SUM_INVALID')
 for unit in units:
  if unit['result_validity']=='UNVERIFIED_RESULT' and (unit['billable_unit_count']!=0 or unit['billing_reason_code']!='RECONCILIATION_DEADLINE_EXPIRED'):raise ValueError('HOLD_REQUIRED')
  if unit['result_validity']=='CONFIRMED_NO_RESULT' and unit['billable_unit_count']!=0:raise ValueError('INVALID_BILLING_UNIT')
  if unit['result_validity']=='VALID_BUSINESS_RESULT' and unit['billable_unit_count']!=1:raise ValueError('INVALID_BILLING_UNIT')
 if decision['final_decision']=='RELEASE' and decision['executed_unit_count']!=0:raise ValueError('RELEASE_WITH_BILLABLE_UNITS')
 if decision['final_decision']=='CAPTURE' and grant['pricing_snapshot']['billing_method']!='TOKEN' and decision['executed_unit_count']==0:raise ValueError('EMPTY_CAPTURE')
 if grant['pricing_snapshot']['billing_method']=='TOKEN' and decision['final_decision']=='CAPTURE':
  usage=decision['usage']
  if usage is None or usage.get('input_tokens') is None or usage.get('output_tokens') is None:raise ValueError('USAGE_HOLD_REQUIRED')
def validate_event(value):
 validate_internal('webhook',value)
 state=next(x for x in load('contracts/states.json') if x['http']==value['task']['status'])
 if value['event_type'] not in state['event_types']:raise ValueError('EVENT_STATUS_MISMATCH')
def validate_result(value):
 validate_internal('result',value)
 if value['capability']!='candidate_screening':return
 items=value['data']['candidates']
 if sorted(x['input_order'] for x in items)!=list(range(1,len(items)+1)):raise ValueError('INPUT_ORDER_INVALID')
 for key in ['candidate_id','file_asset_id']:
  if len({x[key] for x in items})!=len(items):raise ValueError('CANDIDATE_BINDING_INVALID')
 if value['status']=='completed' and any(x['evaluation_status']=='RECONCILIATION_REQUIRED' or (x['result_validity']=='UNVERIFIED_RESULT' and x['billable_unit_count'] is None) for x in items):raise ValueError('UNVERIFIED_BATCH_CANNOT_COMPLETE')
 for item in items:
  scored=item['evaluation_status']=='SUCCEEDED'
  if scored:
   if item['score'] is None:raise ValueError('SCORE_MISSING')
   score=item['score'];level='STRONG_MATCH' if score>=85 else 'MATCH' if score>=70 else 'GENERAL_MATCH' if score>=60 else 'WEAK_MATCH'
   if item['level']!=level:raise ValueError('LEVEL_MISMATCH')
  elif item['score'] is not None or item['level'] is not None:raise ValueError('UNSCORED_HAS_SCORE')
  if item['result_validity']=='UNVERIFIED_RESULT' and item['billable_unit_count'] is not None:
   if item['billable_unit_count']!=0 or item['billing_reason_code']!='RECONCILIATION_DEADLINE_EXPIRED':raise ValueError('HOLD_REQUIRED')
  if item['result_validity']=='CONFIRMED_NO_RESULT' and item['billable_unit_count']!=0:raise ValueError('INVALID_BILLING_UNIT')
  if item['result_validity']=='VALID_BUSINESS_RESULT' and item['billable_unit_count']!=1:raise ValueError('INVALID_BILLING_UNIT')
