package org.dromara.call.constant;

public final class EslHeaders {
    public static final String EVENT_NAME = "Event-Name";
    public static final String UNIQUE_ID = "Unique-ID";
    public static final String CHANNEL_CALL_UUID = "Channel-Call-UUID";
    public static final String CALLER_CALLER_ID_NUMBER = "Caller-Caller-ID-Number";
    public static final String CALLER_USERNAME = "Caller-Username";
    public static final String CALLER_DESTINATION_NUMBER = "Caller-Destination-Number";
    public static final String VARIABLE_SIP_TO_USER = "variable_sip_to_user";
    public static final String HANGUP_CAUSE = "Hangup-Cause";
    public static final String OTHER_LEG_UNIQUE_ID = "Other-Leg-Unique-ID";
    public static final String BRIDGE_A_UNIQUE_ID = "Bridge-A-Unique-ID";
    public static final String BRIDGE_B_UNIQUE_ID = "Bridge-B-Unique-ID";
    public static final String VARIABLE_ORIGINATION_UUID = "variable_origination_uuid";
    public static final String VARIABLE_BRIDGE_UUID = "variable_bridge_uuid";
    public static final String VARIABLE_CALLNEXUS_BUSINESS_CALL_ID = "variable_callnexus_business_call_id";
    public static final String VARIABLE_CALLNEXUS_DIRECTION = "variable_callnexus_direction";
    public static final String VARIABLE_CALLNEXUS_ORIGINAL_CALLER = "variable_callnexus_original_caller";
    public static final String VARIABLE_CALLNEXUS_ORIGINAL_CALLED = "variable_callnexus_original_called";
    public static final String VARIABLE_CALLNEXUS_CUSTOMER_ID = "variable_callnexus_customer_id";
    public static final String VARIABLE_CALLNEXUS_OUTBOUND_TASK_ID = "variable_callnexus_outbound_task_id";
    public static final String VARIABLE_CALLNEXUS_OUTBOUND_MEMBER_ID = "variable_callnexus_outbound_member_id";

    /**
     * CUSTOM 事件的子类标识头，mod_callcenter 队列事件形如 callcenter::call-coming。
     */
    public static final String EVENT_SUBCLASS = "Event-Subclass";

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

    private EslHeaders() {
    }
}
