package com.intelligentrecruitment.recruitment.infrastructure;

import com.intelligentrecruitment.shared.error.ApiException;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.http.Method;
import java.io.ByteArrayInputStream;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class JdSourceObjectStorage {

    private final MinioClient minio;
    private final String bucket;

    public JdSourceObjectStorage(MinioClient minio, @Value("${app.storage.bucket}") String bucket) {
        this.minio = minio;
        this.bucket = bucket;
    }

    public void put(String objectKey, byte[] content, String mediaType) {
        try {
            minio.putObject(PutObjectArgs.builder().bucket(bucket).object(objectKey)
                    .stream(new ByteArrayInputStream(content), content.length, -1).contentType(mediaType).build());
        } catch (Exception exception) {
            throw new ApiException("OBJECT_STORAGE_FAILED", "JD 源文件保存失败", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    /** 生成对象存储临时下载 URL（默认 10 分钟），供前端预览文件使用。 */
    public String presignedGetUrl(String objectKey) {
        try {
            return minio.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .method(Method.GET)
                    .expiry(10, TimeUnit.MINUTES)
                    .build());
        } catch (Exception exception) {
            throw new ApiException("OBJECT_STORAGE_FAILED", "生成文件下载链接失败", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }
}
