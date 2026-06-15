package org.dromara.outbound.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.outbound.domain.OutboundBlacklist;
import org.dromara.outbound.domain.OutboundMember;
import org.dromara.outbound.mapper.OutboundBlacklistMapper;
import org.dromara.outbound.mapper.OutboundMemberMapper;
import org.dromara.outbound.mapper.OutboundTaskMapper;
import org.dromara.outbound.domain.OutboundTask;
import org.dromara.outbound.domain.response.OutboundBlacklistMatch;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class OutboundBlacklistMemberSyncService {
    private final OutboundBlacklistMapper blacklistMapper;
    private final OutboundMemberMapper memberMapper;
    private final OutboundTaskMapper taskMapper;

    @Transactional(rollbackFor = Exception.class)
    public void blockMembers(OutboundBlacklist blacklist) {
        if (!isActive(blacklist)) {
            return;
        }
        List<OutboundMember> members = memberMapper.selectList(new LambdaQueryWrapper<OutboundMember>()
            .eq("TASK".equals(blacklist.getScopeType()), OutboundMember::getTaskId, blacklist.getTaskId())
            .eq(OutboundMember::getPhoneNumber, blacklist.getNormalizedPhone())
            .in(OutboundMember::getStatus, "PENDING", "RETRY", "CLAIMED"));
        Set<Long> affectedTaskIds = new HashSet<>();
        for (OutboundMember member : members) {
            affectedTaskIds.add(member.getTaskId());
            memberMapper.update(null, new LambdaUpdateWrapper<OutboundMember>()
                .eq(OutboundMember::getId, member.getId())
                .in(OutboundMember::getStatus, "PENDING", "RETRY", "CLAIMED")
                .set(OutboundMember::getStatusBeforeBlocked, member.getStatus())
                .set(OutboundMember::getStatus, "BLOCKED")
                .set(OutboundMember::getBlockedReason, blacklist.getReason())
                .set(OutboundMember::getBlockedAt, LocalDateTime.now())
                .set(OutboundMember::getBlockedBlacklistId, blacklist.getId())
                .set(OutboundMember::getClaimedAgentId, null)
                .set(OutboundMember::getClaimedUserId, null)
                .set(OutboundMember::getClaimedAt, null)
                .set(OutboundMember::getLeaseExpiresAt, null));
        }
        affectedTaskIds.forEach(this::completeTaskIfFinished);
    }

    @Transactional(rollbackFor = Exception.class)
    public int restore(Long blacklistId) {
        List<OutboundMember> members = memberMapper.selectList(new LambdaQueryWrapper<OutboundMember>()
            .eq(OutboundMember::getBlockedBlacklistId, blacklistId)
            .eq(OutboundMember::getStatus, "BLOCKED"));
        for (OutboundMember member : members) {
            OutboundBlacklist replacement = findActiveReplacement(member, blacklistId);
            if (replacement != null) {
                memberMapper.update(null, new LambdaUpdateWrapper<OutboundMember>()
                    .eq(OutboundMember::getId, member.getId())
                    .eq(OutboundMember::getStatus, "BLOCKED")
                    .set(OutboundMember::getBlockedReason, replacement.getReason())
                    .set(OutboundMember::getBlockedAt, LocalDateTime.now())
                    .set(OutboundMember::getBlockedBlacklistId, replacement.getId()));
                continue;
            }
            String restored = "CLAIMED".equals(member.getStatusBeforeBlocked()) ? "PENDING" : member.getStatusBeforeBlocked();
            if (!"RETRY".equals(restored)) {
                restored = "PENDING";
            }
            memberMapper.update(null, new LambdaUpdateWrapper<OutboundMember>()
                .eq(OutboundMember::getId, member.getId())
                .eq(OutboundMember::getStatus, "BLOCKED")
                .set(OutboundMember::getStatus, restored)
                .set(OutboundMember::getBlockedReason, null)
                .set(OutboundMember::getBlockedAt, null)
                .set(OutboundMember::getBlockedBlacklistId, null)
                .set(OutboundMember::getStatusBeforeBlocked, null));
            OutboundTask task = taskMapper.selectById(member.getTaskId());
            if (task != null && "COMPLETED".equals(task.getStatus())) {
                taskMapper.update(null, new LambdaUpdateWrapper<OutboundTask>()
                    .eq(OutboundTask::getId, task.getId())
                    .set(OutboundTask::getStatus, "RUNNING"));
            }
        }
        return members.size();
    }

    @Transactional(rollbackFor = Exception.class)
    public int restoreExpired() {
        List<OutboundBlacklist> expired = blacklistMapper.selectList(new LambdaQueryWrapper<OutboundBlacklist>()
            .eq(OutboundBlacklist::getEnabled, true)
            .isNotNull(OutboundBlacklist::getExpiresAt)
            .le(OutboundBlacklist::getExpiresAt, LocalDateTime.now()));
        int restored = 0;
        for (OutboundBlacklist blacklist : expired) {
            blacklist.setEnabled(false);
            blacklistMapper.updateById(blacklist);
            restored += restore(blacklist.getId());
        }
        for (OutboundBlacklist blacklist : blacklistMapper.selectList(new LambdaQueryWrapper<OutboundBlacklist>()
            .eq(OutboundBlacklist::getEnabled, true)
            .and(time -> time.isNull(OutboundBlacklist::getEffectiveAt).or().le(OutboundBlacklist::getEffectiveAt, LocalDateTime.now()))
            .and(time -> time.isNull(OutboundBlacklist::getExpiresAt).or().gt(OutboundBlacklist::getExpiresAt, LocalDateTime.now())))) {
            blockMembers(blacklist);
        }
        return restored;
    }

    public void blockMember(OutboundMember member, OutboundBlacklistMatch match) {
        memberMapper.update(null, new LambdaUpdateWrapper<OutboundMember>()
            .eq(OutboundMember::getId, member.getId())
            .in(OutboundMember::getStatus, "PENDING", "RETRY", "CLAIMED")
            .set(OutboundMember::getStatusBeforeBlocked, member.getStatus())
            .set(OutboundMember::getStatus, "BLOCKED")
            .set(OutboundMember::getBlockedReason, match.getReason())
            .set(OutboundMember::getBlockedAt, LocalDateTime.now())
            .set(OutboundMember::getBlockedBlacklistId, match.getBlacklistId())
            .set(OutboundMember::getClaimedAgentId, null)
            .set(OutboundMember::getClaimedUserId, null)
            .set(OutboundMember::getClaimedAt, null)
            .set(OutboundMember::getLeaseExpiresAt, null));
    }

    private boolean isActive(OutboundBlacklist blacklist) {
        LocalDateTime now = LocalDateTime.now();
        return Boolean.TRUE.equals(blacklist.getEnabled())
            && (blacklist.getEffectiveAt() == null || !blacklist.getEffectiveAt().isAfter(now))
            && (blacklist.getExpiresAt() == null || blacklist.getExpiresAt().isAfter(now));
    }

    private OutboundBlacklist findActiveReplacement(OutboundMember member, Long excludedId) {
        LocalDateTime now = LocalDateTime.now();
        return blacklistMapper.selectOne(new LambdaQueryWrapper<OutboundBlacklist>()
            .ne(OutboundBlacklist::getId, excludedId)
            .eq(OutboundBlacklist::getNormalizedPhone, member.getPhoneNumber())
            .eq(OutboundBlacklist::getEnabled, true)
            .and(scope -> scope.eq(OutboundBlacklist::getScopeType, "GLOBAL")
                .or(task -> task.eq(OutboundBlacklist::getScopeType, "TASK").eq(OutboundBlacklist::getTaskId, member.getTaskId())))
            .and(time -> time.isNull(OutboundBlacklist::getEffectiveAt).or().le(OutboundBlacklist::getEffectiveAt, now))
            .and(time -> time.isNull(OutboundBlacklist::getExpiresAt).or().gt(OutboundBlacklist::getExpiresAt, now))
            .last("ORDER BY CASE WHEN scope_type = 'GLOBAL' THEN 0 ELSE 1 END LIMIT 1"));
    }

    private void completeTaskIfFinished(Long taskId) {
        long executable = memberMapper.selectCount(new LambdaQueryWrapper<OutboundMember>()
            .eq(OutboundMember::getTaskId, taskId)
            .in(OutboundMember::getStatus, "PENDING", "RETRY", "CLAIMED", "DIALING"));
        if (executable == 0) {
            taskMapper.update(null, new LambdaUpdateWrapper<OutboundTask>()
                .eq(OutboundTask::getId, taskId)
                .set(OutboundTask::getStatus, "COMPLETED"));
        }
    }
}
