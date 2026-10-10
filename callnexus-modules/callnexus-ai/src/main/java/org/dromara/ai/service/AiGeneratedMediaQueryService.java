package org.dromara.ai.service;

public interface AiGeneratedMediaQueryService {
    String findSyncedPath(String businessType, Long businessId, Long nodeId);

    SyncedMedia findSyncedMedia(Long mediaId, Long nodeId);

    record SyncedMedia(String path, Long durationMs, String assetName) {
    }
}
