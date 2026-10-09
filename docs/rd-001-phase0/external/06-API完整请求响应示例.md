# API 完整请求响应示例

以下 JSON 均来自正式 v1.1.0 Response DTO 的 synthetic examples，并通过对应 Pydantic
DTO 验证。字段结构与 `04-openapi-v1.1.0.json` 一致；示例中的引用号和人物信息均为
合成数据，不是 Production 业务结果。

认证 Header 在三个场景中相同：

```http
Authorization: Bearer <SERVICE_API_KEY>
```

真实 Key 只能由合作方服务端安全读取，不得复制进本文档、前端、Git 或日志。

## 1. JD Generate

### Request

`POST /api/v1/recruitment/jd/generate`，`Content-Type: application/json`。

```json
{
  "custom_fields": {},
  "slots": {
    "job_title": {
      "status": "answered",
      "value": "高级 Python 后端工程师"
    },
    "required_skills": {
      "status": "answered",
      "value": [
        {
          "minimum_years": 3,
          "name": "Python",
          "note": "具备生产环境后端服务开发经验。"
        },
        {
          "minimum_years": 2,
          "name": "PostgreSQL",
          "note": "具备关系型数据库设计和调优经验。"
        }
      ]
    },
    "responsibilities": {
      "status": "answered",
      "value": [
        "负责 Python 后端服务设计、开发与稳定性建设。",
        "负责 PostgreSQL 数据模型设计和性能优化。"
      ]
    }
  }
}
```

### Response 200 — DirectJDGenerateResponseDTO

```json
{
  "business_result": {
    "jd_text": "# Synthetic Platform Engineer\n\nBuild reliable services.",
    "result_ref": "job_synthetic_001",
    "schema_version": "recruitment_generated_job_v1",
    "structured_job": {
      "custom_fields": {},
      "declared_requirements": [
        {
          "category": "required_skill",
          "description": "Production Python experience.",
          "minimum_years": 3,
          "name": "Python",
          "requirement_code": "required_skill_python"
        }
      ],
      "department": "engineering",
      "description": "Build reliable services for a synthetic platform.",
      "employment_type": "full_time",
      "location": "Shanghai",
      "qualifications": [
        "Production Python experience."
      ],
      "responsibilities": [
        "Design and maintain backend services."
      ],
      "result_ref": "job_synthetic_001",
      "schema_version": "recruitment_structured_job_v1",
      "title": "Synthetic Platform Engineer"
    }
  },
  "execution": {
    "child_trace_ids": [],
    "execution_ref": "direct_jd_synthetic_001",
    "request_id": "req_synthetic_jd_001",
    "root_trace_id": "trace_synthetic_jd_001",
    "status": "completed",
    "usage": {
      "total_tokens": 120
    }
  }
}
```

## 2. Resume Analyze

### Request

`POST /api/v1/recruitment/resume/analyze`，使用 `multipart/form-data`，字段名固定为
`file`，上传一份不超过 10 MiB 的 PDF 或 DOCX：

```bash
curl '<BASE_URL>/api/v1/recruitment/resume/analyze' \
  -H 'Authorization: Bearer <SERVICE_API_KEY>' \
  -F 'file=@synthetic-resume.pdf;type=application/pdf'
```

### Response 200 — DirectResumeAnalyzeResponseDTO

```json
{
  "business_result": {
    "analysis_request_ref": "direct_resume_synthetic_001",
    "analysis_text": "Synthetic candidate assessment.",
    "assessment_definition_version": "recruitment_talent_dimensions_v1",
    "attachment_refs": [
      "attachment_synthetic_001"
    ],
    "core_experience": [],
    "dimensions": [
      {
        "conclusion": "Strong synthetic evidence.",
        "confidence": "high",
        "dimension_code": "professional_capability",
        "display_name": "Professional capability",
        "evidence": [],
        "score": 80
      }
    ],
    "overall_assessment": "Suitable for further synthetic evaluation.",
    "radar": [
      {
        "available": true,
        "dimension_code": "professional_capability",
        "display_name": "Professional capability",
        "score": 80
      }
    ],
    "result_ref": "resume_analysis_synthetic_001",
    "resume_ref": "resume_synthetic_001",
    "risks_and_questions": [],
    "schema_version": "recruitment_resume_analysis_v1",
    "strengths": [],
    "structured_resume": {
      "data": {
        "basic_info": {
          "email": null,
          "headline": "Backend Engineer",
          "location": "Shanghai",
          "name": "Synthetic Candidate",
          "phone": null
        },
        "certificates": [],
        "education": [],
        "experience_summary": {
          "domains": [
            "backend"
          ],
          "highlights": [
            "Synthetic platform delivery"
          ],
          "total_years": 5
        },
        "languages": [],
        "project_experience": [],
        "schema_version": "1.0",
        "skills": [],
        "work_experience": []
      },
      "resume_ref": "resume_synthetic_001",
      "schema_version": "1.0"
    },
    "talent_type": "backend_engineer",
    "talent_type_evidence": [
      {
        "evidence_ref": "evidence_synthetic_001",
        "source_type": "resume",
        "summary": "Synthetic backend experience."
      }
    ],
    "talent_type_reason": "The synthetic profile emphasizes backend delivery."
  },
  "execution": {
    "child_trace_ids": [],
    "execution_ref": "direct_resume_synthetic_001",
    "request_id": "req_synthetic_resume_001",
    "root_trace_id": "trace_synthetic_resume_001",
    "status": "completed",
    "usage": null
  }
}
```

## 3. Resume Match

`POST /api/v1/recruitment/resume/match` 使用 `multipart/form-data`。每次恰好使用一种
JD 输入模式，并使用重复的 `resumes` 字段上传 1–5 份 PDF/DOCX 简历。

### Text JD

```bash
curl '<BASE_URL>/api/v1/recruitment/resume/match' \
  -H 'Authorization: Bearer <SERVICE_API_KEY>' \
  -F 'job_text=Synthetic backend role with complete responsibilities and requirements.' \
  -F 'resumes=@synthetic-resume-1.pdf;type=application/pdf' \
  -F 'resumes=@synthetic-resume-2.docx;type=application/vnd.openxmlformats-officedocument.wordprocessingml.document'
```

### File JD

```bash
curl '<BASE_URL>/api/v1/recruitment/resume/match' \
  -H 'Authorization: Bearer <SERVICE_API_KEY>' \
  -F 'job_file=@synthetic-job.pdf;type=application/pdf' \
  -F 'resumes=@synthetic-resume.pdf;type=application/pdf'
```

### Structured JD

`structured_job` 的 multipart 值是以下 JSON 的字符串序列化结果：

```json
{
  "declared_requirements": [
    "Product planning experience"
  ],
  "department": "product",
  "description": "Own product planning and delivery for an enterprise platform.",
  "employment_type": "full_time",
  "job_ref": "job_partner_product_manager_001",
  "location": "Shanghai",
  "qualifications": [
    "Experience with requirements analysis and cross-team delivery."
  ],
  "raw_text": null,
  "responsibilities": [
    "Research customer needs and maintain the product roadmap."
  ],
  "schema_version": "1.0",
  "source": "external_hr_system",
  "title": "Product Manager"
}
```

同时重复发送 1–5 个 `resumes` 文件字段。不要同时发送 `job_text` 或 `job_file`。

### Response 200 — DirectResumeMatchResponseDTO

```json
{
  "business_result": {
    "candidates": [
      {
        "ai_match_executed": true,
        "attachment_ref": "attachment_synthetic_001",
        "capabilities": {
          "llm": "available"
        },
        "eligibility": "eligible",
        "error_code": null,
        "failure_stage": null,
        "hard_filter_failed": false,
        "input_order": 1,
        "rank": 1,
        "result": {
          "criteria_results": [],
          "eligibility": "eligible",
          "hard_requirement_summary": {
            "reason": "Synthetic requirements are satisfied.",
            "source": "rule",
            "status": "satisfied"
          },
          "hard_rule_results": [],
          "interview_suggestions": [],
          "missing_items": [],
          "overall_score": 82,
          "recommendation": "recommend",
          "result_ref": "candidate_match_synthetic_001",
          "result_status": "completed",
          "risks": [],
          "schema_version": "1.0",
          "score_coverage": 100,
          "strengths": [],
          "summary": "Synthetic candidate meets the core requirements.",
          "uncertainties": []
        },
        "status": "completed",
        "vector_retrieval_executed": false
      }
    ],
    "execution_ref": "direct_match_synthetic_001",
    "failed_count": 0,
    "hard_filtered_count": 0,
    "implementation_code": "self",
    "input_count": 1,
    "job_snapshot_hash": "0000000000000000000000000000000000000000000000000000000000000000",
    "policy_ref": "recruitment_default_v1",
    "result_ref": "match_synthetic_001",
    "schema_version": "recruitment_match_batch_v1",
    "status": "completed",
    "succeeded_count": 1
  },
  "execution": {
    "child_trace_ids": [
      "trace_synthetic_candidate_001"
    ],
    "execution_ref": "direct_match_synthetic_001",
    "request_id": "req_synthetic_match_001",
    "root_trace_id": "trace_synthetic_match_001",
    "status": "completed",
    "usage": null
  }
}
```
