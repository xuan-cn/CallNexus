package org.dromara.ai.service;

import org.dromara.ai.domain.AiTicketDraft;
import org.dromara.ai.domain.request.AiTicketDraftReviewRequest;
import org.dromara.ai.domain.request.AiTicketDraftUpdateRequest;
import org.dromara.ai.domain.response.AiTicketDraftResponse;
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

    @Test
    void shouldUpdateCurrentCallDraftAndPublishLatestVersion() {
        AiTicketDraftMapper draftMapper = mock(AiTicketDraftMapper.class);
        AiTicketDraftReviewService reviewService = mock(AiTicketDraftReviewService.class);
        AiAgentAssistStreamService streamService = mock(AiAgentAssistStreamService.class);
        AiAgentAssistServiceImpl service = service(draftMapper, reviewService, streamService);
        AiTicketDraft draft = new AiTicketDraft();
        draft.setId(10L);
        draft.setSourceCallId("call-1");
        when(draftMapper.selectById(10L)).thenReturn(draft);
        AiTicketDraftResponse saved = new AiTicketDraftResponse();
        saved.setId(10L);
        saved.setVersion(4);
        when(reviewService.get(10L)).thenReturn(saved);
        AiTicketDraftUpdateRequest request = new AiTicketDraftUpdateRequest();
        request.setVersion(3);
        request.setTitle("人工补充标题");

        assertThat(service.updateTicketDraft("call-1", 10L, request)).isSameAs(saved);
        verify(reviewService).update(10L, request);
        verify(streamService).publishTicketDraft(anyString(), eq("call-1"), same(saved));

        assertThatThrownBy(() -> service.updateTicketDraft("call-2", 10L, request))
            .isInstanceOf(ServiceException.class);
    }

    private AiAgentAssistServiceImpl service(AiTicketDraftMapper draftMapper,
                                             AiTicketDraftReviewService reviewService) {
        return service(draftMapper, reviewService, mock(AiAgentAssistStreamService.class));
    }

    private AiAgentAssistServiceImpl service(AiTicketDraftMapper draftMapper,
                                             AiTicketDraftReviewService reviewService,
                                             AiAgentAssistStreamService streamService) {
        return new AiAgentAssistServiceImpl(mock(AiAgentAssistSessionMapper.class),
            mock(AiAgentAssistSuggestionMapper.class), mock(AiCallTranscriptSegmentMapper.class), draftMapper,
            mock(AiAgentMapper.class), mock(AiAgentApplicationService.class), streamService,
            reviewService);
    }
}
