package org.dromara.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.request.AiSpeechTaskPageQuery;
import org.dromara.ai.domain.response.AiSpeechTaskResponse;
import org.dromara.ai.service.AiSpeechApplicationService;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ai/speech-tasks")
@RequiredArgsConstructor
public class AiSpeechTaskController {
    private final AiSpeechApplicationService service;

    @GetMapping
    @SaCheckPermission("callcenter:ai-speech:list")
    public TableDataInfo<AiSpeechTaskResponse> page(AiSpeechTaskPageQuery query, PageQuery pageQuery) {
        return service.tasks(query, pageQuery);
    }
}
