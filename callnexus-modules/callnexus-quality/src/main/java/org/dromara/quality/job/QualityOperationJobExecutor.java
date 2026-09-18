package org.dromara.quality.job;

import com.aizuda.snailjob.client.job.core.annotation.JobExecutor;
import com.aizuda.snailjob.client.job.core.dto.JobArgs;
import com.aizuda.snailjob.common.log.SnailJobLog;
import com.aizuda.snailjob.model.dto.ExecuteResult;
import lombok.RequiredArgsConstructor;
import org.dromara.quality.service.QualityOperationService;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@JobExecutor(name = "qualityOperationJobExecutor")
public class QualityOperationJobExecutor {
    private final QualityOperationService service;

    public ExecuteResult jobExecute(JobArgs jobArgs) {
        String summary = service.executeScheduled();
        SnailJobLog.REMOTE.info("质检周期快照与风险预警生成完成，{}", summary);
        return ExecuteResult.success(summary);
    }
}
