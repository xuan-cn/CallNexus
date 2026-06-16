package org.dromara.call.controller;

import lombok.RequiredArgsConstructor;
import org.dromara.call.service.VoiceMailMessageApplicationService;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.resource.freeswitch.config.FreeSwitchDirectoryProperties;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@RestController
@RequestMapping("/api/internal/freeswitch/voicemail")
@RequiredArgsConstructor
public class VoiceMailInternalController {
    private static final String TOKEN_HEADER = "X-CallNexus-FreeSWITCH-Token";

    private final FreeSwitchDirectoryProperties properties;
    private final VoiceMailMessageApplicationService service;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<Void> upload(@RequestParam String tenantId,
                          @RequestParam Long voicemailBoxId,
                          @RequestParam String businessCallId,
                          @RequestParam(required = false) String callerNumber,
                          @RequestParam(required = false) String calledNumber,
                          @RequestParam(required = false) Long durationMs,
                          @RequestHeader(value = TOKEN_HEADER, required = false) String headerToken,
                          @RequestParam(value = "token", required = false) String queryToken,
                          @RequestPart("file") MultipartFile file) {
        if (!validToken(headerToken, queryToken)) return R.fail("FreeSWITCH 内部接口令牌无效");
        service.upload(tenantId, voicemailBoxId, businessCallId, callerNumber, calledNumber, durationMs, file);
        return R.ok();
    }

    private boolean validToken(String headerToken, String queryToken) {
        String secret = properties.getSecret();
        String token = StringUtils.isBlank(headerToken) ? queryToken : headerToken;
        if (StringUtils.isBlank(secret) || StringUtils.isBlank(token)) return false;
        return MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
    }
}
