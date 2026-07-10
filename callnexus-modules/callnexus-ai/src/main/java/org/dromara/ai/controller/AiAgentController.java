package org.dromara.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.request.AiAgentRequest;
import org.dromara.ai.domain.request.AiChatRequest;
import org.dromara.ai.domain.response.*;
import org.dromara.ai.service.AiAgentApplicationService;
import org.dromara.common.core.domain.R;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.tenant.helper.TenantHelper;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
public class AiAgentController {

    private final AiAgentApplicationService service;

    @GetMapping("/agents")
    @SaCheckPermission("callcenter:ai-agent:list")
    public R<List<AiAgentResponse>> agents() {
        return R.ok(service.agents());
    }

    @GetMapping("/agents/{id}")
    @SaCheckPermission("callcenter:ai-agent:query")
    public R<AiAgentResponse> agent(@PathVariable Long id) {
        return R.ok(service.agent(id));
    }

    @PostMapping("/agents")
    @SaCheckPermission("callcenter:ai-agent:create")
    public R<Long> create(@Valid @RequestBody AiAgentRequest request) {
        return R.ok(service.createAgent(request));
    }

    @PutMapping("/agents/{id}")
    @SaCheckPermission("callcenter:ai-agent:update")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody AiAgentRequest request) {
        service.updateAgent(id, request);
        return R.ok();
    }

    @DeleteMapping("/agents/{id}")
    @SaCheckPermission("callcenter:ai-agent:delete")
    public R<Void> delete(@PathVariable Long id) {
        service.deleteAgent(id);
        return R.ok();
    }

    @PostMapping("/agents/{id}/{action:enable|disable}")
    @SaCheckPermission("callcenter:ai-agent:update")
    public R<Void> enabled(@PathVariable Long id, @PathVariable String action) {
        service.setAgentEnabled(id, "enable".equals(action));
        return R.ok();
    }

    @PostMapping("/agents/{id}/conversations")
    @SaCheckPermission("callcenter:ai-conversation:chat")
    public R<AiConversationStartResponse> startConversation(@PathVariable Long id) {
        return R.ok(service.startConversation(id, LoginHelper.getUserId()));
    }

    @GetMapping("/conversations")
    @SaCheckPermission("callcenter:ai-conversation:list")
    public R<List<AiConversationResponse>> conversations(@RequestParam(required = false) Long agentId) {
        return R.ok(service.conversations(agentId));
    }

    @GetMapping("/conversations/{id}/messages")
    @SaCheckPermission("callcenter:ai-conversation:list")
    public R<List<AiMessageResponse>> messages(@PathVariable Long id) {
        return R.ok(service.messages(id));
    }

    @DeleteMapping("/conversations/{id}")
    @SaCheckPermission("callcenter:ai-conversation:chat")
    public R<Void> deleteConversation(@PathVariable Long id) {
        service.deleteConversation(id);
        return R.ok();
    }

    @DeleteMapping("/agents/{id}/conversations")
    @SaCheckPermission("callcenter:ai-conversation:chat")
    public R<Void> deleteConversations(@PathVariable Long id) {
        service.deleteConversations(id);
        return R.ok();
    }

    @PostMapping(value = "/agents/{id}/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @SaCheckPermission("callcenter:ai-conversation:chat")
    public StreamingResponseBody stream(@PathVariable Long id, @Valid @RequestBody AiChatRequest request,
                                        HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        Long userId = LoginHelper.getUserId();
        String tenantId = TenantHelper.getTenantId();
        return output -> TenantHelper.dynamic(tenantId, () -> service.streamChat(id, userId, request, (event, data) -> {
            try {
                String payload = "event: " + event + "\n" + "data: " + JsonUtils.toJsonString(data) + "\n\n";
                output.write(payload.getBytes(StandardCharsets.UTF_8));
                output.flush();
            } catch (IOException exception) {
                throw new StreamClosedException(exception);
            }
        }));
    }

    private static final class StreamClosedException extends RuntimeException {
        private StreamClosedException(IOException cause) {
            super("AI 对话流连接已关闭", cause);
        }
    }
}
