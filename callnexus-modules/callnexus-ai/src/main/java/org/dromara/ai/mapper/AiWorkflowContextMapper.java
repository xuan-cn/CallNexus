package org.dromara.ai.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.ai.domain.response.AiWorkflowCustomerContext;
import org.dromara.ai.domain.response.AiWorkflowCustomerField;

import java.util.List;

public interface AiWorkflowContextMapper {

    @Select("""
        SELECT
            call_session.caller_number AS phone,
            customer.id AS customerId,
            customer.customer_name AS customerName,
            customer.template_id AS templateId,
            submission.form_data AS formData
        FROM cc_call_session call_session
        LEFT JOIN cc_customer customer
            ON customer.tenant_id = call_session.tenant_id
            AND customer.deleted = 0
            AND customer.id = COALESCE(
                call_session.customer_id,
                (
                    SELECT phone.customer_id
                    FROM cc_customer_phone phone
                    WHERE phone.tenant_id = call_session.tenant_id
                        AND phone.enabled = 1
                        AND phone.normalized_phone = call_session.caller_number
                    ORDER BY phone.primary_flag DESC, phone.sort_order ASC, phone.id ASC
                    LIMIT 1
                ),
                (
                    SELECT candidate.id
                    FROM cc_customer candidate
                    WHERE candidate.tenant_id = call_session.tenant_id
                        AND candidate.deleted = 0
                        AND candidate.primary_phone = call_session.caller_number
                    ORDER BY candidate.id ASC
                    LIMIT 1
                )
            )
        LEFT JOIN cc_form_submission submission
            ON submission.tenant_id = customer.tenant_id
            AND submission.business_type = 'CUSTOMER'
            AND submission.business_id = customer.id
            AND submission.deleted = 0
        WHERE call_session.tenant_id = #{tenantId}
            AND call_session.business_call_id = #{businessCallId}
        LIMIT 1
        """)
    AiWorkflowCustomerContext findInboundCustomer(@Param("tenantId") String tenantId,
                                                   @Param("businessCallId") String businessCallId);

    @Select("""
        SELECT
            member.phone_number AS phone,
            member.customer_id AS customerId,
            COALESCE(customer.customer_name, member.customer_name) AS customerName,
            customer.template_id AS templateId,
            submission.form_data AS formData,
            task.id AS outboundTaskId,
            task.task_name AS outboundTaskName,
            member.id AS outboundMemberId,
            member.attempt_count AS outboundAttemptCount
        FROM cc_outbound_member member
        JOIN cc_outbound_task task
            ON task.id = member.task_id
            AND task.tenant_id = member.tenant_id
            AND task.deleted = 0
        LEFT JOIN cc_customer customer
            ON customer.id = member.customer_id
            AND customer.tenant_id = member.tenant_id
            AND customer.deleted = 0
        LEFT JOIN cc_form_submission submission
            ON submission.tenant_id = customer.tenant_id
            AND submission.business_type = 'CUSTOMER'
            AND submission.business_id = customer.id
            AND submission.deleted = 0
        WHERE member.tenant_id = #{tenantId}
            AND member.business_call_id = #{businessCallId}
            AND member.deleted = 0
        ORDER BY submission.id DESC
        LIMIT 1
        """)
    AiWorkflowCustomerContext findOutboundCustomer(@Param("tenantId") String tenantId,
                                                    @Param("businessCallId") String businessCallId);

    @Select("""
        SELECT
            customer.primary_phone AS phone,
            customer.id AS customerId,
            customer.customer_name AS customerName,
            customer.template_id AS templateId,
            submission.form_data AS formData
        FROM cc_customer customer
        LEFT JOIN cc_customer_phone phone
            ON phone.tenant_id = customer.tenant_id
            AND phone.customer_id = customer.id
            AND phone.enabled = 1
        LEFT JOIN cc_form_submission submission
            ON submission.tenant_id = customer.tenant_id
            AND submission.business_type = 'CUSTOMER'
            AND submission.business_id = customer.id
            AND submission.deleted = 0
        WHERE customer.tenant_id = #{tenantId}
            AND customer.deleted = 0
            AND (customer.primary_phone = #{phone} OR phone.normalized_phone = #{phone})
        ORDER BY phone.primary_flag DESC, phone.sort_order ASC, submission.id DESC
        LIMIT 1
        """)
    AiWorkflowCustomerContext findCustomerByPhone(@Param("tenantId") String tenantId,
                                                   @Param("phone") String phone);

    @Select("""
        SELECT
            customer.primary_phone AS phone,
            customer.id AS customerId,
            customer.customer_name AS customerName,
            customer.template_id AS templateId,
            submission.form_data AS formData
        FROM cc_customer customer
        LEFT JOIN cc_form_submission submission
            ON submission.tenant_id = customer.tenant_id
            AND submission.business_type = 'CUSTOMER'
            AND submission.business_id = customer.id
            AND submission.deleted = 0
        WHERE customer.tenant_id = #{tenantId}
            AND customer.id = #{customerId}
            AND customer.deleted = 0
        ORDER BY submission.id DESC
        LIMIT 1
        """)
    AiWorkflowCustomerContext findCustomerById(@Param("tenantId") String tenantId,
                                                @Param("customerId") Long customerId);

    @Select("""
        SELECT field_code AS fieldCode, field_name AS fieldName, field_type AS fieldType
        FROM cc_form_field
        WHERE tenant_id = #{tenantId}
            AND template_id = #{templateId}
            AND enabled = 1
            AND deleted = 0
        ORDER BY sort_order ASC, id ASC
        """)
    List<AiWorkflowCustomerField> findCustomerFields(@Param("tenantId") String tenantId,
                                                     @Param("templateId") Long templateId);

    @org.apache.ibatis.annotations.Update("""
        UPDATE cc_customer
        SET customer_name = #{customerName}, update_time = CURRENT_TIMESTAMP
        WHERE tenant_id = #{tenantId} AND id = #{customerId} AND deleted = 0
        """)
    int updateCustomerName(@Param("tenantId") String tenantId,
                           @Param("customerId") Long customerId,
                           @Param("customerName") String customerName);

    @org.apache.ibatis.annotations.Update("""
        UPDATE cc_form_submission
        SET form_data = CAST(#{formData} AS JSON), update_time = CURRENT_TIMESTAMP
        WHERE tenant_id = #{tenantId}
            AND business_type = 'CUSTOMER'
            AND business_id = #{customerId}
            AND deleted = 0
        """)
    int updateCustomerFormData(@Param("tenantId") String tenantId,
                               @Param("customerId") Long customerId,
                               @Param("formData") String formData);
}
