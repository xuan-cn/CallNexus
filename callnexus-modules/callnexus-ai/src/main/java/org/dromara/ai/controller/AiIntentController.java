package org.dromara.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.request.AiIntentRecognitionRequest;
import org.dromara.ai.domain.request.AiIntentBatchUpdateRequest;
import org.dromara.ai.domain.request.AiIntentQuery;
import org.dromara.ai.domain.request.AiIntentRequest;
import org.dromara.ai.domain.response.AiIntentRecognitionResponse;
import org.dromara.ai.domain.response.AiIntentResponse;
import org.dromara.ai.service.AiIntentApplicationService;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/ai/intents")
@RequiredArgsConstructor
public class AiIntentController {
    public record RuleTestRequest(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 4096) String text,
                                  @jakarta.validation.constraints.NotNull List<org.dromara.ai.domain.AiIntentMatchRule> rules) {}
    public record RuleHit(int index, String polarity, String mode, List<String> words) {}

    @PostMapping("/rules-test")
    @SaCheckPermission("callcenter:ai-intent:test")
    public R<List<RuleHit>> testRules(@Valid @RequestBody RuleTestRequest request) {
        org.dromara.ai.service.IntentRuleMatcher.validate(request.rules());
        List<RuleHit> hits = new java.util.ArrayList<>();
        for (int index = 0; index < request.rules().size(); index++) {
            var rule = request.rules().get(index);
            var words = org.dromara.ai.service.IntentRuleMatcher.matchedWords(rule, request.text());
            if (!words.isEmpty()) hits.add(new RuleHit(index + 1, rule.getPolarity(), rule.getMode(), words));
        }
        return R.ok(hits);
    }
    private final AiIntentApplicationService service;

    @GetMapping
    @SaCheckPermission("callcenter:ai-intent:list")
    public R<List<AiIntentResponse>> list() {
        return R.ok(service.intents());
    }

    @GetMapping("/page")
    @SaCheckPermission("callcenter:ai-intent:list")
    public TableDataInfo<AiIntentResponse> page(AiIntentQuery query, PageQuery pageQuery) {
        return service.page(query, pageQuery);
    }

    @GetMapping("/{id}")
    @SaCheckPermission("callcenter:ai-intent:query")
    public R<AiIntentResponse> detail(@PathVariable Long id) {
        return R.ok(service.intent(id));
    }

    @PostMapping
    @SaCheckPermission("callcenter:ai-intent:create")
    public R<Long> create(@Valid @RequestBody AiIntentRequest request) {
        return R.ok(service.createIntent(request));
    }

    @PutMapping("/{id}")
    @SaCheckPermission("callcenter:ai-intent:update")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody AiIntentRequest request) {
        service.updateIntent(id, request);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @SaCheckPermission("callcenter:ai-intent:delete")
    public R<Void> delete(@PathVariable Long id) {
        service.deleteIntent(id);
        return R.ok();
    }

    @PutMapping("/batch")
    @SaCheckPermission("callcenter:ai-intent:update")
    public R<Void> batchUpdate(@Valid @RequestBody AiIntentBatchUpdateRequest request) {
        service.batchUpdate(request);
        return R.ok();
    }

    @PostMapping("/recognize-test")
    @SaCheckPermission("callcenter:ai-intent:test")
    public R<AiIntentRecognitionResponse> recognize(@Valid @RequestBody AiIntentRecognitionRequest request) {
        return R.ok(service.recognize(request));
    }
}
