package org.dromara.outbound.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.dromara.outbound.domain.response.OutboundBlacklistMatch;
import org.dromara.resource.outboundauth.domain.OutboundAuthorizationCommand;
import org.dromara.resource.outboundauth.domain.OutboundAuthorizationRejection;
import org.dromara.resource.outboundauth.service.OutboundAuthorizationRule;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class OutboundBlacklistAuthorizationRule implements OutboundAuthorizationRule {

    private final OutboundBlacklistChecker blacklistChecker;

    @Override
    public OutboundAuthorizationRejection validate(OutboundAuthorizationCommand command, String normalizedCallee) {
        OutboundBlacklistMatch match = blacklistChecker.check(command.taskId(), normalizedCallee);
        if (match == null) {
            return null;
        }
        log.warn("外呼黑名单拦截，sourceType={}，taskId={}，memberId={}，blacklistId={}，scopeType={}，"
                + "callee={}，tenantId={}",
            command.sourceType(), command.taskId(), command.memberId(), match.getBlacklistId(),
            match.getScopeType(), normalizedCallee, command.tenantId());
        String message = "目标号码已被外呼黑名单拦截";
        if (StringUtils.isNotBlank(match.getReason())) {
            message += "：" + match.getReason();
        }
        return new OutboundAuthorizationRejection("OUTBOUND_BLACKLISTED", message);
    }
}
