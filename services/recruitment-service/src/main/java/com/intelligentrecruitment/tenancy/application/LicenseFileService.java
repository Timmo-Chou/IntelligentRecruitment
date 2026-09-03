package com.intelligentrecruitment.tenancy.application;

import com.intelligentrecruitment.recruitment.infrastructure.JdSourceObjectStorage;
import com.intelligentrecruitment.shared.error.ApiException;
import com.intelligentrecruitment.shared.security.SecurityHashes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Locale;
import java.util.UUID;

/**
 * 企业认证营业执照文件服务。
 * <p>
 * 企业认证提交时，企业和工作空间尚未创建（需平台审核通过后才创建），
 * 因此不依赖 file_assets 表（其要求 workspace_id NOT NULL），
 * 直接把文件存入对象存储，返回可追踪的 objectKey 作为引用。
 * licenseReference 字段存储 objectKey 字符串，兼容旧数据（纯文件名）。
 */
@Service
public class LicenseFileService {

    private final JdSourceObjectStorage storage;
    private final long maxFileSize;

    public LicenseFileService(JdSourceObjectStorage storage,
                              @Value("${app.storage.max-file-size-bytes:10485760}") long maxFileSize) {
        this.storage = storage;
        this.maxFileSize = maxFileSize;
    }

    /**
     * 上传营业执照文件，返回文件视图（引用=objectKey，可直接存入 licenseReference）。
     */
    public LicenseFileView upload(UUID userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException("EMPTY_FILE", "营业执照文件不能为空", HttpStatus.BAD_REQUEST);
        }
        long size = file.getSize();
        if (size > maxFileSize) {
            throw new ApiException("FILE_TOO_LARGE", "营业执照文件过大（最大 10MB）", HttpStatus.PAYLOAD_TOO_LARGE);
        }
        String original = safeFilename(file.getOriginalFilename());
        String mediaType = mediaType(original);
        if (!mediaType.startsWith("image/") && !mediaType.equals("application/pdf")) {
            throw new ApiException("UNSUPPORTED_FILE_TYPE", "营业执照仅支持 JPG、PNG、WEBP、PDF 格式", HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ApiException("READ_FILE_FAILED", "读取营业执照文件失败", HttpStatus.INTERNAL_SERVER_ERROR);
        }
        // 忽略 hash，仅做存储（审核一次性材料，不做去重）
        String assetId = UUID.randomUUID().toString();
        String objectKey = "company-verifications/" + userId + "/" + assetId + "_" + original;
        storage.put(objectKey, bytes, mediaType);
        return new LicenseFileView(objectKey, original, mediaType, size);
    }

    /**
     * 根据 licenseReference 生成预览预签名 URL。
     * 若 reference 为对象存储 key（包含 '/'），返回预签名 URL；
     * 否则为旧数据（纯文件名），返回 null 表示不可预览。
     */
    public String previewUrl(String licenseReference) {
        if (licenseReference == null || !licenseReference.contains("/")) {
            return null;
        }
        return storage.presignedGetUrl(licenseReference);
    }

    /**
     * 从引用中还原原始文件名。
     * <p>
     * 兼容三种格式：
     * <ol>
     * <li>新数据 objectKey：形如 {@code company-verifications/{userId}/{uuid}_{originalFilename}}，
     * 用最后一个下划线后的部分作为文件名（且包含 '/' 是识别为新格式的前提）。</li>
     * <li>旧数据：纯文件名（不包含 '/'）。原封不动返回，避免把文件名内部的下划线误切开。</li>
     * <li>异常降级：取最后一段路径作为文件名。</li>
     * </ol>
     */
    public String extractFilename(String licenseReference) {
        if (licenseReference == null) return "";
        if (!licenseReference.contains("/")) {
            // 旧数据：没有 objectKey 路径分隔符，直接就是用户上传的文件名
            return licenseReference;
        }
        // 新数据：路径末尾是 UUID_原始文件名
        int idx = licenseReference.lastIndexOf('_');
        if (idx >= 0 && idx < licenseReference.length() - 1) {
            return licenseReference.substring(idx + 1);
        }
        // 降级：取最后一段路径名
        int lastSlash = licenseReference.lastIndexOf('/');
        return lastSlash >= 0 && lastSlash < licenseReference.length() - 1
                ? licenseReference.substring(lastSlash + 1)
                : licenseReference;
    }

    public record LicenseFileView(
            String reference,   // objectKey，用于存入 licenseReference
            String filename,    // 原始文件名
            String mediaType,   // MIME 类型
            long sizeBytes
    ) {}

    // ---------- 内部工具函数 ----------

    private static String safeFilename(String filename) {
        if (filename == null || filename.isEmpty()) return "unnamed";
        // 去除路径分隔符等不安全字符
        return filename.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
    }

    private static String mediaType(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".pdf")) return "application/pdf";
        return "application/octet-stream";
    }
}
