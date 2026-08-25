package org.dromara.outbound.job;

import com.aizuda.snailjob.client.job.core.annotation.JobExecutor;
import com.aizuda.snailjob.client.job.core.dto.JobArgs;
import com.aizuda.snailjob.common.log.SnailJobLog;
import com.aizuda.snailjob.model.dto.ExecuteResult;
import lombok.RequiredArgsConstructor;
import org.dromara.outbound.service.AutoOutboundDialerService;
import org.dromara.outbound.service.model.AutoOutboundDialerResult;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@JobExecutor(name = "autoOutboundDialerJobExecutor")
public class AutoOutboundDialerJobExecutor {
    private final AutoOutboundDialerService dialerService;

    public ExecuteResult jobExecute(JobArgs jobArgs) {
        AutoOutboundDialerResult result = dialerService.execute();
        SnailJobLog.REMOTE.info("自动外呼待拨消费完成，{}", result.summary());
        return ExecuteResult.success(result.summary());
    }
}
