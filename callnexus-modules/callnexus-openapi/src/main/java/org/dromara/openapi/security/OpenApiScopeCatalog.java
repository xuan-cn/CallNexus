package org.dromara.openapi.security;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.LinkedHashSet;
import java.util.Set;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class OpenApiScopeCatalog {
    public static final Set<String> ALL = Set.of(
        "agent.read", "agent.signin", "agent.signout", "agent.status.write",
        "call.read", "call.originate", "call.answer", "call.hangup", "call.hold",
        "call.mute", "call.dtmf", "call.transfer", "call.consult", "call.conference",
        "dispatch.read", "dispatch.monitor", "dispatch.whisper", "dispatch.barge",
        "dispatch.force_hangup", "event.subscribe"
    );

    public static Set<String> ordered() {
        return new LinkedHashSet<>(ALL.stream().sorted().toList());
    }
}
