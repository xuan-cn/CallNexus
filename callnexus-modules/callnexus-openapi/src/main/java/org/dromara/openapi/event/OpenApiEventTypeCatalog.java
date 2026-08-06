package org.dromara.openapi.event;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.LinkedHashSet;
import java.util.Set;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class OpenApiEventTypeCatalog {
    public static final Set<String> ALL = Set.of(
        "call.ringing", "call.answered", "call.bridged", "call.unbridged", "call.hangup",
        "call.monitor.started", "call.monitor.stopped", "call.whisper.started", "call.whisper.stopped",
        "call.barge.started", "call.barge.stopped", "call.force_hangup",
        "conference.created", "conference.member_invited", "conference.member_joined", "conference.member_muted",
        "conference.member_left", "conference.ended", "recording.ready", "transcript.ready", "transcript.failed"
    );

    public static Set<String> ordered() {
        return new LinkedHashSet<>(ALL.stream().sorted().toList());
    }
}
