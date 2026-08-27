"use client";
/* eslint-disable react-hooks/set-state-in-effect */
import { Bell, CheckCheck, ChevronRight } from "lucide-react";
import { useCallback, useEffect, useState } from "react";
import { AppShell } from "@/components/layout/app-shell";
import { apiFetch } from "@/lib/api-client";
type Notification = { id:string; type:string; title:string; content:string; link:string|null; readAt:string|null; createdAt:string };
export default function NotificationsPage() {
  const [items,setItems]=useState<Notification[]>([]); const [unread,setUnread]=useState(0); const [error,setError]=useState("");
  const load=useCallback(async()=>{try{const data=await apiFetch<{items:Notification[];unreadCount:number}>("/notifications");setItems(data.items);setUnread(data.unreadCount);setError("")}catch(cause){setError(cause instanceof Error?cause.message:"通知加载失败")}},[]);
  useEffect(()=>{void load()},[load]);
  async function read(notification:Notification){if(!notification.readAt)await apiFetch(`/notifications/${notification.id}/read`,{method:"POST"});if(notification.link)window.location.assign(notification.link);else await load()}
  async function readAll(){await apiFetch("/notifications/read-all",{method:"POST"});await load()}
  return <AppShell activeItem="通知"><section className="flex items-end justify-between gap-3"><div><h1 className="m-0 text-[25px] font-bold text-[#09245d]">通知</h1><p className="mt-1 text-sm text-[#55709d]">查看企业审核、任务和系统动态。</p></div>{unread>0&&<button type="button" onClick={()=>void readAll()} className="outline-button"><CheckCheck size={15}/>全部已读</button>}</section>{error&&<p className="mt-4 rounded-lg bg-red-50 p-3 text-sm text-red-700">{error}</p>}<section className="mt-5 overflow-hidden rounded-xl border border-[#d6e5f5] bg-white">{!items.length?<div className="grid min-h-64 place-items-center text-sm text-[#7187a8]"><Bell className="mb-2 text-[#9ab1d4]"/>暂无通知</div>:items.map(n=><button type="button" key={n.id} onClick={()=>void read(n)} className={`flex w-full items-center gap-4 border-b border-[#e5edf6] p-5 text-left last:border-0 hover:bg-[#f8fbff] ${!n.readAt?"bg-[#f5faff]":""}`}><span className={`h-2 w-2 shrink-0 rounded-full ${n.readAt?"bg-transparent":"bg-[#2f6bff]"}`}/><div className="min-w-0 flex-1"><h2 className="m-0 text-sm font-semibold text-[#173568]">{n.title}</h2><p className="mb-0 mt-1 text-sm text-[#60799f]">{n.content}</p><time className="mt-2 block text-xs text-[#9aacc5]">{new Date(n.createdAt).toLocaleString("zh-CN")}</time></div>{n.link&&<ChevronRight size={17} className="text-[#9ab1d4]"/>}</button>)}</section></AppShell>
}
