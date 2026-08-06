package org.dromara.openapi.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;

@Component
public class OpenApiRemoteAddressResolver {
    private final List<String> trustedProxyCidrs;

    public OpenApiRemoteAddressResolver(
        @Value("${CALLNEXUS_OPENAPI_TRUSTED_PROXY_CIDRS:}") String trustedProxyCidrs) {
        this.trustedProxyCidrs = Arrays.stream(trustedProxyCidrs.split(","))
            .map(String::trim).filter(value -> !value.isBlank()).map(IpCidrMatcher::normalize).toList();
    }

    public String resolve(HttpServletRequest request) {
        String directAddress = canonical(request.getRemoteAddr());
        if (trustedProxyCidrs.stream().noneMatch(cidr -> IpCidrMatcher.matches(directAddress, cidr))) {
            return directAddress;
        }
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor == null || forwardedFor.isBlank()) {
            return directAddress;
        }
        List<String> chain = Arrays.stream(forwardedFor.split(","))
            .map(String::trim).filter(value -> !value.isBlank()).map(this::canonical).toList();
        for (int index = chain.size() - 1; index >= 0; index--) {
            String candidate = chain.get(index);
            if (trustedProxyCidrs.stream().noneMatch(cidr -> IpCidrMatcher.matches(candidate, cidr))) {
                return candidate;
            }
        }
        return directAddress;
    }

    private String canonical(String value) {
        try {
            return InetAddress.getByName(value).getHostAddress();
        } catch (Exception exception) {
            throw new OpenApiAuthenticationException(400, "invalid_source_ip", "Source IP address is invalid.");
        }
    }
}
