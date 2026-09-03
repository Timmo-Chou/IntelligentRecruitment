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


--
-- Name: enforce_workspace_company_scope(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.enforce_workspace_company_scope() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    expected_company_id UUID;
BEGIN
    SELECT company_id INTO expected_company_id FROM workspaces WHERE id = NEW.workspace_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'workspace does not exist';
    END IF;
    IF NEW.company_id IS DISTINCT FROM expected_company_id THEN
        RAISE EXCEPTION 'company_id does not match workspace scope';
    END IF;
    RETURN NEW;
END;
$$;


--
-- Name: fn_enforce_recruitment_task_linked_candidate_scope(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.fn_enforce_recruitment_task_linked_candidate_scope() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.linked_candidate_id IS NOT NULL THEN
        NEW.company_id := COALESCE(NEW.company_id, (SELECT company_id FROM workspaces WHERE id = NEW.workspace_id));
        IF EXISTS (
            SELECT 1 FROM candidates c
            WHERE c.id = NEW.linked_candidate_id
              AND (c.workspace_id <> NEW.workspace_id OR c.company_id IS DISTINCT FROM NEW.company_id)
        ) THEN
            RAISE EXCEPTION 'Linked candidate % does not belong to workspace %', NEW.linked_candidate_id, NEW.workspace_id;
        END IF;
    END IF;
    RETURN NEW;
END;
$$;


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: access_tokens; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.access_tokens (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    token_hash character varying(64) NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    revoked_at timestamp with time zone,
    created_at timestamp with time zone NOT NULL
);


--
-- Name: ai_runs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ai_runs (
    id uuid NOT NULL,
    company_id uuid,
    workspace_id uuid NOT NULL,
    recruitment_task_id uuid NOT NULL,
    capability character varying(64) NOT NULL,
    provider_task_id character varying(200),
    status character varying(32) NOT NULL,
    progress integer DEFAULT 0 NOT NULL,
    attempt_number integer NOT NULL,
    idempotency_key character varying(200) NOT NULL,
    input_hash character varying(64) NOT NULL,
    pricing_version character varying(64) NOT NULL,
    estimated_amount_minor bigint NOT NULL,
    settled_amount_minor bigint DEFAULT 0 NOT NULL,
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
    company_id uuid,
    workspace_id uuid,
    action character varying(100) NOT NULL,
    resource_type character varying(80) NOT NULL,
    resource_id character varying(100) NOT NULL,
    metadata jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone NOT NULL
);


--
-- Name: billing_accounts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.billing_accounts (
    id uuid NOT NULL,
    workspace_id uuid NOT NULL,
    currency character varying(3) NOT NULL,
    available_amount_minor bigint DEFAULT 0 NOT NULL,
    reserved_amount_minor bigint DEFAULT 0 NOT NULL,
    status character varying(24) NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL
);


--
-- Name: billing_ledger_entries; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.billing_ledger_entries (
    id uuid NOT NULL,
    billing_account_id uuid NOT NULL,
    workspace_id uuid NOT NULL,
    credit_lot_id uuid,
    entry_type character varying(32) NOT NULL,
    amount_minor bigint NOT NULL,
    business_reference character varying(160) NOT NULL,
    idempotency_key character varying(160) NOT NULL,
    operator_user_id uuid,
    reason character varying(500),
    created_at timestamp with time zone NOT NULL
);


--
-- Name: billing_reservation_allocations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.billing_reservation_allocations (
    id uuid NOT NULL,
    reservation_id uuid NOT NULL,
    credit_lot_id uuid NOT NULL,
    reserved_amount_minor bigint NOT NULL,
    settled_amount_minor bigint DEFAULT 0 NOT NULL,
    released_amount_minor bigint DEFAULT 0 NOT NULL
);


--
-- Name: billing_reservations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.billing_reservations (
    id uuid NOT NULL,
    billing_account_id uuid NOT NULL,
    workspace_id uuid NOT NULL,
    business_reference character varying(160) NOT NULL,
    reserved_amount_minor bigint NOT NULL,
    settled_amount_minor bigint DEFAULT 0 NOT NULL,
    released_amount_minor bigint DEFAULT 0 NOT NULL,
    status character varying(24) NOT NULL,
    created_by uuid NOT NULL,
    created_at timestamp with time zone NOT NULL,
    completed_at timestamp with time zone
);


--
-- Name: candidates; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.candidates (
    id uuid NOT NULL,
    company_id uuid,
    workspace_id uuid NOT NULL,
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
-- Name: companies; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.companies (
    id uuid NOT NULL,
    legal_name character varying(200) NOT NULL,
    display_name character varying(120) NOT NULL,
    credit_code_hash character varying(64) NOT NULL,
    credit_code_masked character varying(32) NOT NULL,
    verification_status character varying(24) NOT NULL,
    management_status character varying(24) NOT NULL,
    owner_user_id uuid,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL
);


--
-- Name: company_memberships; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.company_memberships (
    id uuid NOT NULL,
    company_id uuid NOT NULL,
    user_id uuid NOT NULL,
    role character varying(32) NOT NULL,
    status character varying(24) NOT NULL,
    joined_at timestamp with time zone NOT NULL
);


--
-- Name: company_verification_requests; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.company_verification_requests (
    id uuid NOT NULL,
    applicant_user_id uuid NOT NULL,
    company_id uuid,
    request_type character varying(24) DEFAULT 'CREATE'::character varying NOT NULL,
    legal_name character varying(200) NOT NULL,
    display_name character varying(120) NOT NULL,
    credit_code_hash character varying(64) NOT NULL,
    credit_code_masked character varying(32) NOT NULL,
    license_reference character varying(500) NOT NULL,
    first_workspace_name character varying(120) NOT NULL,
    status character varying(24) NOT NULL,
    reviewed_by character varying(100),
    reviewed_at timestamp with time zone,
    rejection_reason character varying(500),
    created_at timestamp with time zone NOT NULL
);


--
-- Name: conversations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.conversations (
    id uuid NOT NULL,
    company_id uuid,
    workspace_id uuid NOT NULL,
    recruitment_task_id uuid NOT NULL,
    status character varying(32) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL
);


--
-- Name: credit_lots; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.credit_lots (
    id uuid NOT NULL,
    billing_account_id uuid NOT NULL,
    source_type character varying(32) NOT NULL,
    original_amount_minor bigint NOT NULL,
    available_amount_minor bigint NOT NULL,
    issued_at timestamp with time zone NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    status character varying(24) NOT NULL
);


--
-- Name: file_assets; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.file_assets (
    id uuid NOT NULL,
    company_id uuid,
    workspace_id uuid NOT NULL,
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
    workspace_id uuid NOT NULL,
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
    company_id uuid,
    workspace_id uuid NOT NULL,
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
    company_id uuid,
    workspace_id uuid NOT NULL,
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
    company_id uuid,
    workspace_id uuid NOT NULL,
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
    company_id uuid,
    workspace_id uuid NOT NULL,
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
    company_id uuid,
    workspace_id uuid NOT NULL,
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
    company_id uuid,
    workspace_id uuid NOT NULL,
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
    company_id uuid,
    workspace_id uuid NOT NULL,
    status character varying(32) DEFAULT 'CONFIRMED'::character varying NOT NULL,
    source_ai_run_id uuid,
    confirmed_at timestamp with time zone
);


--
-- Name: jobs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.jobs (
    id uuid NOT NULL,
    workspace_id uuid NOT NULL,
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
    company_id uuid,
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
-- Name: membership_applications; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.membership_applications (
    id uuid NOT NULL,
    company_id uuid NOT NULL,
    applicant_user_id uuid NOT NULL,
    evidence character varying(500) NOT NULL,
    status character varying(24) NOT NULL,
    reviewed_by_platform_user character varying(100),
    reviewed_at timestamp with time zone,
    review_reason character varying(500),
    created_at timestamp with time zone NOT NULL,
    reviewed_by_user_id uuid
);


--
-- Name: membership_invitations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.membership_invitations (
    id uuid NOT NULL,
    target_type character varying(24) NOT NULL,
    target_id uuid NOT NULL,
    phone_hash character varying(64) NOT NULL,
    role character varying(32) NOT NULL,
    token_hash character varying(64) NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    status character varying(24) NOT NULL,
    created_by uuid NOT NULL,
    accepted_by uuid,
    accepted_at timestamp with time zone,
    created_at timestamp with time zone NOT NULL
);


--
-- Name: messages; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.messages (
    id uuid NOT NULL,
    company_id uuid,
    workspace_id uuid NOT NULL,
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
-- Name: personal_identities; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.personal_identities (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    identity_hash character varying(64) NOT NULL,
    real_name_masked character varying(80) NOT NULL,
    verification_status character varying(24) NOT NULL,
    reviewed_by character varying(100),
    reviewed_at timestamp with time zone,
    rejection_reason character varying(500),
    created_at timestamp with time zone NOT NULL
);


--
-- Name: platform_admins; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_admins (
    id uuid NOT NULL,
    user_id uuid,
    display_name character varying(80) NOT NULL,
    role character varying(24) DEFAULT 'PLATFORM_OPERATOR'::character varying NOT NULL,
    status character varying(24) DEFAULT 'ACTIVE'::character varying NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    key_hash character varying(64)
);


--
-- Name: platform_menus; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_menus (
    id uuid NOT NULL,
    parent_id uuid,
    code character varying(50) NOT NULL,
    display_name character varying(80) NOT NULL,
    icon character varying(50),
    path character varying(200),
    permission_code character varying(80),
    sort_order integer DEFAULT 0 NOT NULL,
    is_visible boolean DEFAULT true NOT NULL,
    visible_to_operator boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL
);


--
-- Name: pricing_items; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.pricing_items (
    id uuid NOT NULL,
    code character varying(64) NOT NULL,
    name character varying(120) NOT NULL,
    description character varying(400),
    billing_unit character varying(32) NOT NULL,
    unit_price_minor bigint NOT NULL,
    currency character varying(8) DEFAULT 'CNY'::character varying NOT NULL,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL
);


--
-- Name: recharge_orders; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.recharge_orders (
    id uuid NOT NULL,
    order_no character varying(64) NOT NULL,
    billing_account_id uuid NOT NULL,
    workspace_id uuid NOT NULL,
    created_by uuid NOT NULL,
    payer_name character varying(200) NOT NULL,
    payment_method character varying(32) NOT NULL,
    amount_minor bigint NOT NULL,
    status character varying(32) NOT NULL,
    provider_trade_no character varying(128),
    paid_at timestamp with time zone,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT recharge_orders_amount_minor_check CHECK (((amount_minor >= 1000) AND (amount_minor <= 500000)))
);


--
-- Name: recharge_receiving_accounts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.recharge_receiving_accounts (
    id uuid NOT NULL,
    bank_name character varying(200) NOT NULL,
    beneficiary_name character varying(200) NOT NULL,
    account_number character varying(100) NOT NULL,
    contact_phone character varying(80),
    contact_email character varying(200),
    status character varying(24) DEFAULT 'ACTIVE'::character varying NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL
);


--
-- Name: recruitment_tasks; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.recruitment_tasks (
    id uuid NOT NULL,
    company_id uuid,
    workspace_id uuid NOT NULL,
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
-- Name: refresh_sessions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.refresh_sessions (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    token_hash character varying(64) NOT NULL,
    device_info character varying(300),
    expires_at timestamp with time zone NOT NULL,
    revoked_at timestamp with time zone,
    rotated_from_id uuid,
    created_at timestamp with time zone NOT NULL
);


--
-- Name: resume_files; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.resume_files (
    id uuid NOT NULL,
    company_id uuid,
    workspace_id uuid NOT NULL,
    candidate_id uuid NOT NULL,
    file_asset_id uuid NOT NULL,
    status character varying(32) NOT NULL,
    error_code character varying(100),
    created_by uuid NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL
);


--
-- Name: resume_parse_drafts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.resume_parse_drafts (
    id uuid NOT NULL,
    company_id uuid,
    workspace_id uuid NOT NULL,
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
    company_id uuid,
    workspace_id uuid NOT NULL,
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
    company_id uuid,
    workspace_id uuid NOT NULL,
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
    company_id uuid,
    workspace_id uuid NOT NULL,
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
    company_id uuid,
    workspace_id uuid NOT NULL,
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
-- Name: screening_quotes; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.screening_quotes (
    id uuid NOT NULL,
    company_id uuid,
    workspace_id uuid NOT NULL,
    plan_version_id uuid NOT NULL,
    candidate_ids_hash character varying(64) NOT NULL,
    candidate_count integer NOT NULL,
    pricing_version character varying(80) NOT NULL,
    unit_price_minor bigint NOT NULL,
    estimated_amount_minor bigint NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    created_by uuid NOT NULL,
    created_at timestamp with time zone NOT NULL,
    job_version_id uuid,
    candidate_versions_hash character varying(64),
    consumed_at timestamp with time zone,
    consumed_by_run_id uuid,
    CONSTRAINT screening_quotes_candidate_count_check CHECK ((candidate_count > 0)),
    CONSTRAINT screening_quotes_estimated_amount_minor_check CHECK ((estimated_amount_minor >= 0)),
    CONSTRAINT screening_quotes_unit_price_minor_check CHECK ((unit_price_minor >= 0))
);


--
-- Name: screening_results; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.screening_results (
    id uuid NOT NULL,
    company_id uuid,
    workspace_id uuid NOT NULL,
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
    company_id uuid,
    workspace_id uuid NOT NULL,
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
    company_id uuid,
    workspace_id uuid NOT NULL,
    job_id uuid NOT NULL,
    job_version_id uuid NOT NULL,
    plan_version_id uuid NOT NULL,
    provider_task_id character varying(200),
    status character varying(32) NOT NULL,
    progress integer DEFAULT 0 NOT NULL,
    scenario character varying(32) NOT NULL,
    pricing_version character varying(64) NOT NULL,
    unit_price_minor bigint NOT NULL,
    estimated_amount_minor bigint NOT NULL,
    settled_amount_minor bigint DEFAULT 0 NOT NULL,
    idempotency_key character varying(200) NOT NULL,
    request_hash character varying(64) NOT NULL,
    created_by uuid NOT NULL,
    created_at timestamp with time zone NOT NULL,
    completed_at timestamp with time zone,
    quote_id uuid,
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
    company_id uuid,
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
    workspace_id uuid NOT NULL
);


--
-- Name: users; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.users (
    id uuid NOT NULL,
    phone_hash character varying(64) NOT NULL,
    phone_last_four character varying(4) NOT NULL,
    display_name character varying(80),
    status character varying(24) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    password_hash character varying(100),
    password_set_at timestamp with time zone
);


--
-- Name: verification_challenges; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.verification_challenges (
    id uuid NOT NULL,
    phone_hash character varying(64) NOT NULL,
    purpose character varying(32) NOT NULL,
    code_hash character varying(64) NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    attempt_count integer DEFAULT 0 NOT NULL,
    consumed_at timestamp with time zone,
    created_at timestamp with time zone NOT NULL
);


--
-- Name: workspace_memberships; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.workspace_memberships (
    id uuid NOT NULL,
    workspace_id uuid NOT NULL,
    user_id uuid NOT NULL,
    role character varying(32) NOT NULL,
    status character varying(24) NOT NULL,
    joined_at timestamp with time zone NOT NULL
);


--
-- Name: workspaces; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.workspaces (
    id uuid NOT NULL,
    company_id uuid,
    type character varying(24) NOT NULL,
    name character varying(120) NOT NULL,
    owner_user_id uuid NOT NULL,
    status character varying(24) NOT NULL,
    created_by uuid NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT ck_workspace_company CHECK (((((type)::text = 'PERSONAL'::text) AND (company_id IS NULL)) OR (((type)::text = 'COMPANY'::text) AND (company_id IS NOT NULL))))
);


--
-- Name: jd_run_events event_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.jd_run_events ALTER COLUMN event_id SET DEFAULT nextval('public.jd_run_events_event_id_seq'::regclass);


--
-- Name: access_tokens access_tokens_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.access_tokens
    ADD CONSTRAINT access_tokens_pkey PRIMARY KEY (id);


--
-- Name: access_tokens access_tokens_token_hash_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.access_tokens
    ADD CONSTRAINT access_tokens_token_hash_key UNIQUE (token_hash);


--
-- Name: ai_runs ai_runs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ai_runs
    ADD CONSTRAINT ai_runs_pkey PRIMARY KEY (id);


--
-- Name: ai_runs ai_runs_workspace_id_idempotency_key_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ai_runs
    ADD CONSTRAINT ai_runs_workspace_id_idempotency_key_key UNIQUE (workspace_id, idempotency_key);


--
-- Name: audit_logs audit_logs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_logs
    ADD CONSTRAINT audit_logs_pkey PRIMARY KEY (id);


--
-- Name: billing_accounts billing_accounts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.billing_accounts
    ADD CONSTRAINT billing_accounts_pkey PRIMARY KEY (id);


--
-- Name: billing_accounts billing_accounts_workspace_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.billing_accounts
    ADD CONSTRAINT billing_accounts_workspace_id_key UNIQUE (workspace_id);


--
-- Name: billing_ledger_entries billing_ledger_entries_billing_account_id_idempotency_key_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.billing_ledger_entries
    ADD CONSTRAINT billing_ledger_entries_billing_account_id_idempotency_key_key UNIQUE (billing_account_id, idempotency_key);


--
-- Name: billing_ledger_entries billing_ledger_entries_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.billing_ledger_entries
    ADD CONSTRAINT billing_ledger_entries_pkey PRIMARY KEY (id);


--
-- Name: billing_reservation_allocations billing_reservation_allocation_reservation_id_credit_lot_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.billing_reservation_allocations
    ADD CONSTRAINT billing_reservation_allocation_reservation_id_credit_lot_id_key UNIQUE (reservation_id, credit_lot_id);


--
-- Name: billing_reservation_allocations billing_reservation_allocations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.billing_reservation_allocations
    ADD CONSTRAINT billing_reservation_allocations_pkey PRIMARY KEY (id);


--
-- Name: billing_reservations billing_reservations_billing_account_id_business_reference_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.billing_reservations
    ADD CONSTRAINT billing_reservations_billing_account_id_business_reference_key UNIQUE (billing_account_id, business_reference);


--
-- Name: billing_reservations billing_reservations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.billing_reservations
    ADD CONSTRAINT billing_reservations_pkey PRIMARY KEY (id);


--
-- Name: candidates candidates_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.candidates
    ADD CONSTRAINT candidates_pkey PRIMARY KEY (id);


--
-- Name: companies companies_credit_code_hash_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.companies
    ADD CONSTRAINT companies_credit_code_hash_key UNIQUE (credit_code_hash);


--
-- Name: companies companies_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.companies
    ADD CONSTRAINT companies_pkey PRIMARY KEY (id);


--
-- Name: company_memberships company_memberships_company_id_user_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.company_memberships
    ADD CONSTRAINT company_memberships_company_id_user_id_key UNIQUE (company_id, user_id);


--
-- Name: company_memberships company_memberships_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.company_memberships
    ADD CONSTRAINT company_memberships_pkey PRIMARY KEY (id);


--
-- Name: company_verification_requests company_verification_requests_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.company_verification_requests
    ADD CONSTRAINT company_verification_requests_pkey PRIMARY KEY (id);


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
-- Name: credit_lots credit_lots_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.credit_lots
    ADD CONSTRAINT credit_lots_pkey PRIMARY KEY (id);


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
-- Name: file_assets file_assets_workspace_id_sha256_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.file_assets
    ADD CONSTRAINT file_assets_workspace_id_sha256_key UNIQUE (workspace_id, sha256);


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
-- Name: membership_applications membership_applications_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.membership_applications
    ADD CONSTRAINT membership_applications_pkey PRIMARY KEY (id);


--
-- Name: membership_invitations membership_invitations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.membership_invitations
    ADD CONSTRAINT membership_invitations_pkey PRIMARY KEY (id);


--
-- Name: membership_invitations membership_invitations_token_hash_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.membership_invitations
    ADD CONSTRAINT membership_invitations_token_hash_key UNIQUE (token_hash);


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
-- Name: personal_identities personal_identities_identity_hash_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.personal_identities
    ADD CONSTRAINT personal_identities_identity_hash_key UNIQUE (identity_hash);


--
-- Name: personal_identities personal_identities_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.personal_identities
    ADD CONSTRAINT personal_identities_pkey PRIMARY KEY (id);


--
-- Name: personal_identities personal_identities_user_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.personal_identities
    ADD CONSTRAINT personal_identities_user_id_key UNIQUE (user_id);


--
-- Name: platform_admins platform_admins_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_admins
    ADD CONSTRAINT platform_admins_pkey PRIMARY KEY (id);


--
-- Name: platform_menus platform_menus_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_menus
    ADD CONSTRAINT platform_menus_code_key UNIQUE (code);


--
-- Name: platform_menus platform_menus_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_menus
    ADD CONSTRAINT platform_menus_pkey PRIMARY KEY (id);


--
-- Name: pricing_items pricing_items_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pricing_items
    ADD CONSTRAINT pricing_items_code_key UNIQUE (code);


--
-- Name: pricing_items pricing_items_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pricing_items
    ADD CONSTRAINT pricing_items_pkey PRIMARY KEY (id);


--
-- Name: recharge_orders recharge_orders_order_no_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.recharge_orders
    ADD CONSTRAINT recharge_orders_order_no_key UNIQUE (order_no);


--
-- Name: recharge_orders recharge_orders_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.recharge_orders
    ADD CONSTRAINT recharge_orders_pkey PRIMARY KEY (id);


--
-- Name: recharge_receiving_accounts recharge_receiving_accounts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.recharge_receiving_accounts
    ADD CONSTRAINT recharge_receiving_accounts_pkey PRIMARY KEY (id);


--
-- Name: recruitment_tasks recruitment_tasks_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.recruitment_tasks
    ADD CONSTRAINT recruitment_tasks_pkey PRIMARY KEY (id);


--
-- Name: recruitment_tasks recruitment_tasks_workspace_id_idempotency_key_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.recruitment_tasks
    ADD CONSTRAINT recruitment_tasks_workspace_id_idempotency_key_key UNIQUE (workspace_id, idempotency_key);


--
-- Name: refresh_sessions refresh_sessions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.refresh_sessions
    ADD CONSTRAINT refresh_sessions_pkey PRIMARY KEY (id);


--
-- Name: refresh_sessions refresh_sessions_token_hash_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.refresh_sessions
    ADD CONSTRAINT refresh_sessions_token_hash_key UNIQUE (token_hash);


--
-- Name: resume_files resume_files_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.resume_files
    ADD CONSTRAINT resume_files_pkey PRIMARY KEY (id);


--
-- Name: resume_files resume_files_workspace_id_file_asset_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.resume_files
    ADD CONSTRAINT resume_files_workspace_id_file_asset_id_key UNIQUE (workspace_id, file_asset_id);


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
-- Name: screening_quotes screening_quotes_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.screening_quotes
    ADD CONSTRAINT screening_quotes_pkey PRIMARY KEY (id);


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
-- Name: screening_runs screening_runs_workspace_id_idempotency_key_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.screening_runs
    ADD CONSTRAINT screening_runs_workspace_id_idempotency_key_key UNIQUE (workspace_id, idempotency_key);


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
    ADD CONSTRAINT uk_idempotency_scope UNIQUE (workspace_id, actor_id, operation_type, idempotency_key);


--
-- Name: users users_phone_hash_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_phone_hash_key UNIQUE (phone_hash);


--
-- Name: users users_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_pkey PRIMARY KEY (id);


--
-- Name: verification_challenges verification_challenges_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.verification_challenges
    ADD CONSTRAINT verification_challenges_pkey PRIMARY KEY (id);


--
-- Name: workspace_memberships workspace_memberships_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.workspace_memberships
    ADD CONSTRAINT workspace_memberships_pkey PRIMARY KEY (id);


--
-- Name: workspace_memberships workspace_memberships_workspace_id_user_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.workspace_memberships
    ADD CONSTRAINT workspace_memberships_workspace_id_user_id_key UNIQUE (workspace_id, user_id);


--
-- Name: workspaces workspaces_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.workspaces
    ADD CONSTRAINT workspaces_pkey PRIMARY KEY (id);


--
-- Name: idx_ai_runs_task_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ai_runs_task_created ON public.ai_runs USING btree (recruitment_task_id, created_at DESC);


--
-- Name: idx_audit_scope; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_audit_scope ON public.audit_logs USING btree (company_id, workspace_id, created_at DESC);


--
-- Name: idx_billing_ledger_account_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_billing_ledger_account_created ON public.billing_ledger_entries USING btree (billing_account_id, created_at DESC);


--
-- Name: idx_candidates_profile_gin; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_candidates_profile_gin ON public.candidates USING gin (profile);


--
-- Name: idx_candidates_search_text; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_candidates_search_text ON public.candidates USING gin (to_tsvector('simple'::regconfig, COALESCE(search_text, ''::text)));


--
-- Name: idx_candidates_workspace_full_name_hash; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_candidates_workspace_full_name_hash ON public.candidates USING btree (workspace_id, full_name_search_hash) WHERE (full_name_search_hash IS NOT NULL);


--
-- Name: idx_candidates_workspace_phone_hash; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_candidates_workspace_phone_hash ON public.candidates USING btree (workspace_id, phone_search_hash) WHERE (phone_search_hash IS NOT NULL);


--
-- Name: idx_candidates_workspace_updated; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_candidates_workspace_updated ON public.candidates USING btree (workspace_id, updated_at DESC);


--
-- Name: idx_company_verifications_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_company_verifications_status ON public.company_verification_requests USING btree (status, created_at);


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

CREATE INDEX idx_jd_run_events_replay ON public.jd_run_events USING btree (workspace_id, recruitment_task_id, event_id);


--
-- Name: idx_jd_source_files_task; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_jd_source_files_task ON public.jd_source_files USING btree (workspace_id, recruitment_task_id, created_at);


--
-- Name: idx_job_versions_job; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_job_versions_job ON public.job_versions USING btree (job_id, version_number DESC);


--
-- Name: idx_job_versions_workspace_job; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_job_versions_workspace_job ON public.job_versions USING btree (workspace_id, job_id, version_number DESC);


--
-- Name: idx_jobs_company_workspace; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_jobs_company_workspace ON public.jobs USING btree (company_id, workspace_id);


--
-- Name: idx_jobs_workspace_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_jobs_workspace_created ON public.jobs USING btree (workspace_id, created_at DESC);


--
-- Name: idx_jobs_workspace_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_jobs_workspace_status ON public.jobs USING btree (workspace_id, status);


--
-- Name: idx_messages_conversation_sequence; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_messages_conversation_sequence ON public.messages USING btree (conversation_id, sequence_number);


--
-- Name: idx_outbox_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_outbox_pending ON public.outbox_events USING btree (status, next_attempt_at, created_at);


--
-- Name: idx_parse_versions_workspace_candidate; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_parse_versions_workspace_candidate ON public.resume_parse_versions USING btree (workspace_id, candidate_id, version_number DESC);


--
-- Name: idx_pricing_items_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pricing_items_status ON public.pricing_items USING btree (status);


--
-- Name: idx_recharge_orders_workspace_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_recharge_orders_workspace_created ON public.recharge_orders USING btree (workspace_id, created_at DESC);


--
-- Name: idx_recruitment_tasks_feature; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_recruitment_tasks_feature ON public.recruitment_tasks USING btree (workspace_id, feature_type, updated_at DESC);


--
-- Name: idx_recruitment_tasks_linked_candidate; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_recruitment_tasks_linked_candidate ON public.recruitment_tasks USING btree (workspace_id, linked_candidate_id, updated_at DESC) WHERE (linked_candidate_id IS NOT NULL);


--
-- Name: idx_recruitment_tasks_workspace_updated; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_recruitment_tasks_workspace_updated ON public.recruitment_tasks USING btree (workspace_id, updated_at DESC);


--
-- Name: idx_refresh_sessions_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_refresh_sessions_user ON public.refresh_sessions USING btree (user_id, revoked_at, expires_at);


--
-- Name: idx_resume_parse_drafts_task; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_resume_parse_drafts_task ON public.resume_parse_drafts USING btree (workspace_id, recruitment_task_id, revision DESC);


--
-- Name: idx_resume_source_files_task; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_resume_source_files_task ON public.resume_source_files USING btree (workspace_id, recruitment_task_id, created_at);


--
-- Name: idx_screening_outbox_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_screening_outbox_pending ON public.outbox_events USING btree (event_type, status, next_attempt_at, created_at) WHERE ((event_type)::text = 'SCREENING_RUN_REQUESTED'::text);


--
-- Name: idx_screening_plans_recruitment_task; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_screening_plans_recruitment_task ON public.screening_plans USING btree (recruitment_task_id, updated_at DESC) WHERE (recruitment_task_id IS NOT NULL);


--
-- Name: idx_screening_quotes_scope_expiry; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_screening_quotes_scope_expiry ON public.screening_quotes USING btree (workspace_id, expires_at DESC);


--
-- Name: idx_screening_run_items_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_screening_run_items_pending ON public.screening_run_items USING btree (run_id, status, created_at) WHERE ((status)::text = ANY ((ARRAY['PENDING'::character varying, 'PROCESSING'::character varying])::text[]));


--
-- Name: idx_screening_runs_recruitment_task; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_screening_runs_recruitment_task ON public.screening_runs USING btree (recruitment_task_id, created_at DESC) WHERE (recruitment_task_id IS NOT NULL);


--
-- Name: idx_screening_runs_root; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_screening_runs_root ON public.screening_runs USING btree (root_run_id, created_at);


--
-- Name: idx_screening_runs_workspace_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_screening_runs_workspace_created ON public.screening_runs USING btree (workspace_id, created_at DESC);


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
-- Name: idx_verification_challenges_phone; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_verification_challenges_phone ON public.verification_challenges USING btree (phone_hash, created_at DESC);


--
-- Name: idx_workspaces_company; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_workspaces_company ON public.workspaces USING btree (company_id, status);


--
-- Name: ix_interview_kits_workspace; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_interview_kits_workspace ON public.interview_kits USING btree (workspace_id, created_at DESC);


--
-- Name: ix_membership_applications_company_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_membership_applications_company_status ON public.membership_applications USING btree (company_id, status, created_at);


--
-- Name: ix_notifications_user_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_notifications_user_created ON public.notifications USING btree (user_id, created_at DESC);


--
-- Name: uk_active_recharge_receiving_account; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_active_recharge_receiving_account ON public.recharge_receiving_accounts USING btree (status) WHERE ((status)::text = 'ACTIVE'::text);


--
-- Name: uk_company_pending_claim; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_company_pending_claim ON public.company_verification_requests USING btree (company_id) WHERE (((request_type)::text = 'CLAIM'::text) AND ((status)::text = 'PENDING'::text));


--
-- Name: uk_jobs_jd_draft; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_jobs_jd_draft ON public.jobs USING btree (jd_draft_id) WHERE (jd_draft_id IS NOT NULL);


--
-- Name: uk_pending_company_application; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_pending_company_application ON public.membership_applications USING btree (company_id, applicant_user_id) WHERE ((status)::text = 'PENDING'::text);


--
-- Name: uk_personal_workspace_user; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_personal_workspace_user ON public.workspaces USING btree (owner_user_id) WHERE (((type)::text = 'PERSONAL'::text) AND ((status)::text = 'ACTIVE'::text));


--
-- Name: uk_screening_run_quote; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_screening_run_quote ON public.screening_runs USING btree (quote_id) WHERE (quote_id IS NOT NULL);


--
-- Name: ai_runs trg_ai_runs_scope; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_ai_runs_scope BEFORE INSERT OR UPDATE ON public.ai_runs FOR EACH ROW EXECUTE FUNCTION public.enforce_workspace_company_scope();


--
-- Name: candidates trg_candidates_scope; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_candidates_scope BEFORE INSERT OR UPDATE ON public.candidates FOR EACH ROW EXECUTE FUNCTION public.enforce_workspace_company_scope();


--
-- Name: conversations trg_conversations_scope; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_conversations_scope BEFORE INSERT OR UPDATE ON public.conversations FOR EACH ROW EXECUTE FUNCTION public.enforce_workspace_company_scope();


--
-- Name: file_assets trg_file_assets_scope; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_file_assets_scope BEFORE INSERT OR UPDATE ON public.file_assets FOR EACH ROW EXECUTE FUNCTION public.enforce_workspace_company_scope();


--
-- Name: jd_drafts trg_jd_drafts_scope; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_jd_drafts_scope BEFORE INSERT OR UPDATE ON public.jd_drafts FOR EACH ROW EXECUTE FUNCTION public.enforce_workspace_company_scope();


--
-- Name: jd_run_events trg_jd_run_events_scope; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_jd_run_events_scope BEFORE INSERT OR UPDATE ON public.jd_run_events FOR EACH ROW EXECUTE FUNCTION public.enforce_workspace_company_scope();


--
-- Name: jd_source_files trg_jd_source_files_scope; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_jd_source_files_scope BEFORE INSERT OR UPDATE ON public.jd_source_files FOR EACH ROW EXECUTE FUNCTION public.enforce_workspace_company_scope();


--
-- Name: job_versions trg_job_versions_scope; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_job_versions_scope BEFORE INSERT OR UPDATE ON public.job_versions FOR EACH ROW EXECUTE FUNCTION public.enforce_workspace_company_scope();


--
-- Name: jobs trg_jobs_scope; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_jobs_scope BEFORE INSERT OR UPDATE ON public.jobs FOR EACH ROW EXECUTE FUNCTION public.enforce_workspace_company_scope();


--
-- Name: messages trg_messages_scope; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_messages_scope BEFORE INSERT OR UPDATE ON public.messages FOR EACH ROW EXECUTE FUNCTION public.enforce_workspace_company_scope();


--
-- Name: recruitment_tasks trg_recruitment_tasks_dims_scope; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_recruitment_tasks_dims_scope BEFORE INSERT OR UPDATE ON public.recruitment_tasks FOR EACH ROW EXECUTE FUNCTION public.enforce_workspace_company_scope();


--
-- Name: recruitment_tasks trg_recruitment_tasks_linked_candidate_scope; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_recruitment_tasks_linked_candidate_scope BEFORE INSERT OR UPDATE OF linked_candidate_id, workspace_id, company_id ON public.recruitment_tasks FOR EACH ROW EXECUTE FUNCTION public.fn_enforce_recruitment_task_linked_candidate_scope();


--
-- Name: recruitment_tasks trg_recruitment_tasks_scope; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_recruitment_tasks_scope BEFORE INSERT OR UPDATE ON public.recruitment_tasks FOR EACH ROW EXECUTE FUNCTION public.enforce_workspace_company_scope();


--
-- Name: resume_files trg_resume_files_scope; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_resume_files_scope BEFORE INSERT OR UPDATE ON public.resume_files FOR EACH ROW EXECUTE FUNCTION public.enforce_workspace_company_scope();


--
-- Name: resume_parse_drafts trg_resume_parse_drafts_scope; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_resume_parse_drafts_scope BEFORE INSERT OR UPDATE ON public.resume_parse_drafts FOR EACH ROW EXECUTE FUNCTION public.enforce_workspace_company_scope();


--
-- Name: resume_parse_versions trg_resume_parse_versions_scope; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_resume_parse_versions_scope BEFORE INSERT OR UPDATE ON public.resume_parse_versions FOR EACH ROW EXECUTE FUNCTION public.enforce_workspace_company_scope();


--
-- Name: resume_source_files trg_resume_source_files_scope; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_resume_source_files_scope BEFORE INSERT OR UPDATE ON public.resume_source_files FOR EACH ROW EXECUTE FUNCTION public.enforce_workspace_company_scope();


--
-- Name: screening_plan_versions trg_screening_plan_versions_scope; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_screening_plan_versions_scope BEFORE INSERT OR UPDATE ON public.screening_plan_versions FOR EACH ROW EXECUTE FUNCTION public.enforce_workspace_company_scope();


--
-- Name: screening_plans trg_screening_plans_scope; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_screening_plans_scope BEFORE INSERT OR UPDATE ON public.screening_plans FOR EACH ROW EXECUTE FUNCTION public.enforce_workspace_company_scope();


--
-- Name: screening_quotes trg_screening_quotes_scope; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_screening_quotes_scope BEFORE INSERT OR UPDATE ON public.screening_quotes FOR EACH ROW EXECUTE FUNCTION public.enforce_workspace_company_scope();


--
-- Name: screening_results trg_screening_results_scope; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_screening_results_scope BEFORE INSERT OR UPDATE ON public.screening_results FOR EACH ROW EXECUTE FUNCTION public.enforce_workspace_company_scope();


--
-- Name: screening_run_items trg_screening_run_items_scope; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_screening_run_items_scope BEFORE INSERT OR UPDATE ON public.screening_run_items FOR EACH ROW EXECUTE FUNCTION public.enforce_workspace_company_scope();


--
-- Name: screening_runs trg_screening_runs_scope; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_screening_runs_scope BEFORE INSERT OR UPDATE ON public.screening_runs FOR EACH ROW EXECUTE FUNCTION public.enforce_workspace_company_scope();


--
-- Name: access_tokens access_tokens_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.access_tokens
    ADD CONSTRAINT access_tokens_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- Name: ai_runs ai_runs_company_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ai_runs
    ADD CONSTRAINT ai_runs_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id);


--
-- Name: ai_runs ai_runs_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ai_runs
    ADD CONSTRAINT ai_runs_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.users(id);


--
-- Name: ai_runs ai_runs_recruitment_task_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ai_runs
    ADD CONSTRAINT ai_runs_recruitment_task_id_fkey FOREIGN KEY (recruitment_task_id) REFERENCES public.recruitment_tasks(id);


--
-- Name: ai_runs ai_runs_workspace_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ai_runs
    ADD CONSTRAINT ai_runs_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES public.workspaces(id);


--
-- Name: billing_accounts billing_accounts_workspace_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.billing_accounts
    ADD CONSTRAINT billing_accounts_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES public.workspaces(id);


--
-- Name: billing_ledger_entries billing_ledger_entries_billing_account_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.billing_ledger_entries
    ADD CONSTRAINT billing_ledger_entries_billing_account_id_fkey FOREIGN KEY (billing_account_id) REFERENCES public.billing_accounts(id);


--
-- Name: billing_ledger_entries billing_ledger_entries_credit_lot_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.billing_ledger_entries
    ADD CONSTRAINT billing_ledger_entries_credit_lot_id_fkey FOREIGN KEY (credit_lot_id) REFERENCES public.credit_lots(id);


--
-- Name: billing_ledger_entries billing_ledger_entries_operator_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.billing_ledger_entries
    ADD CONSTRAINT billing_ledger_entries_operator_user_id_fkey FOREIGN KEY (operator_user_id) REFERENCES public.users(id);


--
-- Name: billing_ledger_entries billing_ledger_entries_workspace_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.billing_ledger_entries
    ADD CONSTRAINT billing_ledger_entries_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES public.workspaces(id);


--
-- Name: billing_reservation_allocations billing_reservation_allocations_credit_lot_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.billing_reservation_allocations
    ADD CONSTRAINT billing_reservation_allocations_credit_lot_id_fkey FOREIGN KEY (credit_lot_id) REFERENCES public.credit_lots(id);


--
-- Name: billing_reservation_allocations billing_reservation_allocations_reservation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.billing_reservation_allocations
    ADD CONSTRAINT billing_reservation_allocations_reservation_id_fkey FOREIGN KEY (reservation_id) REFERENCES public.billing_reservations(id);


--
-- Name: billing_reservations billing_reservations_billing_account_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.billing_reservations
    ADD CONSTRAINT billing_reservations_billing_account_id_fkey FOREIGN KEY (billing_account_id) REFERENCES public.billing_accounts(id);


--
-- Name: billing_reservations billing_reservations_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.billing_reservations
    ADD CONSTRAINT billing_reservations_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.users(id);


--
-- Name: billing_reservations billing_reservations_workspace_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.billing_reservations
    ADD CONSTRAINT billing_reservations_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES public.workspaces(id);


--
-- Name: candidates candidates_company_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.candidates
    ADD CONSTRAINT candidates_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id);


--
-- Name: candidates candidates_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.candidates
    ADD CONSTRAINT candidates_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.users(id);


--
-- Name: candidates candidates_workspace_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.candidates
    ADD CONSTRAINT candidates_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES public.workspaces(id);


--
-- Name: companies companies_owner_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.companies
    ADD CONSTRAINT companies_owner_user_id_fkey FOREIGN KEY (owner_user_id) REFERENCES public.users(id);


--
-- Name: company_memberships company_memberships_company_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.company_memberships
    ADD CONSTRAINT company_memberships_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id);


--
-- Name: company_memberships company_memberships_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.company_memberships
    ADD CONSTRAINT company_memberships_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- Name: company_verification_requests company_verification_requests_applicant_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.company_verification_requests
    ADD CONSTRAINT company_verification_requests_applicant_user_id_fkey FOREIGN KEY (applicant_user_id) REFERENCES public.users(id);


--
-- Name: company_verification_requests company_verification_requests_company_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.company_verification_requests
    ADD CONSTRAINT company_verification_requests_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id);


--
-- Name: conversations conversations_company_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.conversations
    ADD CONSTRAINT conversations_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id);


--
-- Name: conversations conversations_recruitment_task_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.conversations
    ADD CONSTRAINT conversations_recruitment_task_id_fkey FOREIGN KEY (recruitment_task_id) REFERENCES public.recruitment_tasks(id);


--
-- Name: conversations conversations_workspace_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.conversations
    ADD CONSTRAINT conversations_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES public.workspaces(id);


--
-- Name: credit_lots credit_lots_billing_account_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.credit_lots
    ADD CONSTRAINT credit_lots_billing_account_id_fkey FOREIGN KEY (billing_account_id) REFERENCES public.billing_accounts(id);


--
-- Name: file_assets file_assets_company_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.file_assets
    ADD CONSTRAINT file_assets_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id);


--
-- Name: file_assets file_assets_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.file_assets
    ADD CONSTRAINT file_assets_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.users(id);


--
-- Name: file_assets file_assets_workspace_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.file_assets
    ADD CONSTRAINT file_assets_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES public.workspaces(id);


--
-- Name: candidates fk_candidates_current_parse; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.candidates
    ADD CONSTRAINT fk_candidates_current_parse FOREIGN KEY (current_parse_version_id) REFERENCES public.resume_parse_versions(id);


--
-- Name: idempotency_records fk_idempotency_workspace; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.idempotency_records
    ADD CONSTRAINT fk_idempotency_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspaces(id);


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
-- Name: screening_quotes fk_screening_quote_consumed_run; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.screening_quotes
    ADD CONSTRAINT fk_screening_quote_consumed_run FOREIGN KEY (consumed_by_run_id) REFERENCES public.screening_runs(id);


--
-- Name: interview_kit_versions interview_kit_versions_company_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.interview_kit_versions
    ADD CONSTRAINT interview_kit_versions_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id);


--
-- Name: interview_kit_versions interview_kit_versions_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.interview_kit_versions
    ADD CONSTRAINT interview_kit_versions_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.users(id);


--
-- Name: interview_kit_versions interview_kit_versions_kit_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.interview_kit_versions
    ADD CONSTRAINT interview_kit_versions_kit_id_fkey FOREIGN KEY (kit_id) REFERENCES public.interview_kits(id);


--
-- Name: interview_kit_versions interview_kit_versions_workspace_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.interview_kit_versions
    ADD CONSTRAINT interview_kit_versions_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES public.workspaces(id);


--
-- Name: interview_kits interview_kits_candidate_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.interview_kits
    ADD CONSTRAINT interview_kits_candidate_id_fkey FOREIGN KEY (candidate_id) REFERENCES public.candidates(id);


--
-- Name: interview_kits interview_kits_company_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.interview_kits
    ADD CONSTRAINT interview_kits_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id);


--
-- Name: interview_kits interview_kits_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.interview_kits
    ADD CONSTRAINT interview_kits_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.users(id);


--
-- Name: interview_kits interview_kits_workspace_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.interview_kits
    ADD CONSTRAINT interview_kits_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES public.workspaces(id);


--
-- Name: interview_questions interview_questions_company_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.interview_questions
    ADD CONSTRAINT interview_questions_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id);


--
-- Name: interview_questions interview_questions_kit_version_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.interview_questions
    ADD CONSTRAINT interview_questions_kit_version_id_fkey FOREIGN KEY (kit_version_id) REFERENCES public.interview_kit_versions(id) ON DELETE CASCADE;


--
-- Name: interview_questions interview_questions_workspace_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.interview_questions
    ADD CONSTRAINT interview_questions_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES public.workspaces(id);


--
-- Name: jd_drafts jd_drafts_company_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.jd_drafts
    ADD CONSTRAINT jd_drafts_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id);


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
-- Name: jd_drafts jd_drafts_updated_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.jd_drafts
    ADD CONSTRAINT jd_drafts_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES public.users(id);


--
-- Name: jd_drafts jd_drafts_workspace_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.jd_drafts
    ADD CONSTRAINT jd_drafts_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES public.workspaces(id);


--
-- Name: jd_run_events jd_run_events_company_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.jd_run_events
    ADD CONSTRAINT jd_run_events_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id);


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
-- Name: jd_run_events jd_run_events_workspace_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.jd_run_events
    ADD CONSTRAINT jd_run_events_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES public.workspaces(id);


--
-- Name: jd_source_files jd_source_files_company_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.jd_source_files
    ADD CONSTRAINT jd_source_files_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id);


--
-- Name: jd_source_files jd_source_files_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.jd_source_files
    ADD CONSTRAINT jd_source_files_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.users(id);


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
-- Name: jd_source_files jd_source_files_workspace_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.jd_source_files
    ADD CONSTRAINT jd_source_files_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES public.workspaces(id);


--
-- Name: job_versions job_versions_company_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.job_versions
    ADD CONSTRAINT job_versions_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id);


--
-- Name: job_versions job_versions_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.job_versions
    ADD CONSTRAINT job_versions_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.users(id);


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
-- Name: job_versions job_versions_workspace_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.job_versions
    ADD CONSTRAINT job_versions_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES public.workspaces(id);


--
-- Name: jobs jobs_company_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.jobs
    ADD CONSTRAINT jobs_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id);


--
-- Name: jobs jobs_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.jobs
    ADD CONSTRAINT jobs_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.users(id);


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
-- Name: jobs jobs_workspace_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.jobs
    ADD CONSTRAINT jobs_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES public.workspaces(id);


--
-- Name: membership_applications membership_applications_applicant_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.membership_applications
    ADD CONSTRAINT membership_applications_applicant_user_id_fkey FOREIGN KEY (applicant_user_id) REFERENCES public.users(id);


--
-- Name: membership_applications membership_applications_company_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.membership_applications
    ADD CONSTRAINT membership_applications_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id);


--
-- Name: membership_applications membership_applications_reviewed_by_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.membership_applications
    ADD CONSTRAINT membership_applications_reviewed_by_user_id_fkey FOREIGN KEY (reviewed_by_user_id) REFERENCES public.users(id);


--
-- Name: membership_invitations membership_invitations_accepted_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.membership_invitations
    ADD CONSTRAINT membership_invitations_accepted_by_fkey FOREIGN KEY (accepted_by) REFERENCES public.users(id);


--
-- Name: membership_invitations membership_invitations_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.membership_invitations
    ADD CONSTRAINT membership_invitations_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.users(id);


--
-- Name: messages messages_company_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.messages
    ADD CONSTRAINT messages_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id);


--
-- Name: messages messages_conversation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.messages
    ADD CONSTRAINT messages_conversation_id_fkey FOREIGN KEY (conversation_id) REFERENCES public.conversations(id);


--
-- Name: messages messages_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.messages
    ADD CONSTRAINT messages_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.users(id);


--
-- Name: messages messages_workspace_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.messages
    ADD CONSTRAINT messages_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES public.workspaces(id);


--
-- Name: notifications notifications_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notifications
    ADD CONSTRAINT notifications_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- Name: personal_identities personal_identities_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.personal_identities
    ADD CONSTRAINT personal_identities_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- Name: platform_admins platform_admins_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_admins
    ADD CONSTRAINT platform_admins_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- Name: platform_menus platform_menus_parent_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_menus
    ADD CONSTRAINT platform_menus_parent_id_fkey FOREIGN KEY (parent_id) REFERENCES public.platform_menus(id);


--
-- Name: recharge_orders recharge_orders_billing_account_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.recharge_orders
    ADD CONSTRAINT recharge_orders_billing_account_id_fkey FOREIGN KEY (billing_account_id) REFERENCES public.billing_accounts(id);


--
-- Name: recharge_orders recharge_orders_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.recharge_orders
    ADD CONSTRAINT recharge_orders_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.users(id);


--
-- Name: recharge_orders recharge_orders_workspace_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.recharge_orders
    ADD CONSTRAINT recharge_orders_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES public.workspaces(id);


--
-- Name: recruitment_tasks recruitment_tasks_company_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.recruitment_tasks
    ADD CONSTRAINT recruitment_tasks_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id);


--
-- Name: recruitment_tasks recruitment_tasks_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.recruitment_tasks
    ADD CONSTRAINT recruitment_tasks_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.users(id);


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
-- Name: recruitment_tasks recruitment_tasks_workspace_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.recruitment_tasks
    ADD CONSTRAINT recruitment_tasks_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES public.workspaces(id);


--
-- Name: refresh_sessions refresh_sessions_rotated_from_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.refresh_sessions
    ADD CONSTRAINT refresh_sessions_rotated_from_id_fkey FOREIGN KEY (rotated_from_id) REFERENCES public.refresh_sessions(id);


--
-- Name: refresh_sessions refresh_sessions_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.refresh_sessions
    ADD CONSTRAINT refresh_sessions_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- Name: resume_files resume_files_candidate_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.resume_files
    ADD CONSTRAINT resume_files_candidate_id_fkey FOREIGN KEY (candidate_id) REFERENCES public.candidates(id);


--
-- Name: resume_files resume_files_company_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.resume_files
    ADD CONSTRAINT resume_files_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id);


--
-- Name: resume_files resume_files_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.resume_files
    ADD CONSTRAINT resume_files_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.users(id);


--
-- Name: resume_files resume_files_file_asset_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.resume_files
    ADD CONSTRAINT resume_files_file_asset_id_fkey FOREIGN KEY (file_asset_id) REFERENCES public.file_assets(id);


--
-- Name: resume_files resume_files_workspace_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.resume_files
    ADD CONSTRAINT resume_files_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES public.workspaces(id);


--
-- Name: resume_parse_drafts resume_parse_drafts_company_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.resume_parse_drafts
    ADD CONSTRAINT resume_parse_drafts_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id);


--
-- Name: resume_parse_drafts resume_parse_drafts_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.resume_parse_drafts
    ADD CONSTRAINT resume_parse_drafts_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.users(id);


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
-- Name: resume_parse_drafts resume_parse_drafts_updated_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.resume_parse_drafts
    ADD CONSTRAINT resume_parse_drafts_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES public.users(id);


--
-- Name: resume_parse_drafts resume_parse_drafts_workspace_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.resume_parse_drafts
    ADD CONSTRAINT resume_parse_drafts_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES public.workspaces(id);


--
-- Name: resume_parse_versions resume_parse_versions_candidate_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.resume_parse_versions
    ADD CONSTRAINT resume_parse_versions_candidate_id_fkey FOREIGN KEY (candidate_id) REFERENCES public.candidates(id);


--
-- Name: resume_parse_versions resume_parse_versions_company_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.resume_parse_versions
    ADD CONSTRAINT resume_parse_versions_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id);


--
-- Name: resume_parse_versions resume_parse_versions_resume_file_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.resume_parse_versions
    ADD CONSTRAINT resume_parse_versions_resume_file_id_fkey FOREIGN KEY (resume_file_id) REFERENCES public.resume_files(id);


--
-- Name: resume_parse_versions resume_parse_versions_workspace_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.resume_parse_versions
    ADD CONSTRAINT resume_parse_versions_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES public.workspaces(id);


--
-- Name: resume_source_files resume_source_files_company_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.resume_source_files
    ADD CONSTRAINT resume_source_files_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id);


--
-- Name: resume_source_files resume_source_files_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.resume_source_files
    ADD CONSTRAINT resume_source_files_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.users(id);


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
-- Name: resume_source_files resume_source_files_workspace_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.resume_source_files
    ADD CONSTRAINT resume_source_files_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES public.workspaces(id);


--
-- Name: screening_plan_versions screening_plan_versions_company_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.screening_plan_versions
    ADD CONSTRAINT screening_plan_versions_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id);


--
-- Name: screening_plan_versions screening_plan_versions_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.screening_plan_versions
    ADD CONSTRAINT screening_plan_versions_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.users(id);


--
-- Name: screening_plan_versions screening_plan_versions_plan_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.screening_plan_versions
    ADD CONSTRAINT screening_plan_versions_plan_id_fkey FOREIGN KEY (plan_id) REFERENCES public.screening_plans(id);


--
-- Name: screening_plan_versions screening_plan_versions_workspace_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.screening_plan_versions
    ADD CONSTRAINT screening_plan_versions_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES public.workspaces(id);


--
-- Name: screening_plans screening_plans_company_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.screening_plans
    ADD CONSTRAINT screening_plans_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id);


--
-- Name: screening_plans screening_plans_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.screening_plans
    ADD CONSTRAINT screening_plans_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.users(id);


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
-- Name: screening_plans screening_plans_workspace_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.screening_plans
    ADD CONSTRAINT screening_plans_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES public.workspaces(id);


--
-- Name: screening_quotes screening_quotes_company_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.screening_quotes
    ADD CONSTRAINT screening_quotes_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id);


--
-- Name: screening_quotes screening_quotes_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.screening_quotes
    ADD CONSTRAINT screening_quotes_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.users(id);


--
-- Name: screening_quotes screening_quotes_job_version_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.screening_quotes
    ADD CONSTRAINT screening_quotes_job_version_id_fkey FOREIGN KEY (job_version_id) REFERENCES public.job_versions(id);


--
-- Name: screening_quotes screening_quotes_plan_version_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.screening_quotes
    ADD CONSTRAINT screening_quotes_plan_version_id_fkey FOREIGN KEY (plan_version_id) REFERENCES public.screening_plan_versions(id);


--
-- Name: screening_quotes screening_quotes_workspace_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.screening_quotes
    ADD CONSTRAINT screening_quotes_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES public.workspaces(id);


--
-- Name: screening_results screening_results_company_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.screening_results
    ADD CONSTRAINT screening_results_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id);


--
-- Name: screening_results screening_results_run_item_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.screening_results
    ADD CONSTRAINT screening_results_run_item_id_fkey FOREIGN KEY (run_item_id) REFERENCES public.screening_run_items(id);


--
-- Name: screening_results screening_results_workspace_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.screening_results
    ADD CONSTRAINT screening_results_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES public.workspaces(id);


--
-- Name: screening_run_items screening_run_items_candidate_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.screening_run_items
    ADD CONSTRAINT screening_run_items_candidate_id_fkey FOREIGN KEY (candidate_id) REFERENCES public.candidates(id);


--
-- Name: screening_run_items screening_run_items_company_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.screening_run_items
    ADD CONSTRAINT screening_run_items_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id);


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
-- Name: screening_run_items screening_run_items_workspace_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.screening_run_items
    ADD CONSTRAINT screening_run_items_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES public.workspaces(id);


--
-- Name: screening_runs screening_runs_company_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.screening_runs
    ADD CONSTRAINT screening_runs_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id);


--
-- Name: screening_runs screening_runs_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.screening_runs
    ADD CONSTRAINT screening_runs_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.users(id);


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
-- Name: screening_runs screening_runs_quote_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.screening_runs
    ADD CONSTRAINT screening_runs_quote_id_fkey FOREIGN KEY (quote_id) REFERENCES public.screening_quotes(id);


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
-- Name: screening_runs screening_runs_workspace_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.screening_runs
    ADD CONSTRAINT screening_runs_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES public.workspaces(id);


--
-- Name: support_ticket_messages support_ticket_messages_ticket_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.support_ticket_messages
    ADD CONSTRAINT support_ticket_messages_ticket_id_fkey FOREIGN KEY (ticket_id) REFERENCES public.support_tickets(id);


--
-- Name: support_tickets support_tickets_assigned_to_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.support_tickets
    ADD CONSTRAINT support_tickets_assigned_to_id_fkey FOREIGN KEY (assigned_to_id) REFERENCES public.platform_admins(id);


--
-- Name: support_tickets support_tickets_company_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.support_tickets
    ADD CONSTRAINT support_tickets_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id);


--
-- Name: support_tickets support_tickets_creator_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.support_tickets
    ADD CONSTRAINT support_tickets_creator_user_id_fkey FOREIGN KEY (creator_user_id) REFERENCES public.users(id);


--
-- Name: trial_eligibilities trial_eligibilities_workspace_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.trial_eligibilities
    ADD CONSTRAINT trial_eligibilities_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES public.workspaces(id);


--
-- Name: workspace_memberships workspace_memberships_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.workspace_memberships
    ADD CONSTRAINT workspace_memberships_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- Name: workspace_memberships workspace_memberships_workspace_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.workspace_memberships
    ADD CONSTRAINT workspace_memberships_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES public.workspaces(id);


--
-- Name: workspaces workspaces_company_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.workspaces
    ADD CONSTRAINT workspaces_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id);


--
-- Name: workspaces workspaces_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.workspaces
    ADD CONSTRAINT workspaces_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.users(id);


--
-- Name: workspaces workspaces_owner_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.workspaces
    ADD CONSTRAINT workspaces_owner_user_id_fkey FOREIGN KEY (owner_user_id) REFERENCES public.users(id);


--
-- PostgreSQL database dump complete
--


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
-- Data for Name: platform_menus; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.platform_menus (id, parent_id, code, display_name, icon, path, permission_code, sort_order, is_visible, visible_to_operator, created_at, updated_at) VALUES ('48cc57b0-a4d2-4518-ba6b-0dac83b737f5', NULL, 'dashboard', '首页', 'LayoutDashboard', '/', NULL, 0, true, true, '2026-09-03 03:25:13.722147+00', '2026-09-03 03:25:13.722147+00');
INSERT INTO public.platform_menus (id, parent_id, code, display_name, icon, path, permission_code, sort_order, is_visible, visible_to_operator, created_at, updated_at) VALUES ('a72f2ca1-79f4-4ccd-94d3-811ff242b043', NULL, 'users', '用户管理', 'Users', '/users', 'user:read', 1, true, true, '2026-09-03 03:25:13.722147+00', '2026-09-03 03:25:13.722147+00');
INSERT INTO public.platform_menus (id, parent_id, code, display_name, icon, path, permission_code, sort_order, is_visible, visible_to_operator, created_at, updated_at) VALUES ('d409155d-62fa-4a94-a7d0-907758412929', NULL, 'companies', '企业管理', 'Building2', '/companies', 'company:read', 2, true, true, '2026-09-03 03:25:13.722147+00', '2026-09-03 03:25:13.722147+00');
INSERT INTO public.platform_menus (id, parent_id, code, display_name, icon, path, permission_code, sort_order, is_visible, visible_to_operator, created_at, updated_at) VALUES ('08d977c3-0afe-4797-bcf5-fe702c0ec748', NULL, 'reviews', '审核中心', 'FileCheck', NULL, NULL, 3, true, true, '2026-09-03 03:25:13.722147+00', '2026-09-03 03:25:13.722147+00');
INSERT INTO public.platform_menus (id, parent_id, code, display_name, icon, path, permission_code, sort_order, is_visible, visible_to_operator, created_at, updated_at) VALUES ('bb369f23-3a93-4b49-bdfc-cdff95dd7a66', '08d977c3-0afe-4797-bcf5-fe702c0ec748', 'reviews_personal', '个人认证', NULL, '/reviews/personal', 'verification:review', 0, true, true, '2026-09-03 03:25:13.722147+00', '2026-09-03 03:25:13.722147+00');
INSERT INTO public.platform_menus (id, parent_id, code, display_name, icon, path, permission_code, sort_order, is_visible, visible_to_operator, created_at, updated_at) VALUES ('c5736f51-74fe-41a0-aa01-49bd2352b4d9', '08d977c3-0afe-4797-bcf5-fe702c0ec748', 'reviews_company', '企业认证', NULL, '/reviews/company', 'verification:review', 1, true, true, '2026-09-03 03:25:13.722147+00', '2026-09-03 03:25:13.722147+00');
INSERT INTO public.platform_menus (id, parent_id, code, display_name, icon, path, permission_code, sort_order, is_visible, visible_to_operator, created_at, updated_at) VALUES ('b8902ca4-f1d1-4faf-92f8-bd79d11b0832', '08d977c3-0afe-4797-bcf5-fe702c0ec748', 'reviews_membership', '成员申请', NULL, '/reviews/membership', 'membership:review', 2, true, true, '2026-09-03 03:25:13.722147+00', '2026-09-03 03:25:13.722147+00');
INSERT INTO public.platform_menus (id, parent_id, code, display_name, icon, path, permission_code, sort_order, is_visible, visible_to_operator, created_at, updated_at) VALUES ('b2a7dbaf-78af-4258-8f8a-dd37c03e4118', NULL, 'tickets', '工单管理', 'MessageSquare', '/tickets', 'ticket:read', 4, true, true, '2026-09-03 03:25:13.722147+00', '2026-09-03 03:25:13.722147+00');
INSERT INTO public.platform_menus (id, parent_id, code, display_name, icon, path, permission_code, sort_order, is_visible, visible_to_operator, created_at, updated_at) VALUES ('23dd26fe-069e-4ce2-85b6-7ed2ede2988c', NULL, 'billing', '账本管理', 'Wallet', '/billing', 'billing:read', 5, true, true, '2026-09-03 03:25:13.722147+00', '2026-09-03 03:25:13.722147+00');
INSERT INTO public.platform_menus (id, parent_id, code, display_name, icon, path, permission_code, sort_order, is_visible, visible_to_operator, created_at, updated_at) VALUES ('2108144c-c91c-4056-ae5e-58f259a800fc', NULL, 'settings', '系统设置', 'Settings', NULL, NULL, 6, true, false, '2026-09-03 03:25:13.722147+00', '2026-09-03 03:25:13.722147+00');
INSERT INTO public.platform_menus (id, parent_id, code, display_name, icon, path, permission_code, sort_order, is_visible, visible_to_operator, created_at, updated_at) VALUES ('9a93424f-c80a-4559-8860-c856cb797b30', '2108144c-c91c-4056-ae5e-58f259a800fc', 'settings_admins', '管理员管理', NULL, '/settings/admins', 'admin:manage', 0, true, false, '2026-09-03 03:25:13.722147+00', '2026-09-03 03:25:13.722147+00');
INSERT INTO public.platform_menus (id, parent_id, code, display_name, icon, path, permission_code, sort_order, is_visible, visible_to_operator, created_at, updated_at) VALUES ('964ab998-7c97-42bf-ada4-7211868bb313', '2108144c-c91c-4056-ae5e-58f259a800fc', 'settings_menus', '菜单管理', NULL, '/settings/menus', 'menu:manage', 1, true, false, '2026-09-03 03:25:13.722147+00', '2026-09-03 03:25:13.722147+00');


--
-- Data for Name: pricing_items; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.pricing_items (id, code, name, description, billing_unit, unit_price_minor, currency, status, sort_order, created_at, updated_at) VALUES ('00000000-0000-0000-0000-000000000001', 'JD_GENERATION', 'JD 智能生成', 'AI 根据招聘需求自动生成完整 JD 草稿（含职责、任职要求、待确认项）', 'PER_USE', 80, 'CNY', 'ACTIVE', 10, '2026-09-03 03:25:13.839+00', '2026-09-03 03:25:13.839+00');
INSERT INTO public.pricing_items (id, code, name, description, billing_unit, unit_price_minor, currency, status, sort_order, created_at, updated_at) VALUES ('00000000-0000-0000-0000-000000000002', 'RESUME_PARSING', '简历 AI 解析', 'AI 解析候选人简历，提取结构化信息（工作经历、教育背景、技能等）', 'PER_ITEM', 80, 'CNY', 'ACTIVE', 20, '2026-09-03 03:25:13.839+00', '2026-09-03 03:25:13.839+00');
INSERT INTO public.pricing_items (id, code, name, description, billing_unit, unit_price_minor, currency, status, sort_order, created_at, updated_at) VALUES ('00000000-0000-0000-0000-000000000003', 'SCREENING', 'AI 简历筛选', 'AI 按招聘方案对候选人简历进行智能匹配评分，每筛选一位候选人计费', 'PER_CANDIDATE', 80, 'CNY', 'ACTIVE', 30, '2026-09-03 03:25:13.839+00', '2026-09-03 03:25:13.839+00');


--
-- PostgreSQL database dump complete
--
