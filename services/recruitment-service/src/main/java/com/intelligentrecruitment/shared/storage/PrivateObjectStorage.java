package com.intelligentrecruitment.shared.storage;

/** Application boundary for private recruitment documents and short-lived downloads. */
public interface PrivateObjectStorage {
    void put(String objectKey, byte[] content, String mediaType);
    byte[] get(String objectKey);
    void remove(String objectKey);
    String presignedGetUrl(String objectKey, int expirySeconds);
    default String presignedGetUrl(String objectKey){return presignedGetUrl(objectKey,600);}
}
