package org.dromara.ai.service;

public interface AiAgentAssistAvailabilityProvider {
    Availability resolve(String businessCallId);

    record Availability(boolean enabled, Long callSessionId, Long agentId,
                        Long skillGroupId, Long assistAgentId) {
        public static Availability disabled() {
            return new Availability(false, null, null, null, null);
        }
    }
}
