"use client";

// 审核详情页面：查看完整注册信息 + 审批/拒绝操作
import { ArrowLeft, Shield, Building2, FileText, Hash, User, Calendar, Clock, AlertCircle, Phone } from "lucide-react";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useState } from "react";
import { useQuery, useMutation } from "@tanstack/react-query";
import { adminApiFetch } from "@/lib/admin-api-client";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";

// 统一展示用类型
type ReviewDetail = {
  id: string;
  type: string;
  status: string;
  targetName: string;
  submitterName: string;
  createdAt: string;
  // 个人认证字段
  realName?: string;
  identityHash?: string;
  userId?: string;
  userDisplayName?: string;
  phoneLastFour?: string;
  // 企业认证字段
  companyName?: string;
  displayName?: string;
  businessLicense?: string;
  creditCodeMasked?: string;
  requestType?: string;
  firstWorkspaceName?: string;
  applicantDisplayName?: string;
  // 成员申请字段
  applicantName?: string;
  companyDisplayName?: string;
  message?: string;
};

// 后端返回的个人认证详情
type PersonalDetail = {
  id: string; userId: string; identityHash: string; realNameMasked: string;
  verificationStatus: string; createdAt: string;
  userDisplayName: string | null;
  phoneLastFour: string | null;
};

// 后端返回的企业认证详情
type CompanyDetail = {
  id: string; applicantUserId: string; legalName: string;
  displayName: string; requestType: string; status: string;
  creditCodeMasked: string; licenseReference: string;
  firstWorkspaceName: string; applicantDisplayName: string | null;
  createdAt: string;
};

// 后端返回的成员申请详情
type MembershipDetail = {
  id: string; companyId: string; applicantUserId: string;
  companyDisplayName: string; status: string; evidence: string;
  userDisplayName: string | null;
  createdAt: string;
};

// 将后端不同格式映射为统一展示类型
function mapToReviewDetail(raw: unknown, type: string): ReviewDetail {
  if (type === "personal") {
    const d = raw as PersonalDetail;
    return {
      id: d.id, type: "personal", status: d.verificationStatus,
      targetName: d.realNameMasked || d.userDisplayName || d.userId,
      submitterName: d.userDisplayName ?? d.userId,
      createdAt: d.createdAt,
      realName: d.realNameMasked,
      identityHash: d.identityHash,
      userId: d.userId,
      userDisplayName: d.userDisplayName ?? "",
      phoneLastFour: d.phoneLastFour ?? "",
    };
  }
  if (type === "company") {
    const d = raw as CompanyDetail;
    return {
      id: d.id, type: "company", status: d.status,
      targetName: d.legalName || d.displayName,
      submitterName: d.applicantDisplayName ?? d.applicantUserId,
      createdAt: d.createdAt,
      companyName: d.legalName,
      displayName: d.displayName,
      businessLicense: d.licenseReference,
      creditCodeMasked: d.creditCodeMasked,
      requestType: d.requestType,
      firstWorkspaceName: d.firstWorkspaceName,
      applicantDisplayName: d.applicantDisplayName ?? d.applicantUserId,
    };
  }
  // membership
  const d = raw as MembershipDetail;
  return {
    id: d.id, type: "membership", status: d.status,
    targetName: d.companyDisplayName || d.companyId,
    submitterName: d.userDisplayName ?? d.applicantUserId,
    createdAt: d.createdAt,
    companyDisplayName: d.companyDisplayName,
    applicantName: d.userDisplayName ?? d.applicantUserId,
    message: d.evidence,
  };
}

export default function ReviewDetailPage() {
  const params = useParams();
  const router = useRouter();
  const type = params.type as string; // personal | company | membership
  const id = params.id as string;

  const [reason, setReason] = useState("");
  const [showRejectInput, setShowRejectInput] = useState(false);
  // 存储个人认证的 userId（审批接口需要），其他类型为 null
  const [personalUserId, setPersonalUserId] = useState<string | null>(null);

  // 根据类型确定后端 API 路径
  function getDetailEndpoint(): string {
    if (type === "personal") return `/platform/reviews/personal/${id}`;
    if (type === "company") return `/platform/reviews/company-verifications/${id}`;
    return `/platform/reviews/membership-applications/${id}`;
  }

  function getProcessEndpoint(action: "APPROVE" | "REJECT"): string {
    if (type === "personal") {
      const uid = personalUserId || id;
      return `/platform/personal-verifications/${uid}/${action === "APPROVE" ? "approve" : "reject"}`;
    }
    if (type === "company") {
      return `/platform/company-verifications/${id}/${action === "APPROVE" ? "approve" : "reject"}`;
    }
    return `/platform/company-membership-applications/${id}/${action === "APPROVE" ? "approve" : "reject"}`;
  }

  const { data: review, isLoading } = useQuery({
    queryKey: ["review", type, id],
    queryFn: async () => {
      const rawData = await adminApiFetch<Record<string, unknown>>(getDetailEndpoint());
      // 保存个人认证的 userId 用于审批
      if (type === "personal" && rawData.userId) {
        setPersonalUserId(rawData.userId as string);
      }
      return mapToReviewDetail(rawData, type);
    },
    enabled: !!id && !!type,
  });

  const processMutation = useMutation({
    mutationFn: async (action: "APPROVE" | "REJECT") => {
      const endpoint = getProcessEndpoint(action);
      return adminApiFetch(endpoint, {
        method: "POST",
        body: JSON.stringify(
          action === "APPROVE"
            ? { reviewer: "平台管理员" }
            : { reviewer: "平台管理员", reason },
        ),
      });
    },
    onSuccess: () => {
      router.push("/reviews");
    },
    onError: (err: Error) => {
      alert("操作失败：" + err.message);
    },
  });

  function getTypeLabel(t: string) {
    const map: Record<string, string> = {
      personal: "个人认证",
      company: "企业认证",
      membership: "成员申请",
    };
    return map[t] ?? t;
  }

  function getRequestTypeLabel(t: string) {
    const map: Record<string, string> = {
      CREATE: "创建企业",
      CLAIM: "认领企业",
      JOIN: "加入企业",
    };
    return map[t] ?? t;
  }

  function getStatusBadge(status: string) {
    if (status === "PENDING") return <Badge variant="warning">待审核</Badge>;
    if (status === "APPROVED" || status === "VERIFIED") return <Badge variant="success">已通过</Badge>;
    if (status === "REJECTED") return <Badge variant="danger">已拒绝</Badge>;
    return <Badge>{status}</Badge>;
  }

  if (isLoading) {
    return <div className="p-8 text-center text-sm text-slate-400">加载中…</div>;
  }

  if (!review) {
    return <div className="p-8 text-center text-sm text-red-500">审核记录不存在</div>;
  }

  return (
    <div>
      <Link
        href="/reviews"
        className="mb-4 inline-flex items-center gap-1 text-sm text-slate-500 hover:text-slate-700"
      >
        <ArrowLeft className="h-4 w-4" />
        返回审核列表
      </Link>

      {/* 审核信息 */}
      <section className="mb-6 rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
        <div className="flex items-start justify-between">
          <div>
            <div className="flex items-center gap-2">
              <h1 className="text-xl font-bold text-slate-800">
                {getTypeLabel(review.type)} - {review.targetName}
              </h1>
              {getStatusBadge(review.status)}
            </div>
            <p className="mt-2 text-sm text-slate-500">
              提交人：{review.submitterName} | 提交时间：{review.createdAt}
            </p>
          </div>
        </div>

        {/* 详细信息 */}
        <div className="mt-6 space-y-6">
          <div className="grid grid-cols-2 gap-4 text-sm">
            {/* 个人认证字段 */}
            {review.realName && (
              <div className="flex items-start gap-2">
                <User className="mt-0.5 h-4 w-4 text-slate-400" />
                <div>
                  <span className="text-slate-400">真实姓名</span>
                  <p className="mt-0.5 font-medium text-slate-700">{review.realName}</p>
                </div>
              </div>
            )}
            {review.identityHash && (
              <div className="flex items-start gap-2">
                <Hash className="mt-0.5 h-4 w-4 text-slate-400" />
                <div>
                  <span className="text-slate-400">身份哈希</span>
                  <p className="mt-0.5 font-mono text-xs text-slate-700 break-all">{review.identityHash}</p>
                </div>
              </div>
            )}
            {review.userId && (
              <div className="flex items-start gap-2">
                <User className="mt-0.5 h-4 w-4 text-slate-400" />
                <div>
                  <span className="text-slate-400">用户ID</span>
                  <p className="mt-0.5 font-mono text-xs text-slate-700">{review.userId}</p>
                </div>
              </div>
            )}
            {review.userDisplayName && (
              <div className="flex items-start gap-2">
                <User className="mt-0.5 h-4 w-4 text-slate-400" />
                <div>
                  <span className="text-slate-400">用户显示名</span>
                  <p className="mt-0.5 font-medium text-slate-700">{review.userDisplayName}</p>
                </div>
              </div>
            )}
            {review.phoneLastFour && (
              <div className="flex items-start gap-2">
                <Phone className="mt-0.5 h-4 w-4 text-slate-400" />
                <div>
                  <span className="text-slate-400">手机号(后四位)</span>
                  <p className="mt-0.5 font-medium text-slate-700">****{review.phoneLastFour}</p>
                </div>
              </div>
            )}

            {/* 企业认证字段 */}
            {review.requestType && (
              <div className="flex items-start gap-2">
                <FileText className="mt-0.5 h-4 w-4 text-slate-400" />
                <div>
                  <span className="text-slate-400">申请类型</span>
                  <p className="mt-0.5 font-medium text-slate-700">{getRequestTypeLabel(review.requestType)}</p>
                </div>
              </div>
            )}
            {review.companyName && (
              <div className="flex items-start gap-2">
                <Building2 className="mt-0.5 h-4 w-4 text-slate-400" />
                <div>
                  <span className="text-slate-400">企业全称</span>
                  <p className="mt-0.5 font-medium text-slate-700">{review.companyName}</p>
                </div>
              </div>
            )}
            {review.displayName && (
              <div className="flex items-start gap-2">
                <Building2 className="mt-0.5 h-4 w-4 text-slate-400" />
                <div>
                  <span className="text-slate-400">企业简称</span>
                  <p className="mt-0.5 font-medium text-slate-700">{review.displayName}</p>
                </div>
              </div>
            )}
            {review.creditCodeMasked && (
              <div className="flex items-start gap-2">
                <Hash className="mt-0.5 h-4 w-4 text-slate-400" />
                <div>
                  <span className="text-slate-400">统一社会信用代码</span>
                  <p className="mt-0.5 font-medium text-slate-700">{review.creditCodeMasked}</p>
                </div>
              </div>
            )}
            {review.businessLicense && (
              <div className="flex items-start gap-2">
                <FileText className="mt-0.5 h-4 w-4 text-slate-400" />
                <div>
                  <span className="text-slate-400">营业执照编号</span>
                  <p className="mt-0.5 font-medium text-slate-700">{review.businessLicense}</p>
                </div>
              </div>
            )}
            {review.firstWorkspaceName && (
              <div className="flex items-start gap-2">
                <FileText className="mt-0.5 h-4 w-4 text-slate-400" />
                <div>
                  <span className="text-slate-400">首个工作空间</span>
                  <p className="mt-0.5 font-medium text-slate-700">{review.firstWorkspaceName}</p>
                </div>
              </div>
            )}
            {review.applicantDisplayName && (
              <div className="flex items-start gap-2">
                <User className="mt-0.5 h-4 w-4 text-slate-400" />
                <div>
                  <span className="text-slate-400">申请人</span>
                  <p className="mt-0.5 font-medium text-slate-700">{review.applicantDisplayName}</p>
                </div>
              </div>
            )}

            {/* 成员申请字段 */}
            {review.companyDisplayName && (
              <div className="flex items-start gap-2">
                <Building2 className="mt-0.5 h-4 w-4 text-slate-400" />
                <div>
                  <span className="text-slate-400">目标企业</span>
                  <p className="mt-0.5 font-medium text-slate-700">{review.companyDisplayName}</p>
                </div>
              </div>
            )}
            {review.applicantName && type === "membership" && (
              <div className="flex items-start gap-2">
                <User className="mt-0.5 h-4 w-4 text-slate-400" />
                <div>
                  <span className="text-slate-400">申请人</span>
                  <p className="mt-0.5 font-medium text-slate-700">{review.applicantName}</p>
                </div>
              </div>
            )}
          </div>

          {/* 申请说明 */}
          {review.message && (
            <div className="rounded-lg border border-slate-200 bg-slate-50 p-4">
              <span className="text-xs font-semibold uppercase text-slate-400">申请说明</span>
              <p className="mt-2 text-sm text-slate-700 whitespace-pre-wrap">{review.message}</p>
            </div>
          )}
        </div>
      </section>

      {/* 审批操作 */}
      {review.status === "PENDING" && (
        <section className="rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
          <h2 className="mb-4 flex items-center gap-2 text-base font-bold text-slate-700">
            <Shield className="h-5 w-5 text-blue-500" />
            审核操作
          </h2>

          {showRejectInput && (
            <div className="mb-4">
              <label className="mb-2 block text-sm font-medium text-slate-700">拒绝原因</label>
              <textarea
                value={reason}
                onChange={(e) => setReason(e.target.value)}
                className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm text-slate-700 focus:border-brand focus:ring-3 focus:ring-brand/10"
                rows={3}
                placeholder="请输入拒绝原因…"
              />
            </div>
          )}

          <div className="flex items-center gap-3">
            <Button
              variant="confirm"
              onClick={() => processMutation.mutate("APPROVE")}
              disabled={processMutation.isPending}
            >
              {processMutation.isPending ? "处理中…" : "通过"}
            </Button>
            {showRejectInput ? (
              <Button
                variant="danger"
                onClick={() => processMutation.mutate("REJECT")}
                disabled={processMutation.isPending || !reason.trim()}
              >
                {processMutation.isPending ? "处理中…" : "确认拒绝"}
              </Button>
            ) : (
              <Button
                variant="secondary"
                onClick={() => setShowRejectInput(true)}
              >
                拒绝
              </Button>
            )}
            {showRejectInput && (
              <Button
                variant="ghost"
                onClick={() => { setShowRejectInput(false); setReason(""); }}
              >
                取消
              </Button>
            )}
          </div>

          {processMutation.error && (
            <p className="mt-3 text-sm text-red-500">
              操作失败：{(processMutation.error as Error).message}
            </p>
          )}
        </section>
      )}
    </div>
  );
}