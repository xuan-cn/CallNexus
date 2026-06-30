package org.dromara.call.constant;

public final class EslHeaders {
    public static final String EVENT_NAME = "Event-Name";
    public static final String UNIQUE_ID = "Unique-ID";
    public static final String CHANNEL_CALL_UUID = "Channel-Call-UUID";
    public static final String CHANNEL_NAME = "Channel-Name";
    public static final String CALL_DIRECTION = "Call-Direction";
    public static final String CALLER_CALLER_ID_NUMBER = "Caller-Caller-ID-Number";
    public static final String CALLER_USERNAME = "Caller-Username";
    public static final String CALLER_DESTINATION_NUMBER = "Caller-Destination-Number";
    public static final String CALLER_CALLEE_ID_NUMBER = "Caller-Callee-ID-Number";
    public static final String VARIABLE_SIP_REQ_USER = "variable_sip_req_user";
    public static final String VARIABLE_SIP_TO_USER = "variable_sip_to_user";
    public static final String VARIABLE_DIALED_USER = "variable_dialed_user";
    public static final String VARIABLE_DIALLED_USER = "variable_dialled_user";
    public static final String VARIABLE_CURRENT_APPLICATION_DATA = "variable_current_application_data";
    public static final String HANGUP_CAUSE = "Hangup-Cause";
    public static final String OTHER_LEG_UNIQUE_ID = "Other-Leg-Unique-ID";
    public static final String BRIDGE_A_UNIQUE_ID = "Bridge-A-Unique-ID";
    public static final String BRIDGE_B_UNIQUE_ID = "Bridge-B-Unique-ID";
    public static final String VARIABLE_ORIGINATION_UUID = "variable_origination_uuid";
    public static final String VARIABLE_BRIDGE_UUID = "variable_bridge_uuid";
    public static final String VARIABLE_CALLNEXUS_BUSINESS_CALL_ID = "variable_callnexus_business_call_id";
    public static final String VARIABLE_CALLNEXUS_DIRECTION = "variable_callnexus_direction";
    public static final String VARIABLE_CALLNEXUS_CALL_PURPOSE = "variable_callnexus_call_purpose";
    public static final String VARIABLE_CALLNEXUS_ORIGINAL_CALLER = "variable_callnexus_original_caller";
    public static final String VARIABLE_CALLNEXUS_ORIGINAL_CALLED = "variable_callnexus_original_called";
    public static final String VARIABLE_CALLNEXUS_ORIGINAL_CALL_ID = "variable_callnexus_original_call_id";
    public static final String VARIABLE_CALLNEXUS_CONSULT_CALL_ID = "variable_callnexus_consult_call_id";
    public static final String VARIABLE_CALLNEXUS_CUSTOMER_LEG_UUID = "variable_callnexus_customer_leg_uuid";
    public static final String VARIABLE_CALLNEXUS_SOURCE_AGENT_LEG_UUID = "variable_callnexus_source_agent_leg_uuid";
    public static final String VARIABLE_CALLNEXUS_CONSULT_LEG_UUID = "variable_callnexus_consult_leg_uuid";
    public static final String VARIABLE_CALLNEXUS_SOURCE_AGENT_EXTENSION = "variable_callnexus_source_agent_extension";
    public static final String VARIABLE_CALLNEXUS_TARGET_AGENT_EXTENSION = "variable_callnexus_target_agent_extension";
    public static final String VARIABLE_CALLNEXUS_MONITOR_TARGET_LEG_UUID = "variable_callnexus_monitor_target_leg_uuid";
    public static final String VARIABLE_CALLNEXUS_CUSTOMER_ID = "variable_callnexus_customer_id";
    public static final String VARIABLE_CALLNEXUS_OUTBOUND_TASK_ID = "variable_callnexus_outbound_task_id";
    public static final String VARIABLE_CALLNEXUS_OUTBOUND_MEMBER_ID = "variable_callnexus_outbound_member_id";
    public static final String VARIABLE_CALLNEXUS_DISPATCH_TASK_ID = "variable_callnexus_dispatch_task_id";
    public static final String VARIABLE_CALLNEXUS_DISPATCH_TARGET_ID = "variable_callnexus_dispatch_target_id";

    /**
     * CUSTOM 事件的子类标识头，mod_callcenter 队列事件形如 callcenter::call-coming。
     */
    public static final String EVENT_SUBCLASS = "Event-Subclass";

    /**
     * DTMF 事件按键头，单次事件代表一次按键。
     */
    public static final String DTMF_DIGIT = "DTMF-Digit";
    /**
     * DTMF 事件按键持续时间（采样数）。
     */
    public static final String DTMF_DURATION = "DTMF-Duration";
    /**
     * DTMF 事件按键来源（{@code endpoint}=来自终端、{@code inband}=带内识别、{@code app}=应用注入）。
     */
    public static final String DTMF_SOURCE = "DTMF-Source";

    /**
     * mod_callcenter 队列事件公共头。
     */
    public static final String CC_QUEUE = "CC-Queue";
    public static final String CC_CALLER_UUID = "CC-Caller-UUID";
    public static final String CC_AGENT = "CC-Agent";
    public static final String CC_CAUSE = "CC-Cause";
    public static final String CC_QUEUE_POSITION = "CC-Queue-Position";
    public static final String CC_CALLER_CID_NUMBER = "CC-Caller-CID-Number";
    public static final String CC_MEMBER_UUID = "CC-Member-UUID";
    public static final String VARIABLE_CC_MEMBER_SESSION_UUID = "variable_cc_member_session_uuid";
    public static final String VARIABLE_CC_MEMBER_UUID = "variable_cc_member_uuid";
    public static final String CALLNEXUS_BUSINESS_CALL_ID = "CallNexus-Business-Call-ID";
    public static final String CALLNEXUS_QUEUE_ID = "CallNexus-Queue-ID";
    public static final String CALLNEXUS_CUSTOMER_LEG_UUID = "CallNexus-Customer-Leg-UUID";
    public static final String CALLNEXUS_SATISFACTION_DIGIT = "CallNexus-Satisfaction-Digit";

    private EslHeaders() {
    }
}
