package org.dromara.resource.gateway.support;

import org.dromara.common.core.exception.ServiceException;

/** Builds FreeSWITCH endpoints without persisting a registered device's transient Contact. */
public final class OutboundGatewayDialString {
    public static final String DEVICE_REGISTER = "DEVICE_REGISTER";

    private OutboundGatewayDialString() {
    }

    public static String build(String accessMode, String gatewayCode, String registeredIdentity, String sipProfile,
                               String domain, String destination) {
        return build(accessMode, gatewayCode, registeredIdentity, sipProfile, domain, destination, new String[0]);
    }

    public static String build(String accessMode, String gatewayCode, String registeredIdentity, String sipProfile,
                               String domain, String destination, String... additionalLegVariables) {
        requireDialValue(destination, "外呼号码");
        if (!DEVICE_REGISTER.equals(accessMode)) {
            requireDialValue(gatewayCode, "网关编码");
            return legVariables(additionalLegVariables) + "sofia/gateway/" + gatewayCode + "/" + destination;
        }
        requireDialValue(registeredIdentity, "设备注册身份");
        requireDialValue(sipProfile, "Sofia Profile");
        requireDialValue(domain, "SIP 域");

        // Let the user endpoint resolve the current Contact and keep every B-leg variable in one originate block.
        String destinationUri = "sip:" + destination + "@" + domain;
        String[] legVariables = new String[additionalLegVariables.length + 2];
        System.arraycopy(additionalLegVariables, 0, legVariables, 0, additionalLegVariables.length);
        legVariables[additionalLegVariables.length] = "sip_invite_req_uri=" + destinationUri;
        legVariables[additionalLegVariables.length + 1] = "sip_invite_to_uri=" + destinationUri;
        return legVariables(legVariables)
            + "user/" + registeredIdentity + "@" + domain;
    }

    private static String legVariables(String... variables) {
        if (variables == null || variables.length == 0) {
            return "";
        }
        for (String variable : variables) {
            if (variable == null || !variable.matches("^[A-Za-z0-9_]+=[A-Za-z0-9_.+*#@:-]+$")) {
                throw new ServiceException("外呼腿参数不合法");
            }
        }
        return "[" + String.join(",", variables) + "]";
    }

    /**
     * Replaces the registered identity in a live Sofia contact with the PSTN destination.
     */
    public static String buildFromRegisteredContact(String contact, String destination) {
        requireDialValue(destination, "外呼号码");
        if (contact == null || contact.isBlank()) {
            throw new ServiceException("线路设备未注册或 Contact 不可用");
        }
        String normalized = contact.trim();
        if (normalized.startsWith("-ERR") || normalized.startsWith("error/")) {
            throw new ServiceException("线路设备未注册或 Contact 不可用");
        }
        int separator = normalized.indexOf(',');
        if (separator >= 0) {
            normalized = normalized.substring(0, separator).trim();
        }
        if (normalized.indexOf('\r') >= 0 || normalized.indexOf('\n') >= 0
            || normalized.indexOf('{') >= 0 || normalized.indexOf('}') >= 0
            || normalized.indexOf('|') >= 0 || normalized.indexOf('\'') >= 0) {
            throw new ServiceException("线路设备 Contact 格式不合法");
        }
        int sipUserStart = normalized.indexOf("/sip:");
        int at = sipUserStart < 0 ? -1 : normalized.indexOf('@', sipUserStart + 5);
        if (!normalized.startsWith("sofia/") || at < 0 || at == normalized.length() - 1) {
            throw new ServiceException("线路设备 Contact 格式不受支持");
        }
        return normalized.substring(0, sipUserStart + 5) + destination + normalized.substring(at);
    }

    private static void requireDialValue(String value, String field) {
        if (value == null || !value.matches("^[A-Za-z0-9_.+*#@:-]+$")) {
            throw new ServiceException(field + "不合法");
        }
    }
}
