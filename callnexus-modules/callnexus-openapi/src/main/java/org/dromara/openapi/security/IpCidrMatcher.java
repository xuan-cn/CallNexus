package org.dromara.openapi.security;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.net.InetAddress;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class IpCidrMatcher {
    public static String normalize(String value) {
        try {
            String input = value.trim();
            String[] parts = input.split("/", -1);
            if (!parts[0].matches("[0-9a-fA-F:.]+")) {
                throw new IllegalArgumentException();
            }
            InetAddress address = InetAddress.getByName(parts[0]);
            int maxBits = address.getAddress().length * 8;
            int prefix = parts.length == 1 ? maxBits : Integer.parseInt(parts[1]);
            if (parts.length > 2 || prefix < 0 || prefix > maxBits) {
                throw new IllegalArgumentException();
            }
            return address.getHostAddress() + "/" + prefix;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid IP/CIDR: " + value, exception);
        }
    }

    public static boolean matches(String remoteAddress, String cidr) {
        try {
            InetAddress remote = InetAddress.getByName(remoteAddress);
            String[] parts = cidr.split("/", -1);
            InetAddress network = InetAddress.getByName(parts[0]);
            byte[] remoteBytes = remote.getAddress();
            byte[] networkBytes = network.getAddress();
            if (remoteBytes.length != networkBytes.length) {
                return false;
            }
            int prefix = parts.length == 1 ? networkBytes.length * 8 : Integer.parseInt(parts[1]);
            int fullBytes = prefix / 8;
            int remainingBits = prefix % 8;
            for (int index = 0; index < fullBytes; index++) {
                if (remoteBytes[index] != networkBytes[index]) {
                    return false;
                }
            }
            if (remainingBits == 0) {
                return true;
            }
            int mask = 0xff << (8 - remainingBits);
            return (remoteBytes[fullBytes] & mask) == (networkBytes[fullBytes] & mask);
        } catch (Exception exception) {
            return false;
        }
    }
}
