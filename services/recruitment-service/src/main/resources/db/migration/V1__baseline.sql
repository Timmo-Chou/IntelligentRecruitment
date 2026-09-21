--
-- PostgreSQL database dump
--


-- Dumped from database version 17.11
-- Dumped by pg_dump version 17.11

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: pgcrypto; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA public;


--
-- Name: EXTENSION pgcrypto; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION pgcrypto IS 'cryptographic functions';


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: ai_runs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ai_runs (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    recruitment_task_id uuid NOT NULL,
    capability character varying(64) NOT NULL,
    provider_task_id character varying(200),
    status character varying(32) NOT NULL,
    progress integer DEFAULT 0 NOT NULL,
    attempt_number integer NOT NULL,
    idempotency_key character varying(200) NOT NULL,
    input_hash character varying(64) NOT NULL,
    error_code character varying(100),
    error_message character varying(500),
    created_by uuid NOT NULL,
    created_at timestamp with time zone NOT NULL,
    completed_at timestamp with time zone,
    input_payload jsonb DEFAULT '{}'::jsonb NOT NULL,
    policy_decision jsonb DEFAULT '{}'::jsonb NOT NULL,
    execution_context jsonb DEFAULT '{}'::jsonb NOT NULL
);


--
-- Name: audit_logs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.audit_logs (
    id uuid NOT NULL,
    actor_user_id uuid,
    tenant_id uuid,
    action character varying(100) NOT NULL,
    resource_type character varying(80) NOT NULL,
    resource_id character varying(100) NOT NULL,
    metadata jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone NOT NULL
);


--
-- Name: candidates; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.candidates (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    display_name_masked character varying(120) NOT NULL,
    full_name_ciphertext text NOT NULL,
    email_ciphertext text,
    phone_ciphertext text,
    current_parse_version_id uuid,
    status character varying(32) NOT NULL,
    created_by uuid NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    profile jsonb DEFAULT '{}'::jsonb NOT NULL,
    search_text text DEFAULT ''::text NOT NULL,
    full_name_search_hash character varying(64),
    phone_search_hash character varying(64)
);


--
-- Name: conversations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.conversations (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    recruitment_task_id uuid NOT NULL,
    status character varying(32) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL
);


--
-- Name: enterprise_job_pool_copies; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.enterprise_job_pool_copies (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    source_job_id uuid NOT NULL,
    source_owner_user_id uuid NOT NULL,
    snapshot jsonb NOT NULL,
    source_updated_at timestamp with time zone NOT NULL,
    synchronized_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    lifecycle_status character varying(16) DEFAULT 'ACTIVE'::character varying NOT NULL,
    deleted_at timestamp with time zone,
    sync_status character varying(24) DEFAULT 'SYNCED'::character varying NOT NULL,
    CONSTRAINT enterprise_job_pool_copies_sync_status_check CHECK (((sync_status)::text = ANY ((ARRAY['SYNCED'::character varying, 'PENDING_UPDATE'::character varying, 'SYNC_FAILED'::character varying])::text[])))
);


--
-- Name: enterprise_pool_attachment_assets; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.enterprise_pool_attachment_assets (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    talent_pool_copy_id uuid NOT NULL,
    source_file_asset_id uuid NOT NULL,
    object_key character varying(500) NOT NULL,
    original_filename character varying(255) NOT NULL,
    media_type character varying(120) NOT NULL,
    size_bytes bigint NOT NULL,
    sha256 character varying(64) NOT NULL,
    lifecycle_status character varying(32) DEFAULT 'ACTIVE'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: enterprise_pool_export_logs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.enterprise_pool_export_logs (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    pool_type character varying(32) NOT NULL,
    actor_user_id uuid NOT NULL,
    filter_criteria jsonb NOT NULL,
    fields jsonb NOT NULL,
    record_count integer NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT enterprise_pool_export_logs_pool_type_check CHECK (((pool_type)::text = ANY ((ARRAY['TALENT'::character varying, 'JOB'::character varying])::text[])))
);


--
-- Name: enterprise_talent_pool_copies; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.enterprise_talent_pool_copies (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    source_candidate_id uuid NOT NULL,
    source_owner_user_id uuid NOT NULL,
    snapshot jsonb NOT NULL,
    source_updated_at timestamp with time zone NOT NULL,
    synchronized_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    lifecycle_status character varying(16) DEFAULT 'ACTIVE'::character varying NOT NULL,
    deleted_at timestamp with time zone,
    sync_status character varying(24) DEFAULT 'SYNCED'::character varying NOT NULL,
    CONSTRAINT enterprise_talent_pool_copies_sync_status_check CHECK (((sync_status)::text = ANY ((ARRAY['SYNCED'::character varying, 'PENDING_UPDATE'::character varying, 'SYNC_FAILED'::character varying])::text[])))
);


--
-- Name: file_assets; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.file_assets (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    object_key character varying(500) NOT NULL,
    original_filename character varying(255) NOT NULL,
    media_type character varying(120) NOT NULL,
    size_bytes bigint NOT NULL,
    sha256 character varying(64) NOT NULL,
    scan_status character varying(32) NOT NULL,
    lifecycle_status character varying(32) NOT NULL,
    created_by uuid NOT NULL,
    created_at timestamp with time zone NOT NULL
);


--
-- Name: foundation_async_probe; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.foundation_async_probe (
    id uuid NOT NULL,
    status character varying(32) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    completed_at timestamp with time zone,
    version bigint DEFAULT 0 NOT NULL
);


--
-- Name: idempotency_records; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.idempotency_records (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    actor_id character varying(64) NOT NULL,
    operation_type character varying(100) NOT NULL,
    idempotency_key character varying(200) NOT NULL,
    request_hash character varying(128) NOT NULL,
    status character varying(32) NOT NULL,
    response_reference character varying(200),
    created_at timestamp with time zone NOT NULL,
    expires_at timestamp with time zone NOT NULL
);


--
-- Name: interview_kit_versions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.interview_kit_versions (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    kit_id uuid NOT NULL,
    screening_result_id uuid,
    version_no integer NOT NULL,
    status character varying(24) NOT NULL,
    created_by uuid NOT NULL,
    created_at timestamp with time zone NOT NULL
);


--
-- Name: interview_kits; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.interview_kits (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    job_version_id uuid,
    candidate_id uuid NOT NULL,
    screening_result_id uuid,
    status character varying(24) NOT NULL,
    created_by uuid NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    core_competencies jsonb DEFAULT '[]'::jsonb NOT NULL,
    match_summary text DEFAULT ''::text NOT NULL
);


--
-- Name: interview_questions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.interview_questions (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    kit_version_id uuid NOT NULL,
    category character varying(24) NOT NULL,
    content text NOT NULL,
    rationale text,
    focus_points text,
    scoring_points text,
    evidence_refs text,
    sort_order integer NOT NULL,
    reference_answer_points text DEFAULT ''::text NOT NULL
);


--
-- Name: jd_drafts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.jd_drafts (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    recruitment_task_id uuid NOT NULL,
    source_ai_run_id uuid,
    revision integer NOT NULL,
    title character varying(200) NOT NULL,
    company_name character varying(200) NOT NULL,
    location character varying(200) DEFAULT ''::character varying NOT NULL,
    experience_level character varying(80) DEFAULT ''::character varying NOT NULL,
    education character varying(80) DEFAULT ''::character varying NOT NULL,
    job_type character varying(50) DEFAULT '全职'::character varying NOT NULL,
    responsibilities text DEFAULT ''::text NOT NULL,
    requirements text DEFAULT ''::text NOT NULL,
    skills text DEFAULT ''::text NOT NULL,
    talent_profile text DEFAULT ''::text NOT NULL,
    warnings jsonb DEFAULT '[]'::jsonb NOT NULL,
    status character varying(32) NOT NULL,
    updated_by uuid NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    salary_range character varying(200) DEFAULT ''::character varying NOT NULL,
    benefits text DEFAULT ''::text NOT NULL,
    nice_to_haves text DEFAULT ''::text NOT NULL
);


--
-- Name: jd_run_events; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.jd_run_events (
    event_id bigint NOT NULL,
    run_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    recruitment_task_id uuid NOT NULL,
    event_type character varying(32) NOT NULL,
    data jsonb NOT NULL,
    created_at timestamp with time zone NOT NULL
);


--
-- Name: jd_run_events_event_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.jd_run_events_event_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: jd_run_events_event_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.jd_run_events_event_id_seq OWNED BY public.jd_run_events.event_id;


--
-- Name: jd_source_files; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.jd_source_files (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    recruitment_task_id uuid NOT NULL,
    file_asset_id uuid NOT NULL,
    extracted_text text DEFAULT ''::text NOT NULL,
    created_by uuid NOT NULL,
    created_at timestamp with time zone NOT NULL
);


--
-- Name: job_versions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.job_versions (
    id uuid NOT NULL,
    job_id uuid NOT NULL,
    version_number integer NOT NULL,
    snapshot jsonb NOT NULL,
    change_summary character varying(500) DEFAULT ''::character varying NOT NULL,
    created_by uuid NOT NULL,
    created_at timestamp with time zone NOT NULL,
    tenant_id uuid NOT NULL,
    status character varying(32) DEFAULT 'CONFIRMED'::character varying NOT NULL,
    source_ai_run_id uuid,
    confirmed_at timestamp with time zone
);


--
-- Name: jobs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.jobs (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    title character varying(200) NOT NULL,
    company_name character varying(200) NOT NULL,
    location character varying(200) NOT NULL,
    description text DEFAULT ''::text NOT NULL,
    requirements text DEFAULT ''::text NOT NULL,
    skills text DEFAULT ''::text NOT NULL,
    experience_level character varying(50) DEFAULT ''::character varying NOT NULL,
    education character varying(50) DEFAULT ''::character varying NOT NULL,
    job_type character varying(50) DEFAULT '全职'::character varying NOT NULL,
    status character varying(32) DEFAULT 'DRAFT'::character varying NOT NULL,
    created_by uuid NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    current_version_id uuid,
    source character varying(32) DEFAULT 'MANUAL'::character varying NOT NULL,
    lock_version bigint DEFAULT 0 NOT NULL,
    recruitment_task_id uuid,
    talent_profile text DEFAULT ''::text NOT NULL,
    warnings jsonb DEFAULT '[]'::jsonb NOT NULL,
    jd_draft_id uuid,
    salary_range character varying(200) DEFAULT ''::character varying NOT NULL,
    benefits text DEFAULT ''::text NOT NULL,
    nice_to_haves text DEFAULT ''::text NOT NULL
);


--
-- Name: messages; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.messages (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    conversation_id uuid NOT NULL,
    role character varying(24) NOT NULL,
    content text NOT NULL,
    capability character varying(64),
    sequence_number integer NOT NULL,
    created_by uuid,
    created_at timestamp with time zone NOT NULL
);


--
-- Name: notifications; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.notifications (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    type character varying(40) NOT NULL,
    title character varying(200) NOT NULL,
    content character varying(1000) NOT NULL,
    link character varying(300),
    read_at timestamp with time zone,
    created_at timestamp with time zone NOT NULL
);


--
-- Name: outbox_events; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.outbox_events (
    id uuid NOT NULL,
    aggregate_type character varying(100) NOT NULL,
    aggregate_id character varying(100) NOT NULL,
    event_type character varying(150) NOT NULL,
    payload jsonb NOT NULL,
    status character varying(32) NOT NULL,
    attempts integer DEFAULT 0 NOT NULL,
    next_attempt_at timestamp with time zone,
    created_at timestamp with time zone NOT NULL,
    sent_at timestamp with time zone
);


--
-- Name: recruitment_tasks; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.recruitment_tasks (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    title character varying(200) NOT NULL,
    initial_requirement text NOT NULL,
    status character varying(32) NOT NULL,
    current_stage character varying(32) NOT NULL,
    idempotency_key character varying(200) NOT NULL,
    request_hash character varying(64) NOT NULL,
    created_by uuid NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    feature_type character varying(32),
    linked_job_id uuid,
    linked_candidate_id uuid
);


--
-- Name: resume_files; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.resume_files (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    candidate_id uuid NOT NULL,
    file_asset_id uuid NOT NULL,
    status character varying(32) NOT NULL,
    error_code character varying(100),
    created_by uuid NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    provider_task_id uuid,
    parse_idempotency_key character varying(160),
    parse_attempts integer DEFAULT 0 NOT NULL,
    enterprise_pool_sync_enabled boolean DEFAULT false NOT NULL
);


--
-- Name: resume_parse_drafts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.resume_parse_drafts (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    recruitment_task_id uuid NOT NULL,
    source_ai_run_id uuid,
    resume_source_file_id uuid,
    content text DEFAULT ''::text NOT NULL,
    status character varying(16) DEFAULT 'DRAFT'::character varying NOT NULL,
    revision integer DEFAULT 1 NOT NULL,
    created_by uuid,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    updated_by uuid
);


--
-- Name: COLUMN resume_parse_drafts.updated_by; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.resume_parse_drafts.updated_by IS '最后一次更新该草稿版本的用户ID（手动保存/AI 回写时记录操作人，用于审计与版本对比）';


--
-- Name: resume_parse_versions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.resume_parse_versions (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    candidate_id uuid NOT NULL,
    resume_file_id uuid NOT NULL,
    version_number integer NOT NULL,
    schema_version character varying(32) NOT NULL,
    status character varying(32) NOT NULL,
    headline character varying(300) DEFAULT ''::character varying NOT NULL,
    years_experience integer DEFAULT 0 NOT NULL,
    highest_education character varying(100) DEFAULT ''::character varying NOT NULL,
    skills jsonb DEFAULT '[]'::jsonb NOT NULL,
    work_experience jsonb DEFAULT '[]'::jsonb NOT NULL,
    education_experience jsonb DEFAULT '[]'::jsonb NOT NULL,
    summary text DEFAULT ''::text NOT NULL,
    warnings jsonb DEFAULT '[]'::jsonb NOT NULL,
    raw_text text DEFAULT ''::text NOT NULL,
    created_at timestamp with time zone NOT NULL
);


--
-- Name: resume_source_files; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.resume_source_files (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    recruitment_task_id uuid NOT NULL,
    file_asset_id uuid NOT NULL,
    filename character varying(255) NOT NULL,
    media_type character varying(128) DEFAULT ''::character varying NOT NULL,
    size_bytes bigint DEFAULT 0 NOT NULL,
    extracted_text text DEFAULT ''::text NOT NULL,
    created_by uuid NOT NULL,
    created_at timestamp with time zone NOT NULL
);


--
-- Name: screening_plan_versions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.screening_plan_versions (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    plan_id uuid NOT NULL,
    version_number integer NOT NULL,
    rules_snapshot jsonb NOT NULL,
    created_by uuid NOT NULL,
    created_at timestamp with time zone NOT NULL
);


--
-- Name: screening_plans; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.screening_plans (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    job_id uuid NOT NULL,
    current_version_id uuid,
    name character varying(200) NOT NULL,
    status character varying(32) NOT NULL,
    created_by uuid NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    recruitment_task_id uuid
);


--
-- Name: screening_results; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.screening_results (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    run_item_id uuid NOT NULL,
    score integer NOT NULL,
    level character varying(32) NOT NULL,
    matched_points jsonb NOT NULL,
    unmatched_points jsonb NOT NULL,
    negotiable_points jsonb NOT NULL,
    missing_information jsonb NOT NULL,
    risks jsonb NOT NULL,
    evidence jsonb NOT NULL,
    result_snapshot jsonb NOT NULL,
    created_at timestamp with time zone NOT NULL,
    CONSTRAINT screening_results_score_check CHECK (((score >= 0) AND (score <= 100)))
);


--
-- Name: screening_run_items; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.screening_run_items (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    run_id uuid NOT NULL,
    candidate_id uuid NOT NULL,
    parse_version_id uuid NOT NULL,
    status character varying(32) NOT NULL,
    error_code character varying(100),
    attempt_number integer DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    source_run_item_id uuid,
    provider_task_id character varying(200)
);


--
-- Name: screening_runs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.screening_runs (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    job_id uuid NOT NULL,
    job_version_id uuid NOT NULL,
    plan_version_id uuid NOT NULL,
    provider_task_id character varying(200),
    status character varying(32) NOT NULL,
    progress integer DEFAULT 0 NOT NULL,
    scenario character varying(32) NOT NULL,
    idempotency_key character varying(200) NOT NULL,
    request_hash character varying(64) NOT NULL,
    created_by uuid NOT NULL,
    created_at timestamp with time zone NOT NULL,
    completed_at timestamp with time zone,
    parent_run_id uuid,
    root_run_id uuid,
    policy_decision jsonb DEFAULT '{}'::jsonb NOT NULL,
    execution_context jsonb DEFAULT '{}'::jsonb NOT NULL,
    recruitment_task_id uuid
);


--
-- Name: support_ticket_messages; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.support_ticket_messages (
    id uuid NOT NULL,
    ticket_id uuid NOT NULL,
    sender_type character varying(20) NOT NULL,
    sender_id uuid,
    sender_name character varying(80) NOT NULL,
    body text NOT NULL,
    created_at timestamp with time zone NOT NULL
);


--
-- Name: support_tickets; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.support_tickets (
    id uuid NOT NULL,
    ticket_number character varying(20) NOT NULL,
    creator_user_id uuid,
    creator_name character varying(80) NOT NULL,
    tenant_id uuid,
    title character varying(200) NOT NULL,
    category character varying(50) NOT NULL,
    priority character varying(20) DEFAULT 'NORMAL'::character varying NOT NULL,
    status character varying(24) DEFAULT 'OPEN'::character varying NOT NULL,
    assigned_to_id uuid,
    closed_at timestamp with time zone,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL
);


--
-- Name: trial_eligibilities; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.trial_eligibilities (
    id uuid NOT NULL,
    subject_type character varying(32) NOT NULL,
    subject_id uuid NOT NULL,
    policy_code character varying(64) NOT NULL,
    granted_at timestamp with time zone NOT NULL,
    tenant_id uuid NOT NULL
);


--
-- Name: jd_run_events event_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.jd_run_events ALTER COLUMN event_id SET DEFAULT nextval('public.jd_run_events_event_id_seq'::regclass);


--
-- Name: ai_runs ai_runs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ai_runs
    ADD CONSTRAINT ai_runs_pkey PRIMARY KEY (id);


--
-- Name: ai_runs ai_runs_tenant_id_idempotency_key_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ai_runs
    ADD CONSTRAINT ai_runs_tenant_id_idempotency_key_key UNIQUE (tenant_id, idempotency_key);


--
-- Name: audit_logs audit_logs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_logs
    ADD CONSTRAINT audit_logs_pkey PRIMARY KEY (id);


--
-- Name: candidates candidates_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.candidates
    ADD CONSTRAINT candidates_pkey PRIMARY KEY (id);


--
-- Name: conversations conversations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.conversations
    ADD CONSTRAINT conversations_pkey PRIMARY KEY (id);


--
-- Name: conversations conversations_recruitment_task_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.conversations
    ADD CONSTRAINT conversations_recruitment_task_id_key UNIQUE (recruitment_task_id);


--
-- Name: enterprise_job_pool_copies enterprise_job_pool_copies_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.enterprise_job_pool_copies
    ADD CONSTRAINT enterprise_job_pool_copies_pkey PRIMARY KEY (id);


--
-- Name: enterprise_job_pool_copies enterprise_job_pool_copies_tenant_id_source_job_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.enterprise_job_pool_copies
    ADD CONSTRAINT enterprise_job_pool_copies_tenant_id_source_job_id_key UNIQUE (tenant_id, source_job_id);


--
-- Name: enterprise_pool_attachment_assets enterprise_pool_attachment_as_tenant_id_talent_pool_copy_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.enterprise_pool_attachment_assets
    ADD CONSTRAINT enterprise_pool_attachment_as_tenant_id_talent_pool_copy_id_key UNIQUE (tenant_id, talent_pool_copy_id, source_file_asset_id);


--
-- Name: enterprise_pool_attachment_assets enterprise_pool_attachment_assets_object_key_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.enterprise_pool_attachment_assets
    ADD CONSTRAINT enterprise_pool_attachment_assets_object_key_key UNIQUE (object_key);


--
-- Name: enterprise_pool_attachment_assets enterprise_pool_attachment_assets_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.enterprise_pool_attachment_assets
    ADD CONSTRAINT enterprise_pool_attachment_assets_pkey PRIMARY KEY (id);


--
-- Name: enterprise_pool_export_logs enterprise_pool_export_logs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.enterprise_pool_export_logs
    ADD CONSTRAINT enterprise_pool_export_logs_pkey PRIMARY KEY (id);


--
-- Name: enterprise_talent_pool_copies enterprise_talent_pool_copies_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.enterprise_talent_pool_copies
    ADD CONSTRAINT enterprise_talent_pool_copies_pkey PRIMARY KEY (id);


--
-- Name: enterprise_talent_pool_copies enterprise_talent_pool_copies_tenant_id_source_candidate_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.enterprise_talent_pool_copies
    ADD CONSTRAINT enterprise_talent_pool_copies_tenant_id_source_candidate_id_key UNIQUE (tenant_id, source_candidate_id);


--
-- Name: file_assets file_assets_object_key_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.file_assets
    ADD CONSTRAINT file_assets_object_key_key UNIQUE (object_key);


--
-- Name: file_assets file_assets_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.file_assets
    ADD CONSTRAINT file_assets_pkey PRIMARY KEY (id);


--
-- Name: file_assets file_assets_tenant_id_sha256_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.file_assets
    ADD CONSTRAINT file_assets_tenant_id_sha256_key UNIQUE (tenant_id, sha256);


--
-- Name: foundation_async_probe foundation_async_probe_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.foundation_async_probe
    ADD CONSTRAINT foundation_async_probe_pkey PRIMARY KEY (id);


--
-- Name: idempotency_records idempotency_records_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.idempotency_records
    ADD CONSTRAINT idempotency_records_pkey PRIMARY KEY (id);


--
-- Name: interview_kit_versions interview_kit_versions_kit_id_version_no_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.interview_kit_versions
    ADD CONSTRAINT interview_kit_versions_kit_id_version_no_key UNIQUE (kit_id, version_no);


--
-- Name: interview_kit_versions interview_kit_versions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.interview_kit_versions
    ADD CONSTRAINT interview_kit_versions_pkey PRIMARY KEY (id);


--
-- Name: interview_kits interview_kits_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.interview_kits
    ADD CONSTRAINT interview_kits_pkey PRIMARY KEY (id);


--
-- Name: interview_questions interview_questions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.interview_questions
    ADD CONSTRAINT interview_questions_pkey PRIMARY KEY (id);


--
-- Name: jd_drafts jd_drafts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.jd_drafts
    ADD CONSTRAINT jd_drafts_pkey PRIMARY KEY (id);


--
-- Name: jd_run_events jd_run_events_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.jd_run_events
    ADD CONSTRAINT jd_run_events_pkey PRIMARY KEY (event_id);


--
-- Name: jd_source_files jd_source_files_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.jd_source_files
    ADD CONSTRAINT jd_source_files_pkey PRIMARY KEY (id);


--
-- Name: jd_source_files jd_source_files_recruitment_task_id_file_asset_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.jd_source_files
    ADD CONSTRAINT jd_source_files_recruitment_task_id_file_asset_id_key UNIQUE (recruitment_task_id, file_asset_id);


--
-- Name: job_versions job_versions_job_id_version_number_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.job_versions
    ADD CONSTRAINT job_versions_job_id_version_number_key UNIQUE (job_id, version_number);


--
-- Name: job_versions job_versions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.job_versions
    ADD CONSTRAINT job_versions_pkey PRIMARY KEY (id);


--
-- Name: jobs jobs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.jobs
    ADD CONSTRAINT jobs_pkey PRIMARY KEY (id);


--
-- Name: messages messages_conversation_id_sequence_number_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.messages
    ADD CONSTRAINT messages_conversation_id_sequence_number_key UNIQUE (conversation_id, sequence_number);


--
-- Name: messages messages_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.messages
    ADD CONSTRAINT messages_pkey PRIMARY KEY (id);


--
-- Name: notifications notifications_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notifications
    ADD CONSTRAINT notifications_pkey PRIMARY KEY (id);


--
-- Name: outbox_events outbox_events_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.outbox_events
    ADD CONSTRAINT outbox_events_pkey PRIMARY KEY (id);


--
-- Name: recruitment_tasks recruitment_tasks_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.recruitment_tasks
    ADD CONSTRAINT recruitment_tasks_pkey PRIMARY KEY (id);


--
-- Name: recruitment_tasks recruitment_tasks_tenant_id_idempotency_key_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.recruitment_tasks
    ADD CONSTRAINT recruitment_tasks_tenant_id_idempotency_key_key UNIQUE (tenant_id, idempotency_key);


--
-- Name: resume_files resume_files_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.resume_files
    ADD CONSTRAINT resume_files_pkey PRIMARY KEY (id);


--
-- Name: resume_files resume_files_tenant_id_file_asset_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.resume_files
    ADD CONSTRAINT resume_files_tenant_id_file_asset_id_key UNIQUE (tenant_id, file_asset_id);


--
-- Name: resume_parse_drafts resume_parse_drafts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.resume_parse_drafts
    ADD CONSTRAINT resume_parse_drafts_pkey PRIMARY KEY (id);


--
-- Name: resume_parse_drafts resume_parse_drafts_recruitment_task_id_revision_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.resume_parse_drafts
    ADD CONSTRAINT resume_parse_drafts_recruitment_task_id_revision_key UNIQUE (recruitment_task_id, revision);


--
-- Name: resume_parse_versions resume_parse_versions_candidate_id_version_number_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.resume_parse_versions
    ADD CONSTRAINT resume_parse_versions_candidate_id_version_number_key UNIQUE (candidate_id, version_number);


--
-- Name: resume_parse_versions resume_parse_versions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.resume_parse_versions
    ADD CONSTRAINT resume_parse_versions_pkey PRIMARY KEY (id);


--
-- Name: resume_source_files resume_source_files_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.resume_source_files
    ADD CONSTRAINT resume_source_files_pkey PRIMARY KEY (id);


--
-- Name: resume_source_files resume_source_files_recruitment_task_id_file_asset_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.resume_source_files
    ADD CONSTRAINT resume_source_files_recruitment_task_id_file_asset_id_key UNIQUE (recruitment_task_id, file_asset_id);


--
-- Name: screening_plan_versions screening_plan_versions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.screening_plan_versions
    ADD CONSTRAINT screening_plan_versions_pkey PRIMARY KEY (id);


--
-- Name: screening_plan_versions screening_plan_versions_plan_id_version_number_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.screening_plan_versions
    ADD CONSTRAINT screening_plan_versions_plan_id_version_number_key UNIQUE (plan_id, version_number);


--
-- Name: screening_plans screening_plans_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.screening_plans
    ADD CONSTRAINT screening_plans_pkey PRIMARY KEY (id);


--
-- Name: screening_results screening_results_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.screening_results
    ADD CONSTRAINT screening_results_pkey PRIMARY KEY (id);


--
-- Name: screening_results screening_results_run_item_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.screening_results
    ADD CONSTRAINT screening_results_run_item_id_key UNIQUE (run_item_id);


--
-- Name: screening_run_items screening_run_items_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.screening_run_items
    ADD CONSTRAINT screening_run_items_pkey PRIMARY KEY (id);


--
-- Name: screening_run_items screening_run_items_run_id_candidate_id_attempt_number_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.screening_run_items
    ADD CONSTRAINT screening_run_items_run_id_candidate_id_attempt_number_key UNIQUE (run_id, candidate_id, attempt_number);


--
-- Name: screening_runs screening_runs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.screening_runs
    ADD CONSTRAINT screening_runs_pkey PRIMARY KEY (id);


--
-- Name: screening_runs screening_runs_tenant_id_idempotency_key_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.screening_runs
    ADD CONSTRAINT screening_runs_tenant_id_idempotency_key_key UNIQUE (tenant_id, idempotency_key);


--
-- Name: support_ticket_messages support_ticket_messages_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.support_ticket_messages
    ADD CONSTRAINT support_ticket_messages_pkey PRIMARY KEY (id);


--
-- Name: support_tickets support_tickets_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.support_tickets
    ADD CONSTRAINT support_tickets_pkey PRIMARY KEY (id);


--
-- Name: support_tickets support_tickets_ticket_number_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.support_tickets
    ADD CONSTRAINT support_tickets_ticket_number_key UNIQUE (ticket_number);


--
-- Name: trial_eligibilities trial_eligibilities_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.trial_eligibilities
    ADD CONSTRAINT trial_eligibilities_pkey PRIMARY KEY (id);


--
-- Name: trial_eligibilities trial_eligibilities_subject_type_subject_id_policy_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.trial_eligibilities
    ADD CONSTRAINT trial_eligibilities_subject_type_subject_id_policy_code_key UNIQUE (subject_type, subject_id, policy_code);


--
-- Name: idempotency_records uk_idempotency_scope; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.idempotency_records
    ADD CONSTRAINT uk_idempotency_scope UNIQUE (tenant_id, actor_id, operation_type, idempotency_key);


--
-- Name: idx_ai_runs_task_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ai_runs_task_created ON public.ai_runs USING btree (recruitment_task_id, created_at DESC);


--
-- Name: idx_audit_scope; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_audit_scope ON public.audit_logs USING btree (tenant_id, created_at DESC);


--
-- Name: idx_candidates_profile_gin; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_candidates_profile_gin ON public.candidates USING gin (profile);


--
-- Name: idx_candidates_search_text; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_candidates_search_text ON public.candidates USING gin (to_tsvector('simple'::regconfig, COALESCE(search_text, ''::text)));


--
-- Name: idx_candidates_tenant_full_name_hash; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_candidates_tenant_full_name_hash ON public.candidates USING btree (tenant_id, full_name_search_hash) WHERE (full_name_search_hash IS NOT NULL);


--
-- Name: idx_candidates_tenant_phone_hash; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_candidates_tenant_phone_hash ON public.candidates USING btree (tenant_id, phone_search_hash) WHERE (phone_search_hash IS NOT NULL);


--
-- Name: idx_candidates_tenant_updated; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_candidates_tenant_updated ON public.candidates USING btree (tenant_id, updated_at DESC);


--
-- Name: idx_enterprise_job_pool_copies_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_enterprise_job_pool_copies_active ON public.enterprise_job_pool_copies USING btree (tenant_id, synchronized_at DESC) WHERE ((lifecycle_status)::text = 'ACTIVE'::text);


--
-- Name: idx_enterprise_job_pool_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_enterprise_job_pool_tenant ON public.enterprise_job_pool_copies USING btree (tenant_id, synchronized_at DESC);


--
-- Name: idx_enterprise_pool_attachment_copy; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_enterprise_pool_attachment_copy ON public.enterprise_pool_attachment_assets USING btree (talent_pool_copy_id, lifecycle_status);


--
-- Name: idx_enterprise_pool_export_logs_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_enterprise_pool_export_logs_tenant ON public.enterprise_pool_export_logs USING btree (tenant_id, created_at DESC);


--
-- Name: idx_enterprise_talent_pool_copies_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_enterprise_talent_pool_copies_active ON public.enterprise_talent_pool_copies USING btree (tenant_id, synchronized_at DESC) WHERE ((lifecycle_status)::text = 'ACTIVE'::text);


--
-- Name: idx_enterprise_talent_pool_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_enterprise_talent_pool_tenant ON public.enterprise_talent_pool_copies USING btree (tenant_id, synchronized_at DESC);


--
-- Name: idx_jd_drafts_task_updated; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_jd_drafts_task_updated ON public.jd_drafts USING btree (recruitment_task_id, updated_at);


--
-- Name: idx_jd_outbox_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_jd_outbox_pending ON public.outbox_events USING btree (status, next_attempt_at, created_at) WHERE ((event_type)::text = 'JD_RUN_REQUESTED'::text);


--
-- Name: idx_jd_run_events_replay; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_jd_run_events_replay ON public.jd_run_events USING btree (tenant_id, recruitment_task_id, event_id);


--
-- Name: idx_jd_source_files_task; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_jd_source_files_task ON public.jd_source_files USING btree (tenant_id, recruitment_task_id, created_at);


--
-- Name: idx_job_versions_job; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_job_versions_job ON public.job_versions USING btree (job_id, version_number DESC);


--
-- Name: idx_job_versions_tenant_job; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_job_versions_tenant_job ON public.job_versions USING btree (tenant_id, job_id, version_number DESC);


--
-- Name: idx_jobs_tenant_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_jobs_tenant_created ON public.jobs USING btree (tenant_id, created_at DESC);


--
-- Name: idx_jobs_tenant_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_jobs_tenant_status ON public.jobs USING btree (tenant_id, status);


--
-- Name: idx_jobs_tenant_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_jobs_tenant_tenant ON public.jobs USING btree (tenant_id);


--
-- Name: idx_messages_conversation_sequence; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_messages_conversation_sequence ON public.messages USING btree (conversation_id, sequence_number);


--
-- Name: idx_outbox_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_outbox_pending ON public.outbox_events USING btree (status, next_attempt_at, created_at);


--
-- Name: idx_parse_versions_tenant_candidate; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_parse_versions_tenant_candidate ON public.resume_parse_versions USING btree (tenant_id, candidate_id, version_number DESC);


--
-- Name: idx_recruitment_tasks_feature; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_recruitment_tasks_feature ON public.recruitment_tasks USING btree (tenant_id, feature_type, updated_at DESC);


--
-- Name: idx_recruitment_tasks_linked_candidate; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_recruitment_tasks_linked_candidate ON public.recruitment_tasks USING btree (tenant_id, linked_candidate_id, updated_at DESC) WHERE (linked_candidate_id IS NOT NULL);


--
-- Name: idx_recruitment_tasks_tenant_updated; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_recruitment_tasks_tenant_updated ON public.recruitment_tasks USING btree (tenant_id, updated_at DESC);


--
-- Name: idx_resume_parse_drafts_task; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_resume_parse_drafts_task ON public.resume_parse_drafts USING btree (tenant_id, recruitment_task_id, revision DESC);


--
-- Name: idx_resume_source_files_task; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_resume_source_files_task ON public.resume_source_files USING btree (tenant_id, recruitment_task_id, created_at);


--
-- Name: idx_screening_outbox_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_screening_outbox_pending ON public.outbox_events USING btree (event_type, status, next_attempt_at, created_at) WHERE ((event_type)::text = 'SCREENING_RUN_REQUESTED'::text);


--
-- Name: idx_screening_plans_recruitment_task; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_screening_plans_recruitment_task ON public.screening_plans USING btree (recruitment_task_id, updated_at DESC) WHERE (recruitment_task_id IS NOT NULL);


--
-- Name: idx_screening_run_items_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_screening_run_items_pending ON public.screening_run_items USING btree (run_id, status, created_at) WHERE ((status)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('PROCESSING'::character varying)::text]));


--
-- Name: idx_screening_runs_recruitment_task; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_screening_runs_recruitment_task ON public.screening_runs USING btree (recruitment_task_id, created_at DESC) WHERE (recruitment_task_id IS NOT NULL);


--
-- Name: idx_screening_runs_root; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_screening_runs_root ON public.screening_runs USING btree (root_run_id, created_at);


--
-- Name: idx_screening_runs_tenant_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_screening_runs_tenant_created ON public.screening_runs USING btree (tenant_id, created_at DESC);


--
-- Name: idx_ticket_messages; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ticket_messages ON public.support_ticket_messages USING btree (ticket_id, created_at);


--
-- Name: idx_tickets_creator; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tickets_creator ON public.support_tickets USING btree (creator_user_id, created_at);


--
-- Name: idx_tickets_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tickets_status ON public.support_tickets USING btree (status, created_at);


--
-- Name: ix_interview_kits_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_interview_kits_tenant ON public.interview_kits USING btree (tenant_id, created_at DESC);


--
-- Name: ix_notifications_user_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_notifications_user_created ON public.notifications USING btree (user_id, created_at DESC);


--
-- Name: uk_jobs_jd_draft; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_jobs_jd_draft ON public.jobs USING btree (jd_draft_id) WHERE (jd_draft_id IS NOT NULL);


--
-- Name: ux_resume_files_parse_idempotency; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ux_resume_files_parse_idempotency ON public.resume_files USING btree (tenant_id, parse_idempotency_key) WHERE (parse_idempotency_key IS NOT NULL);


--
-- Name: ai_runs ai_runs_recruitment_task_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ai_runs
    ADD CONSTRAINT ai_runs_recruitment_task_id_fkey FOREIGN KEY (recruitment_task_id) REFERENCES public.recruitment_tasks(id);


--
-- Name: conversations conversations_recruitment_task_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.conversations
    ADD CONSTRAINT conversations_recruitment_task_id_fkey FOREIGN KEY (recruitment_task_id) REFERENCES public.recruitment_tasks(id);


--
-- Name: enterprise_pool_attachment_assets enterprise_pool_attachment_assets_talent_pool_copy_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.enterprise_pool_attachment_assets
    ADD CONSTRAINT enterprise_pool_attachment_assets_talent_pool_copy_id_fkey FOREIGN KEY (talent_pool_copy_id) REFERENCES public.enterprise_talent_pool_copies(id);


--
-- Name: candidates fk_candidates_current_parse; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.candidates
    ADD CONSTRAINT fk_candidates_current_parse FOREIGN KEY (current_parse_version_id) REFERENCES public.resume_parse_versions(id);


--
-- Name: jobs fk_jobs_current_version; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.jobs
    ADD CONSTRAINT fk_jobs_current_version FOREIGN KEY (current_version_id) REFERENCES public.job_versions(id);


--
-- Name: screening_plans fk_screening_plan_version; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.screening_plans
    ADD CONSTRAINT fk_screening_plan_version FOREIGN KEY (current_version_id) REFERENCES public.screening_plan_versions(id);


--
-- Name: interview_kit_versions interview_kit_versions_kit_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.interview_kit_versions
    ADD CONSTRAINT interview_kit_versions_kit_id_fkey FOREIGN KEY (kit_id) REFERENCES public.interview_kits(id);


--
-- Name: interview_kits interview_kits_candidate_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.interview_kits
    ADD CONSTRAINT interview_kits_candidate_id_fkey FOREIGN KEY (candidate_id) REFERENCES public.candidates(id);


--
-- Name: interview_questions interview_questions_kit_version_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.interview_questions
    ADD CONSTRAINT interview_questions_kit_version_id_fkey FOREIGN KEY (kit_version_id) REFERENCES public.interview_kit_versions(id) ON DELETE CASCADE;


--
-- Name: jd_drafts jd_drafts_recruitment_task_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.jd_drafts
    ADD CONSTRAINT jd_drafts_recruitment_task_id_fkey FOREIGN KEY (recruitment_task_id) REFERENCES public.recruitment_tasks(id);


--
-- Name: jd_drafts jd_drafts_source_ai_run_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.jd_drafts
    ADD CONSTRAINT jd_drafts_source_ai_run_id_fkey FOREIGN KEY (source_ai_run_id) REFERENCES public.ai_runs(id);


--
-- Name: jd_run_events jd_run_events_recruitment_task_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.jd_run_events
    ADD CONSTRAINT jd_run_events_recruitment_task_id_fkey FOREIGN KEY (recruitment_task_id) REFERENCES public.recruitment_tasks(id);


--
-- Name: jd_run_events jd_run_events_run_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.jd_run_events
    ADD CONSTRAINT jd_run_events_run_id_fkey FOREIGN KEY (run_id) REFERENCES public.ai_runs(id) ON DELETE CASCADE;


--
-- Name: jd_source_files jd_source_files_file_asset_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.jd_source_files
    ADD CONSTRAINT jd_source_files_file_asset_id_fkey FOREIGN KEY (file_asset_id) REFERENCES public.file_assets(id);


--
-- Name: jd_source_files jd_source_files_recruitment_task_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.jd_source_files
    ADD CONSTRAINT jd_source_files_recruitment_task_id_fkey FOREIGN KEY (recruitment_task_id) REFERENCES public.recruitment_tasks(id) ON DELETE CASCADE;


--
-- Name: job_versions job_versions_job_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.job_versions
    ADD CONSTRAINT job_versions_job_id_fkey FOREIGN KEY (job_id) REFERENCES public.jobs(id) ON DELETE CASCADE;


--
-- Name: job_versions job_versions_source_ai_run_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.job_versions
    ADD CONSTRAINT job_versions_source_ai_run_id_fkey FOREIGN KEY (source_ai_run_id) REFERENCES public.ai_runs(id);


--
-- Name: jobs jobs_jd_draft_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.jobs
    ADD CONSTRAINT jobs_jd_draft_id_fkey FOREIGN KEY (jd_draft_id) REFERENCES public.jd_drafts(id);


--
-- Name: jobs jobs_recruitment_task_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.jobs
    ADD CONSTRAINT jobs_recruitment_task_id_fkey FOREIGN KEY (recruitment_task_id) REFERENCES public.recruitment_tasks(id);


--
-- Name: messages messages_conversation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.messages
    ADD CONSTRAINT messages_conversation_id_fkey FOREIGN KEY (conversation_id) REFERENCES public.conversations(id);


--
-- Name: recruitment_tasks recruitment_tasks_linked_candidate_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.recruitment_tasks
    ADD CONSTRAINT recruitment_tasks_linked_candidate_id_fkey FOREIGN KEY (linked_candidate_id) REFERENCES public.candidates(id) ON DELETE SET NULL;


--
-- Name: recruitment_tasks recruitment_tasks_linked_job_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.recruitment_tasks
    ADD CONSTRAINT recruitment_tasks_linked_job_id_fkey FOREIGN KEY (linked_job_id) REFERENCES public.jobs(id) ON DELETE SET NULL;


--
-- Name: resume_files resume_files_candidate_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.resume_files
    ADD CONSTRAINT resume_files_candidate_id_fkey FOREIGN KEY (candidate_id) REFERENCES public.candidates(id);


--
-- Name: resume_files resume_files_file_asset_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.resume_files
    ADD CONSTRAINT resume_files_file_asset_id_fkey FOREIGN KEY (file_asset_id) REFERENCES public.file_assets(id);


--
-- Name: resume_parse_drafts resume_parse_drafts_recruitment_task_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.resume_parse_drafts
    ADD CONSTRAINT resume_parse_drafts_recruitment_task_id_fkey FOREIGN KEY (recruitment_task_id) REFERENCES public.recruitment_tasks(id) ON DELETE CASCADE;


--
-- Name: resume_parse_drafts resume_parse_drafts_resume_source_file_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.resume_parse_drafts
    ADD CONSTRAINT resume_parse_drafts_resume_source_file_id_fkey FOREIGN KEY (resume_source_file_id) REFERENCES public.resume_source_files(id) ON DELETE SET NULL;


--
-- Name: resume_parse_drafts resume_parse_drafts_source_ai_run_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.resume_parse_drafts
    ADD CONSTRAINT resume_parse_drafts_source_ai_run_id_fkey FOREIGN KEY (source_ai_run_id) REFERENCES public.ai_runs(id) ON DELETE SET NULL;


--
-- Name: resume_parse_versions resume_parse_versions_candidate_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.resume_parse_versions
    ADD CONSTRAINT resume_parse_versions_candidate_id_fkey FOREIGN KEY (candidate_id) REFERENCES public.candidates(id);


--
-- Name: resume_parse_versions resume_parse_versions_resume_file_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.resume_parse_versions
    ADD CONSTRAINT resume_parse_versions_resume_file_id_fkey FOREIGN KEY (resume_file_id) REFERENCES public.resume_files(id);


--
-- Name: resume_source_files resume_source_files_file_asset_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.resume_source_files
    ADD CONSTRAINT resume_source_files_file_asset_id_fkey FOREIGN KEY (file_asset_id) REFERENCES public.file_assets(id);


--
-- Name: resume_source_files resume_source_files_recruitment_task_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.resume_source_files
    ADD CONSTRAINT resume_source_files_recruitment_task_id_fkey FOREIGN KEY (recruitment_task_id) REFERENCES public.recruitment_tasks(id) ON DELETE CASCADE;


--
-- Name: screening_plan_versions screening_plan_versions_plan_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.screening_plan_versions
    ADD CONSTRAINT screening_plan_versions_plan_id_fkey FOREIGN KEY (plan_id) REFERENCES public.screening_plans(id);


--
-- Name: screening_plans screening_plans_job_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.screening_plans
    ADD CONSTRAINT screening_plans_job_id_fkey FOREIGN KEY (job_id) REFERENCES public.jobs(id);


--
-- Name: screening_plans screening_plans_recruitment_task_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.screening_plans
    ADD CONSTRAINT screening_plans_recruitment_task_id_fkey FOREIGN KEY (recruitment_task_id) REFERENCES public.recruitment_tasks(id);


--
-- Name: screening_results screening_results_run_item_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.screening_results
    ADD CONSTRAINT screening_results_run_item_id_fkey FOREIGN KEY (run_item_id) REFERENCES public.screening_run_items(id);


--
-- Name: screening_run_items screening_run_items_candidate_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.screening_run_items
    ADD CONSTRAINT screening_run_items_candidate_id_fkey FOREIGN KEY (candidate_id) REFERENCES public.candidates(id);


--
-- Name: screening_run_items screening_run_items_parse_version_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.screening_run_items
    ADD CONSTRAINT screening_run_items_parse_version_id_fkey FOREIGN KEY (parse_version_id) REFERENCES public.resume_parse_versions(id);


--
-- Name: screening_run_items screening_run_items_run_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.screening_run_items
    ADD CONSTRAINT screening_run_items_run_id_fkey FOREIGN KEY (run_id) REFERENCES public.screening_runs(id);


--
-- Name: screening_run_items screening_run_items_source_run_item_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.screening_run_items
    ADD CONSTRAINT screening_run_items_source_run_item_id_fkey FOREIGN KEY (source_run_item_id) REFERENCES public.screening_run_items(id);


--
-- Name: screening_runs screening_runs_job_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.screening_runs
    ADD CONSTRAINT screening_runs_job_id_fkey FOREIGN KEY (job_id) REFERENCES public.jobs(id);


--
-- Name: screening_runs screening_runs_job_version_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.screening_runs
    ADD CONSTRAINT screening_runs_job_version_id_fkey FOREIGN KEY (job_version_id) REFERENCES public.job_versions(id);


--
-- Name: screening_runs screening_runs_parent_run_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.screening_runs
    ADD CONSTRAINT screening_runs_parent_run_id_fkey FOREIGN KEY (parent_run_id) REFERENCES public.screening_runs(id);


--
-- Name: screening_runs screening_runs_plan_version_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.screening_runs
    ADD CONSTRAINT screening_runs_plan_version_id_fkey FOREIGN KEY (plan_version_id) REFERENCES public.screening_plan_versions(id);


--
-- Name: screening_runs screening_runs_recruitment_task_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.screening_runs
    ADD CONSTRAINT screening_runs_recruitment_task_id_fkey FOREIGN KEY (recruitment_task_id) REFERENCES public.recruitment_tasks(id);


--
-- Name: screening_runs screening_runs_root_run_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.screening_runs
    ADD CONSTRAINT screening_runs_root_run_id_fkey FOREIGN KEY (root_run_id) REFERENCES public.screening_runs(id);


--
-- Name: support_ticket_messages support_ticket_messages_ticket_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.support_ticket_messages
    ADD CONSTRAINT support_ticket_messages_ticket_id_fkey FOREIGN KEY (ticket_id) REFERENCES public.support_tickets(id);


--
-- PostgreSQL database dump complete
--
