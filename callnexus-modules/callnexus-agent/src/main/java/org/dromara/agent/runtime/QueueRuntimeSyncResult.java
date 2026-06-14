package org.dromara.agent.runtime;

import java.util.List;

public record QueueRuntimeSyncResult(
    int successCount,
    int failedCount,
    List<String> errors
) {
}
