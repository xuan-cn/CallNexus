package org.dromara.call.domain;

public record EslEndpoint(String host, int port, String password, String sipDomain) {
}
