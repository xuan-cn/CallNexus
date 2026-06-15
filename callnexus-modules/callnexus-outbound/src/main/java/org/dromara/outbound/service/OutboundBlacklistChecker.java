package org.dromara.outbound.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.outbound.domain.OutboundBlacklist;
import org.dromara.outbound.domain.response.OutboundBlacklistMatch;
import org.dromara.outbound.mapper.OutboundBlacklistMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class OutboundBlacklistChecker {
    private final OutboundBlacklistMapper blacklistMapper;
    private final PhoneNumberNormalizer phoneNumberNormalizer;

    public OutboundBlacklistMatch check(Long taskId, String phoneNumber) {
        String normalized = phoneNumberNormalizer.normalize(phoneNumber);
        if (!phoneNumberNormalizer.isValid(normalized)) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        OutboundBlacklist blacklist = blacklistMapper.selectOne(new LambdaQueryWrapper<OutboundBlacklist>()
            .eq(OutboundBlacklist::getNormalizedPhone, normalized)
            .eq(OutboundBlacklist::getEnabled, true)
            .and(scope -> scope.eq(OutboundBlacklist::getScopeType, "GLOBAL")
                .or(taskId != null, task -> task.eq(OutboundBlacklist::getScopeType, "TASK")
                    .eq(OutboundBlacklist::getTaskId, taskId)))
            .and(time -> time.isNull(OutboundBlacklist::getEffectiveAt).or().le(OutboundBlacklist::getEffectiveAt, now))
            .and(time -> time.isNull(OutboundBlacklist::getExpiresAt).or().gt(OutboundBlacklist::getExpiresAt, now))
            .last("ORDER BY CASE WHEN scope_type = 'GLOBAL' THEN 0 ELSE 1 END LIMIT 1"));
        if (blacklist == null) {
            return null;
        }
        OutboundBlacklistMatch match = new OutboundBlacklistMatch();
        match.setBlacklistId(blacklist.getId());
        match.setScopeType(blacklist.getScopeType());
        match.setTaskId(blacklist.getTaskId());
        match.setNormalizedPhone(normalized);
        match.setReason(blacklist.getReason());
        return match;
    }
}
