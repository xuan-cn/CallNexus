package org.dromara.ai.service;

import org.dromara.ai.domain.AiTicketDraft;
import org.dromara.ai.domain.request.AiTicketDraftReviewRequest;
import org.dromara.ai.mapper.*;
import org.dromara.ai.service.impl.AiAgentAssistServiceImpl;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@Tag("dev")
class AiAgentAssistTicketDraftTest {

    @Test
    void shouldApproveOnlyDraftBelongingToCurrentCall() {
        AiTicketDraftMapper draftMapper = mock(AiTicketDraftMapper.class);
        AiTicketDraftReviewService reviewService = mock(AiTicketDraftReviewService.class);
        AiAgentAssistServiceImpl service = service(draftMapper, reviewService);
        AiTicketDraft draft = new AiTicketDraft();
        draft.setId(10L);
        draft.setSourceCallId("call-1");
        when(draftMapper.selectById(10L)).thenReturn(draft);
        when(reviewService.approve(eq(10L), any())).thenReturn(99L);

        assertThat(service.approveTicketDraft("call-1", 10L, 3)).isEqualTo(99L);
        ArgumentCaptor<AiTicketDraftReviewRequest> captor = ArgumentCaptor.forClass(AiTicketDraftReviewRequest.class);
        verify(reviewService).approve(eq(10L), captor.capture());
        assertThat(captor.getValue().getVersion()).isEqualTo(3);
        assertThat(captor.getValue().getReason()).isEqualTo("坐席工作台确认");

        assertThatThrownBy(() -> service.approveTicketDraft("call-2", 10L, 3))
            .isInstanceOf(ServiceException.class);
    }

    private AiAgentAssistServiceImpl service(AiTicketDraftMapper draftMapper,
                                             AiTicketDraftReviewService reviewService) {
        return new AiAgentAssistServiceImpl(mock(AiAgentAssistSessionMapper.class),
            mock(AiAgentAssistSuggestionMapper.class), mock(AiCallTranscriptSegmentMapper.class), draftMapper,
            mock(AiAgentMapper.class), mock(AiAgentApplicationService.class), mock(AiAgentAssistStreamService.class),
            reviewService);
    }
}
