package org.dromara.call.service;

public interface CallBusinessAssociationService {
    void associateCustomer(String businessCallId, Long customerId);

    void associateTicket(String businessCallId, Long ticketId, Long customerId);
}
