package com.intelligentrecruitment.candidates.infrastructure;

import com.intelligentrecruitment.shared.error.ApiException;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;

@Component
public class ResumeObjectStorage {

    private final MinioClient minio;
    private final String bucket;

    public ResumeObjectStorage(MinioClient minio, @Value("${app.storage.bucket}") String bucket) {
        this.minio = minio;
        this.bucket = bucket;
    }

    public void put(String objectKey, byte[] content, String mediaType) {
        try {
            minio.putObject(PutObjectArgs.builder().bucket(bucket).object(objectKey)
                    .stream(new ByteArrayInputStream(content), content.length, -1)
                    .contentType(mediaType).build());
        } catch (Exception exception) {
            throw new ApiException("OBJECT_STORAGE_FAILED", "简历文件保存失败", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    public byte[] get(String objectKey) {
        try (var stream = minio.getObject(GetObjectArgs.builder().bucket(bucket).object(objectKey).build())) {
            return stream.readAllBytes();
        } catch (Exception exception) {
            throw new ApiException("RESUME_FILE_UNAVAILABLE", "简历原文件暂不可用", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    public void remove(String objectKey) {
        try {
            minio.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception exception) {
            throw new ApiException("OBJECT_DELETE_FAILED", "简历文件删除失败，请稍后重试", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }
}
