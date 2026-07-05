package org.dromara.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.AiRealtimeCallSession;
import org.dromara.ai.domain.AiRealtimeCallTurn;
import org.dromara.ai.mapper.AiRealtimeCallSessionMapper;
import org.dromara.ai.mapper.AiRealtimeCallTurnMapper;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/ai/realtime-voice")
@RequiredArgsConstructor
public class AiRealtimeVoiceController {
    private final AiRealtimeCallSessionMapper sessionMapper;
    private final AiRealtimeCallTurnMapper turnMapper;

    @GetMapping("/sessions")
    @SaCheckPermission("callcenter:ai-agent:query")
    public R<List<AiRealtimeCallSession>> sessions(@RequestParam(required = false) String businessCallId) {
        return R.ok(sessionMapper.selectList(new LambdaQueryWrapper<AiRealtimeCallSession>()
            .eq(StringUtils.isNotBlank(businessCallId), AiRealtimeCallSession::getBusinessCallId, businessCallId)
            .orderByDesc(AiRealtimeCallSession::getCreateTime)
            .last("limit 100")));
    }

    @GetMapping("/sessions/{id}/turns")
    @SaCheckPermission("callcenter:ai-agent:query")
    public R<List<AiRealtimeCallTurn>> turns(@PathVariable Long id) {
        return R.ok(turnMapper.selectList(new LambdaQueryWrapper<AiRealtimeCallTurn>()
            .eq(AiRealtimeCallTurn::getRealtimeSessionId, id)
            .orderByAsc(AiRealtimeCallTurn::getSequenceNo)));
    }
}
