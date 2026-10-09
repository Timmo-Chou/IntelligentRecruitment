package com.intelligentrecruitment.recruitment.infrastructure;

import com.intelligentrecruitment.shared.error.ApiException;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.ServerSideEncryptionS3;
import io.minio.http.Method;
import java.io.ByteArrayInputStream;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class JdSourceObjectStorage implements com.intelligentrecruitment.shared.storage.PrivateObjectStorage {

    private final MinioClient minio;
    private final String bucket;
    private final boolean serverSideEncryption;

    public JdSourceObjectStorage(MinioClient minio, @Value("${app.storage.bucket}") String bucket,
                                 @Value("${app.storage.server-side-encryption:false}") boolean serverSideEncryption) {
        this.minio = minio;
        this.bucket = bucket;
        this.serverSideEncryption = serverSideEncryption;
    }

    public void put(String objectKey, byte[] content, String mediaType) {
        try {
            PutObjectArgs.Builder request = PutObjectArgs.builder().bucket(bucket).object(objectKey)
                    .stream(new ByteArrayInputStream(content), content.length, -1).contentType(mediaType);
            if (serverSideEncryption) request.sse(new ServerSideEncryptionS3());
            minio.putObject(request.build());
        } catch (Exception exception) {
            throw new ApiException("OBJECT_STORAGE_FAILED", "JD 源文件保存失败", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    /** 生成对象存储临时下载 URL（默认 10 分钟），供前端预览文件使用。 */
    public String presignedGetUrl(String objectKey) {
        return presignedGetUrl(objectKey, 600);
    }

    public String presignedGetUrl(String objectKey,int expirySeconds) {
        try {
            return minio.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .method(Method.GET)
                    .expiry(expirySeconds, TimeUnit.SECONDS)
                    .build());
        } catch (Exception exception) {
            throw new ApiException("OBJECT_STORAGE_FAILED", "生成文件下载链接失败", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    @Override public byte[] get(String objectKey) {
        try (var stream=minio.getObject(io.minio.GetObjectArgs.builder().bucket(bucket).object(objectKey).build())) { return stream.readAllBytes(); }
        catch (Exception exception) { throw new ApiException("OBJECT_STORAGE_FAILED","读取私有文件失败",HttpStatus.SERVICE_UNAVAILABLE); }
    }
    @Override public void remove(String objectKey) {
        try { minio.removeObject(io.minio.RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build()); }
        catch (Exception exception) { throw new ApiException("OBJECT_DELETE_FAILED","删除私有文件失败",HttpStatus.SERVICE_UNAVAILABLE); }
    }
}
