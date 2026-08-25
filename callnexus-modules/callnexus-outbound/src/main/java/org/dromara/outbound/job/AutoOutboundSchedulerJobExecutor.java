package org.dromara.outbound.job;

import com.aizuda.snailjob.client.job.core.annotation.JobExecutor;
import com.aizuda.snailjob.client.job.core.dto.JobArgs;
import com.aizuda.snailjob.common.log.SnailJobLog;
import com.aizuda.snailjob.model.dto.ExecuteResult;
import lombok.RequiredArgsConstructor;
import org.dromara.outbound.service.AutoOutboundSchedulerService;
import org.dromara.outbound.service.model.AutoOutboundSchedulerResult;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@JobExecutor(name = "autoOutboundSchedulerJobExecutor")
public class AutoOutboundSchedulerJobExecutor {

    private final AutoOutboundSchedulerService schedulerService;

    public ExecuteResult jobExecute(JobArgs jobArgs) {
        AutoOutboundSchedulerResult result = schedulerService.execute();
        SnailJobLog.REMOTE.info("自动外呼安全调度执行完成，{}", result.summary());
        return ExecuteResult.success(result.summary());
    }
}
