import type { Metadata } from "next";
import { LegalDocumentPage } from "@/components/legal/legal-document-page";

export const metadata: Metadata = {
  title: "用户服务协议 | AI 智能招聘",
};

export default function TermsPage() {
  return <LegalDocumentPage fileName="user-agreement" />;
}
