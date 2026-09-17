package com.intelligentrecruitment.recruitment.application;

import com.intelligentrecruitment.candidates.application.ResumeTextExtractor;
import com.intelligentrecruitment.candidates.application.PiiCipher;
import com.intelligentrecruitment.recruitment.infrastructure.JdSourceObjectStorage;
import com.intelligentrecruitment.shared.error.ApiException;
import com.intelligentrecruitment.shared.security.SecurityHashes;
import com.intelligentrecruitment.tenancy.application.TenantAccessService;
import com.intelligentrecruitment.tenancy.application.TenantAccessService.TenantScope;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import static com.intelligentrecruitment.shared.database.SqlTimes.timestamp;

@Service
public class JdSourceFileService {

    private final JdbcTemplate jdbc;
    private final TenantAccessService tenantAccess;
    private final JdSourceObjectStorage storage;
    private final ResumeTextExtractor extractor;
    private final PiiCipher pii;
    private final long maxFileSize;

    public JdSourceFileService(JdbcTemplate jdbc, TenantAccessService tenantAccess, JdSourceObjectStorage storage,
                               ResumeTextExtractor extractor, PiiCipher pii,
                               @Value("${app.storage.max-file-size-bytes:10485760}") long maxFileSize) {
        this.jdbc = jdbc;
        this.tenantAccess = tenantAccess;
        this.storage = storage;
        this.extractor = extractor;
        this.pii = pii;
        this.maxFileSize = maxFileSize;
    }

    @Transactional
    public SourceFileView upload(UUID userId, UUID tenantId, UUID taskId, MultipartFile file) {
        TenantScope scope = tenantAccess.requireBusinessAccess(userId, tenantId);
        requireTask(tenantId, taskId);
        byte[] bytes = read(file);
        String filename = safeFilename(file.getOriginalFilename());
        String hash = SecurityHashes.sha256(bytes);
        UUID assetId = existingAsset(tenantId, hash);
        String mediaType = mediaType(filename);
        if (assetId == null) {
            assetId = UUID.randomUUID();
            String objectKey = tenantId + "/jd-source/" + assetId;
            storage.put(objectKey, bytes, mediaType);
            jdbc.update("""
                    INSERT INTO file_assets
                    (id,tenant_id,object_key,original_filename,media_type,size_bytes,sha256,
                     scan_status,lifecycle_status,created_by,created_at)
                    VALUES (?,?,?,?,?,?,?,'PENDING','ACTIVE',?,?)
                    """, assetId, scope.tenantId(), objectKey, pii.encrypt(filename), mediaType, bytes.length, hash,
                    userId, timestamp(Instant.now()));
        }
        UUID sourceId = UUID.randomUUID();
        UUID resolvedAssetId = assetId;
        String extracted = extractedText(bytes, filename);
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO jd_source_files
                (id,tenant_id,recruitment_task_id,file_asset_id,extracted_text,created_by,created_at)
                VALUES (?,?,?,?,?,?,?)
                ON CONFLICT (recruitment_task_id,file_asset_id) DO NOTHING
                """, sourceId, scope.tenantId(), taskId, resolvedAssetId, pii.encrypt(extracted), userId, timestamp(now));
        List<SourceFileView> files = list(tenantId, taskId);
        return files.stream().filter(item -> item.fileAssetId().equals(resolvedAssetId)).findFirst().orElseThrow();
    }

    public List<SourceFileView> listForGeneration(UUID tenantId, UUID taskId) {
        return list(tenantId, taskId);
    }

    private List<SourceFileView> list(UUID tenantId, UUID taskId) {
        return jdbc.query("""
                SELECT s.id,s.file_asset_id,f.original_filename,f.media_type,f.size_bytes,s.extracted_text,s.created_at
                FROM jd_source_files s JOIN file_assets f ON f.id=s.file_asset_id
                WHERE s.tenant_id=? AND s.recruitment_task_id=? AND f.lifecycle_status='ACTIVE'
                ORDER BY s.created_at
                """, (rs, n) -> new SourceFileView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                pii.decryptIfEncrypted(rs.getString(3)), rs.getString(4), rs.getLong(5), pii.decryptIfEncrypted(rs.getString(6)), rs.getTimestamp(7).toInstant()), tenantId, taskId);
    }

    private UUID existingAsset(UUID tenantId, String hash) {
        List<UUID> rows = jdbc.query("SELECT id FROM file_assets WHERE tenant_id=? AND sha256=? AND lifecycle_status='ACTIVE'",
                (rs, n) -> rs.getObject(1, UUID.class), tenantId, hash);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void requireTask(UUID tenantId, UUID taskId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM recruitment_tasks WHERE id=? AND tenant_id=?", Integer.class, taskId, tenantId);
        if (count == null || count == 0) throw new ApiException("RECRUITMENT_TASK_NOT_FOUND", "招聘任务不存在", HttpStatus.NOT_FOUND);
    }

    private byte[] read(MultipartFile file) {
        if (file == null || file.isEmpty()) throw validation("请选择 JD 源文件");
        if (file.getSize() > maxFileSize) throw validation("JD 源文件不能超过10MB");
        String filename = safeFilename(file.getOriginalFilename()).toLowerCase(Locale.ROOT);
        if (!List.of("pdf", "docx", "txt", "md").contains(extension(filename))) throw validation("仅支持 PDF、DOCX、TXT 或 MD 文件");
        try { return file.getBytes(); } catch (java.io.IOException exception) { throw validation("读取 JD 源文件失败"); }
    }

    private String extractedText(byte[] bytes, String filename) {
        String extension = extension(filename.toLowerCase(Locale.ROOT));
        String text = ("txt".equals(extension) || "md".equals(extension)) ? new String(bytes, StandardCharsets.UTF_8) : extractor.extract(bytes, filename);
        String clean = text.replaceAll("\\u0000", "").trim();
        if (clean.isBlank()) throw validation("无法从该文件提取可用文本，请上传可复制文本的 PDF、DOCX、TXT 或 MD 文件");
        return clean.substring(0, Math.min(clean.length(), 20_000));
    }

    private static String safeFilename(String filename) {
        String value = filename == null ? "jd-source" : filename.replace("\\", "/");
        value = value.substring(value.lastIndexOf('/') + 1).replaceAll("[\\r\\n]", "").trim();
        if (value.isBlank() || value.length() > 255) throw validation("JD 源文件名无效");
        return value;
    }

    private static String extension(String filename) {
        int index = filename.lastIndexOf('.');
        return index < 0 ? "" : filename.substring(index + 1);
    }

    private static String mediaType(String filename) {
        return switch (extension(filename.toLowerCase(Locale.ROOT))) {
            case "pdf" -> "application/pdf";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "md" -> "text/markdown";
            default -> "text/plain";
        };
    }

    private static ApiException validation(String message) {
        return new ApiException("VALIDATION_FAILED", message, HttpStatus.BAD_REQUEST);
    }

    public record SourceFileView(UUID id, UUID fileAssetId, String filename, String mediaType, long sizeBytes,
                                 String extractedText, Instant createdAt) { }
}
