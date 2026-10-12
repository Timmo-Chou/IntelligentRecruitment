"use client";
import { useState } from "react";
import { adminApiFetch } from "@/lib/admin-api-client";
import { usePermission } from "@/lib/use-permission";
import { AdminErrorNotice } from "@/components/admin-error-notice";
type Dimension={name:string;weight:number;description:string;required:boolean;exclusionRule:string;missingPolicy:string};
type Plan={id:string;name:string;jobId:string;versionNumber:number;dimensions:Dimension[]};
export default function ScreeningRulesPage(){
 const {hasPermission}=usePermission();const canEdit=hasPermission("ADMIN_SCREENING_RULE_EDIT");
 const [tenant,setTenant]=useState("");const [job,setJob]=useState("");const [task,setTask]=useState("");const [name,setName]=useState("");const [plan,setPlan]=useState("");
 const [rows,setRows]=useState<Plan[]>([]);const [dimensions,setDimensions]=useState<Dimension[]>([{name:"岗位技能",weight:100,description:"依据职位要求验证技能与经历",required:false,exclusionRule:"",missingPolicy:"REVIEW"}]);const [message,setMessage]=useState("");const [actionError,setActionError]=useState<unknown>(null);
 const path=`/platform/screening-rules/tenants/${tenant}`;
 async function load(){setMessage("");setActionError(null);try{setRows(await adminApiFetch<Plan[]>(path));}catch(error){setActionError(error);}}
 async function save(){setMessage("");setActionError(null);try{await adminApiFetch(path+(plan?`/${plan}`:""),{method:"POST",body:JSON.stringify({jobId:job,name,dimensions,...(!plan?{recruitmentTaskId:task||null}:{})})});setMessage("规则版本已保存");await load();}catch(error){setActionError(error);}}
 return <div className="space-y-4"><h1 className="text-2xl font-bold">筛选规则</h1><p>维护职位对应的规则版本。已有筛选继续使用创建时冻结的版本。</p>
 <label className="block">企业编号<input className="ml-3 rounded border p-2" value={tenant} onChange={e=>setTenant(e.target.value)}/></label><button onClick={load} disabled={!tenant||!hasPermission("ADMIN_SCREENING_RULE_VIEW")} className="rounded bg-blue-600 p-2 text-white">加载方案</button>
 <ul>{rows.map(row=><li key={row.id}><button onClick={()=>{setPlan(row.id);setJob(row.jobId);setName(row.name);setDimensions(row.dimensions);}}>{row.name} · 版本 {row.versionNumber}</button></li>)}</ul>
 {actionError != null &&<AdminErrorNotice error={actionError} fallback="筛选规则操作暂未成功，请检查信息后重试。" className="rounded bg-red-50 p-3 text-sm text-red-700"/>}{canEdit&&<section className="space-y-3 rounded border p-4"><button onClick={()=>{setPlan("");setName("");}}>新增方案</button><label className="block">方案名称<input className="ml-3 border p-2" value={name} onChange={e=>setName(e.target.value)}/></label><label className="block">职位编号<input className="ml-3 border p-2" value={job} onChange={e=>setJob(e.target.value)}/></label>{!plan&&<label className="block">招聘任务编号（可选）<input className="ml-3 border p-2" value={task} onChange={e=>setTask(e.target.value)}/></label>}
 {dimensions.map((d,i)=><div key={i} className="flex flex-wrap gap-2"><input aria-label="维度名称" value={d.name} onChange={e=>setDimensions(ds=>ds.map((x,n)=>n===i?{...x,name:e.target.value}:x))}/><input aria-label="权重" type="number" min="1" max="100" value={d.weight} onChange={e=>setDimensions(ds=>ds.map((x,n)=>n===i?{...x,weight:Number(e.target.value)}:x))}/><input aria-label="规则描述" value={d.description} onChange={e=>setDimensions(ds=>ds.map((x,n)=>n===i?{...x,description:e.target.value}:x))}/><label><input type="checkbox" checked={d.required} onChange={e=>setDimensions(ds=>ds.map((x,n)=>n===i?{...x,required:e.target.checked}:x))}/>必要条件</label><input aria-label="排除条件" value={d.exclusionRule} onChange={e=>setDimensions(ds=>ds.map((x,n)=>n===i?{...x,exclusionRule:e.target.value}:x))}/><button onClick={()=>setDimensions(ds=>ds.filter((_,n)=>n!==i))}>删除</button></div>)}
 <button onClick={()=>setDimensions(ds=>[...ds,{name:"",weight:1,description:"",required:false,exclusionRule:"",missingPolicy:"REVIEW"}])}>添加维度</button><button disabled={!tenant||!job||!dimensions.length} onClick={save} className="ml-3 rounded bg-blue-600 p-2 text-white">保存新版本</button></section>}{message&&<p role="status">{message}</p>}</div>;
}
