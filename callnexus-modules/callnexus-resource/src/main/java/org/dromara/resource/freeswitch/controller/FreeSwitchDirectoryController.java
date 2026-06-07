package org.dromara.resource.freeswitch.controller;

import lombok.RequiredArgsConstructor;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.resource.freeswitch.config.FreeSwitchDirectoryProperties;
import org.dromara.resource.freeswitch.xml.FreeSwitchXmlRenderer;
import org.dromara.resource.freeswitch.xml.directory.FreeSwitchDirectoryXmlRenderer;
import org.dromara.resource.sip.domain.response.SipDirectoryAccountResponse;
import org.dromara.resource.sip.service.SipAccountQueryService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@RestController
@RequestMapping("/api/internal/freeswitch")
@RequiredArgsConstructor
public class FreeSwitchDirectoryController {
    private static final String TOKEN_HEADER = "X-CallNexus-FreeSWITCH-Token";

    private final SipAccountQueryService sipAccountQueryService;
    private final FreeSwitchDirectoryProperties properties;
    private final FreeSwitchDirectoryXmlRenderer directoryXmlRenderer;

    @PostMapping(value = "/directory", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE, produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> directory(@RequestBody MultiValueMap<String, String> request,
                                            @RequestParam MultiValueMap<String, String> query,
                                            @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        if (!validToken(token, firstValue(query, "token"))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_XML).body(FreeSwitchXmlRenderer.notFound());
        }
        String section = firstValue(request, query, "section");
        if (!"directory".equals(section)) return xml(FreeSwitchXmlRenderer.notFound());
        String domain = firstValue(request, query, "domain");
        String extension = firstValue(request, query, "user");
        if (StringUtils.isBlank(extension)) extension = firstValue(request, query, "sip_auth_username");
        if (StringUtils.isBlank(extension)) extension = firstValue(request, query, "key_value");
        if (StringUtils.isBlank(domain) || StringUtils.isBlank(extension)) return xml(FreeSwitchXmlRenderer.notFound());

        String tenantId = firstValue(request, query, "tenantId");
        if (StringUtils.isBlank(tenantId)) tenantId = properties.getDefaultTenantId();
        SipDirectoryAccountResponse account = sipAccountQueryService.findDirectoryAccount(tenantId, domain, extension);
        if (account == null) return xml(FreeSwitchXmlRenderer.notFound());
        return xml(directoryXmlRenderer.render(account));
    }

    private boolean validToken(String headerToken, String queryToken) {
        String secret = properties.getSecret();
        String token = StringUtils.isBlank(headerToken) ? queryToken : headerToken;
        if (StringUtils.isBlank(secret) || StringUtils.isBlank(token)) return false;
        return MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
    }

    private ResponseEntity<String> xml(String body) {
        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .contentType(MediaType.APPLICATION_XML)
            .body(body);
    }

    private String firstValue(MultiValueMap<String, String> request, String key) {
        String value = request.getFirst(key);
        return value == null ? null : value.trim();
    }

    private String firstValue(MultiValueMap<String, String> request, MultiValueMap<String, String> query, String key) {
        String value = firstValue(request, key);
        return StringUtils.isBlank(value) ? firstValue(query, key) : value;
    }

}
