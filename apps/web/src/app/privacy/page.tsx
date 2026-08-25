import type { Metadata } from "next";
import { LegalDocumentPage } from "@/components/legal/legal-document-page";

export const metadata: Metadata = {
  title: "隐私政策 | AI 智能招聘",
};

export default function PrivacyPage() {
  return <LegalDocumentPage fileName="privacy-policy" />;
}
