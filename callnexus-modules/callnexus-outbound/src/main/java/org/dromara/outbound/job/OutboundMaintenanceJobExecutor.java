package org.dromara.outbound.job;

import com.aizuda.snailjob.client.job.core.annotation.JobExecutor;
import com.aizuda.snailjob.client.job.core.dto.JobArgs;
import com.aizuda.snailjob.common.log.SnailJobLog;
import com.aizuda.snailjob.model.dto.ExecuteResult;
import lombok.RequiredArgsConstructor;
import org.dromara.outbound.service.OutboundMaintenanceSchedulerService;
import org.dromara.outbound.service.model.OutboundMaintenanceResult;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@JobExecutor(name = "outboundMaintenanceJobExecutor")
public class OutboundMaintenanceJobExecutor {

    private final OutboundMaintenanceSchedulerService schedulerService;

    public ExecuteResult jobExecute(JobArgs jobArgs) {
        OutboundMaintenanceResult result = schedulerService.execute();
        SnailJobLog.REMOTE.info("外呼维护调度执行完成，{}", result.summary());
        return ExecuteResult.success(result.summary());
    }
}

