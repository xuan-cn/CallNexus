package org.dromara.resource.freeswitch.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.resource.freeswitch.config.FreeSwitchDirectoryProperties;
import org.dromara.resource.freeswitch.xml.FreeSwitchXmlRenderer;
import org.dromara.resource.freeswitch.xmlcurl.FreeSwitchXmlCurlDispatcher;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

@RestController
@RequestMapping("/api/internal/freeswitch")
@RequiredArgsConstructor
@Slf4j
public class FreeSwitchDirectoryController {
    private static final String TOKEN_HEADER = "X-CallNexus-FreeSWITCH-Token";

    private final FreeSwitchDirectoryProperties properties;
    private final FreeSwitchXmlCurlDispatcher dispatcher;

    /**
     * 兼容旧的 XML Curl Directory 入口。
     * 后续新增类型优先使用更清晰的独立路径，不继续往该入口堆 purpose。
     */
    @PostMapping(value = "/directory", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE, produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> directory(@RequestParam MultiValueMap<String, String> params,
                                            @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        return dispatchXmlCurl(params, token, firstValue(params, "section"), firstValue(params, "purpose"), "兼容目录入口");
    }

    @PostMapping(value = "/directory/users", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE, produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> directoryUsers(@RequestParam MultiValueMap<String, String> params,
                                                 @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        return dispatchXmlCurl(params, token, "directory", null, "用户目录");
    }

    @PostMapping(value = "/directory/gateways", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE, produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> directoryGateways(@RequestParam MultiValueMap<String, String> params,
                                                    @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
            return dispatchXmlCurl(params, token, "directory", "gateways", "网关目录");
    }

    @PostMapping(value = "/dialplan", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE, produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> dialplan(@RequestParam MultiValueMap<String, String> params,
                                           @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        return dispatchXmlCurl(params, token, "dialplan", null, "拨号计划");
    }

    @PostMapping(value = "/configuration/callcenter", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE, produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> callCenterConfiguration(@RequestParam MultiValueMap<String, String> params,
                                                          @RequestHeader(value = TOKEN_HEADER, required = false) String token,
                                                          @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                                          HttpServletRequest servletRequest) {
        MultiValueMap<String, String> requestParams = new LinkedMultiValueMap<>(params);
        requestParams.set("_remoteAddress", clientAddress(servletRequest));
        applyNodeCredentials(requestParams, authorization);
        return dispatchXmlCurl(requestParams, token, "configuration", "callcenter", "呼叫队列配置");
    }

    private ResponseEntity<String> dispatchXmlCurl(MultiValueMap<String, String> params, String token, String section, String purpose, String requestType) {
        if (!validToken(token, firstValue(params, "token"))) {
            log.warn("FreeSWITCH 动态 XML 配置请求鉴权失败，类型={}，section={}，purpose={}，domain={}",
                requestType, section, purpose, firstValue(params, "domain"));
            return ResponseEntity.status(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_XML).body(FreeSwitchXmlRenderer.notFound());
        }
        String body = dispatcher.dispatch(params, section, purpose);
        log.info("已返回 FreeSWITCH 动态 XML 配置，类型={}，section={}，purpose={}，domain={}，tenantId={}，响应长度={} 字符",
            requestType, section, purpose, firstValue(params, "domain"), firstValue(params, "tenantId"), body.length());
        return xml(body);
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

    private String clientAddress(HttpServletRequest request) {
        String remoteAddress = request.getRemoteAddr();
        return remoteAddress != null && remoteAddress.startsWith("::ffff:")
            ? remoteAddress.substring("::ffff:".length())
            : remoteAddress;
    }

    private void applyNodeCredentials(MultiValueMap<String, String> params, String authorization) {
        if (StringUtils.isBlank(authorization) || !authorization.startsWith("Basic ")) {
            return;
        }
        try {
            String credentials = new String(Base64.getDecoder().decode(authorization.substring(6)), StandardCharsets.UTF_8);
            int separator = credentials.indexOf(':');
            if (separator > 0 && separator < credentials.length() - 1) {
                params.set("_nodeCode", credentials.substring(0, separator));
                params.set("_nodeToken", credentials.substring(separator + 1));
            }
        } catch (IllegalArgumentException exception) {
            log.warn("FreeSWITCH 呼叫队列动态配置请求 Basic 鉴权格式错误");
        }
    }
}
