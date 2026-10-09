package com.intelligentrecruitment.internal;

import com.intelligentrecruitment.candidates.application.PiiCipher;
import com.intelligentrecruitment.shared.storage.PrivateObjectStorage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Issues short-lived private-object downloads only for a persisted, accepted Complex attempt. */
@RestController
public class InternalRecruitmentFileGrantController {
    private final JdbcTemplate jdbc;
    private final PiiCipher pii;
    private final @org.springframework.beans.factory.annotation.Qualifier("jdSourceObjectStorage") PrivateObjectStorage storage;
    private final String serviceToken;

    public InternalRecruitmentFileGrantController(JdbcTemplate jdbc,PiiCipher pii,@org.springframework.beans.factory.annotation.Qualifier("jdSourceObjectStorage") PrivateObjectStorage storage,
            @Value("${app.ai-platform.http.service-token:}") String serviceToken) {
        this.jdbc=jdbc;this.pii=pii;this.storage=storage;this.serviceToken=serviceToken;
    }

    @PostMapping("/internal/recruitment/file-download-grants")
    public Grant issue(@RequestHeader("Authorization") String authorization,@RequestBody Request request) {
        if (serviceToken.isBlank()||!constantTimeEquals("Bearer "+serviceToken,authorization))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Internal service authentication failed");
        if (request==null||request.tenantId()==null||request.aiTaskId()==null||request.attemptId()==null||request.fileAssetId()==null
                ||!List.of("RESUME_ANALYSIS","CANDIDATE_MATCH").contains(request.purpose()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid file download grant request");
        UUID aiTaskUuid;
        try { aiTaskUuid=UUID.fromString(request.aiTaskId()); }
        catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid AI task id"); }
        List<File> files=jdbc.query("""
                SELECT f.object_key,f.original_filename,f.media_type,f.size_bytes,f.sha256,e.business_task_id,e.capability,e.agent_id
                FROM ai_execution_records e JOIN file_assets f ON f.id=? AND f.tenant_id=e.tenant_id
                WHERE e.tenant_id=? AND e.agent_task_id=? AND e.attempt_id=? AND e.business_task_id=?
                  AND e.agent_id='complex_recruitment_agent' AND e.accepted_at IS NOT NULL AND e.final_decision IS NULL
                  AND e.capability=? AND f.lifecycle_status='ACTIVE' AND f.scan_status='CLEAN'
                  AND f.size_bytes BETWEEN 1 AND 10485760
                  AND ((?='RESUME_ANALYSIS' AND e.capability='RESUME_PARSING' AND (EXISTS (
                         SELECT 1 FROM resume_files rf JOIN candidates c ON c.id=rf.candidate_id
                         WHERE rf.file_asset_id=f.id AND c.id::text=e.business_task_id AND c.tenant_id=e.tenant_id)
                         OR EXISTS(SELECT 1 FROM resume_source_files sf WHERE sf.file_asset_id=f.id AND sf.tenant_id=e.tenant_id AND sf.recruitment_task_id::text=e.business_task_id)
                         OR EXISTS(SELECT 1 FROM recruitment_tasks rt JOIN resume_files rf ON rf.candidate_id=rt.linked_candidate_id WHERE rt.tenant_id=e.tenant_id AND rt.id::text=e.business_task_id AND rf.file_asset_id=f.id)))
                    OR (?='CANDIDATE_MATCH' AND e.capability='CANDIDATE_SCREENING' AND EXISTS (
                         SELECT 1 FROM screening_run_items sri JOIN resume_parse_versions pv ON pv.id=sri.parse_version_id
                         JOIN resume_files rf ON rf.id=pv.resume_file_id
                         WHERE sri.tenant_id=e.tenant_id AND rf.file_asset_id=f.id
                           AND (sri.id::text=e.business_task_id OR (sri.batch_id::text=e.business_task_id AND sri.status='PROCESSING'
                             AND EXISTS(SELECT 1 FROM screening_execution_batches seb WHERE seb.id=sri.batch_id AND seb.tenant_id=e.tenant_id AND seb.status='PROCESSING')))))
                  )
                """,(rs,n)->new File(rs.getString(1),pii.decryptIfEncrypted(rs.getString(2)),rs.getString(3),rs.getLong(4),rs.getString(5)),
                request.fileAssetId(),request.tenantId(),request.aiTaskId(),request.attemptId(),request.businessTaskId(),request.capability(),request.purpose(),request.purpose());
        if(files.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Authorized source file not found");
        File file=files.getFirst();
        String lower=file.filename().toLowerCase(java.util.Locale.ROOT);
        if(!(lower.endsWith(".pdf")||lower.endsWith(".docx"))||!("application/pdf".equals(file.mimeType())||
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(file.mimeType())))
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"Only PDF and DOCX files are supported");
        Instant expiresAt=Instant.now().plusSeconds(300);
        jdbc.update("INSERT INTO ai_file_download_grant_audits(id,tenant_id,ai_task_id,attempt_id,file_asset_id,purpose,sha256,expires_at) VALUES(?,?,?,?,?,?,?,?)",
                UUID.randomUUID(),request.tenantId(),aiTaskUuid,request.attemptId(),request.fileAssetId(),request.purpose(),file.sha256(),java.sql.Timestamp.from(expiresAt));
        return new Grant(storage.presignedGetUrl(file.objectKey(),300),expiresAt,file.sha256(),file.mimeType(),file.sizeBytes(),file.filename());
    }

    private static boolean constantTimeEquals(String expected,String provided){
        return provided!=null&&MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),provided.getBytes(StandardCharsets.UTF_8));
    }
    @com.fasterxml.jackson.databind.annotation.JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
    // Task identifiers are stored as varchar in the durable execution ledger. Keep their
    // wire values as strings so PostgreSQL does not compare varchar columns to UUID binds.
    public record Request(UUID tenantId,String aiTaskId,UUID attemptId,UUID fileAssetId,String purpose,String capability,String businessTaskId) { }
    public record Grant(String url,Instant expiresAt,String sha256,String mimeType,long byteCount,String filename) { }
    private record File(String objectKey,String filename,String mimeType,long sizeBytes,String sha256) { }
}
