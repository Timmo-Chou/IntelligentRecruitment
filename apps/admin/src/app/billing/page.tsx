export default function BillingPage() {
  return <Notice title="账单管理已迁移至 BOSS" detail="Recruitment SaaS 不再保存或修改账单、余额、充值和结算数据。请在 BOSS 管理台完成财务操作。" />;
}

function Notice({ title, detail }: { title: string; detail: string }) {
  return <main className="mx-auto max-w-3xl p-10"><section className="rounded-xl border border-slate-200 bg-white p-8"><h1 className="text-xl font-semibold text-slate-800">{title}</h1><p className="mt-3 text-sm leading-6 text-slate-500">{detail}</p></section></main>;
}
