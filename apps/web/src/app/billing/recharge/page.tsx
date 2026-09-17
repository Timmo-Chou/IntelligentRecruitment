"use client";

import Link from "next/link";
import { AppShell } from "@/components/layout/app-shell";
import { useTenant } from "@/lib/tenant-context";

export default function RechargePage() {
  const { tenant } = useTenant();
  const enterprise = tenant?.type === "ENTERPRISE";
  return <AppShell activeItem="额度与账单"><section className="mx-auto mt-8 max-w-2xl rounded-2xl border border-[#d8e6f5] bg-white p-7"><h1 className="m-0 text-xl font-bold">购买说明</h1>{enterprise ? <><p className="mt-3 text-sm leading-6 text-[#60799f]">企业首次购买、续期、加购席位和积分包均通过合同订单，由平台运营核验后开通；企业端不提供在线支付入口。</p>{tenant?.owner && <Link href="/enterprise-management?tab=billing" className="primary-button mt-4">查看企业合同与积分</Link>}</> : <p className="mt-3 text-sm leading-6 text-[#60799f]">个人套餐和个人积分包只在个人租户中购买，且与企业合同账户完全隔离。可购买的套餐和积分包将按平台运营发布的产品配置展示。</p>}<Link href="/billing" className="mt-4 block text-sm text-[#2467ca]">返回套餐、积分与账单</Link></section></AppShell>;
}
