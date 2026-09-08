import type { Metadata } from "next";
import { BossApiManualPage } from "@/components/open-platform/boss-api-manual-page";

export const metadata: Metadata = {
  title: "BOSS 开放平台 API 接入手册",
  description: "外部系统接入 BOSS 身份、企业、权限、计费与事件能力的服务端指南。",
};

export default function OpenPlatformPage() {
  return <BossApiManualPage />;
}
