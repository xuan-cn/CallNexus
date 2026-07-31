package org.dromara.resource.acl.service;

import org.dromara.common.core.exception.ServiceException;

import java.net.InetAddress;
import java.net.UnknownHostException;

public final class IpCidrMatcher {
    private IpCidrMatcher() {
    }

    public static String normalizeCidr(String value) {
        if (value == null || value.isBlank()) {
            throw new ServiceException("IP/CIDR 不能为空");
        }
        String cidr = value.trim();
        String addressPart = cidr;
        Integer prefix = null;
        int separator = cidr.indexOf('/');
        if (separator >= 0) {
            addressPart = cidr.substring(0, separator);
            try {
                prefix = Integer.parseInt(cidr.substring(separator + 1));
            } catch (NumberFormatException exception) {
                throw new ServiceException("CIDR 前缀长度不正确：" + value);
            }
        }
        InetAddress address = parseAddress(addressPart);
        int maxPrefix = address.getAddress().length * 8;
        int effectivePrefix = prefix == null ? maxPrefix : prefix;
        if (effectivePrefix < 0 || effectivePrefix > maxPrefix) {
            throw new ServiceException("CIDR 前缀长度超出范围：" + value);
        }
        return address.getHostAddress() + "/" + effectivePrefix;
    }

    public static boolean matches(String ip, String cidr) {
        InetAddress address = parseAddress(ip);
        String normalizedCidr = normalizeCidr(cidr);
        int separator = normalizedCidr.lastIndexOf('/');
        InetAddress network = parseAddress(normalizedCidr.substring(0, separator));
        int prefix = Integer.parseInt(normalizedCidr.substring(separator + 1));
        byte[] addressBytes = address.getAddress();
        byte[] networkBytes = network.getAddress();
        if (addressBytes.length != networkBytes.length) {
            return false;
        }
        int wholeBytes = prefix / 8;
        int remainingBits = prefix % 8;
        for (int index = 0; index < wholeBytes; index++) {
            if (addressBytes[index] != networkBytes[index]) {
                return false;
            }
        }
        if (remainingBits == 0) {
            return true;
        }
        int mask = 0xFF << (8 - remainingBits);
        return (addressBytes[wholeBytes] & mask) == (networkBytes[wholeBytes] & mask);
    }

    private static InetAddress parseAddress(String value) {
        String address = value == null ? "" : value.trim();
        if (!address.matches("^[0-9A-Fa-f:.]+$")) {
            throw new ServiceException("IP 地址格式不正确：" + value);
        }
        try {
            return InetAddress.getByName(address);
        } catch (UnknownHostException exception) {
            throw new ServiceException("IP 地址格式不正确：" + value);
        }
    }
}
