package fu.coreservice.controller;

import fu.coreservice.dto.PageResponse;
import fu.coreservice.dto.chat.ChatMessageResponse;
import fu.coreservice.dto.chat.ChatSessionResponse;
import fu.coreservice.exception.AppException;
import fu.coreservice.exception.ErrorCode;
import fu.coreservice.exception.GlobalExceptionHandler;
import fu.coreservice.service.ChatSessionService;
import fu.coreservice.service.ChatMessageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AiChatControllerTest {
    private final ChatSessionService service = mock(ChatSessionService.class);
    private final ChatMessageService messageService = mock(ChatMessageService.class);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new AiChatController(service, messageService))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void replyIdIsBoundOnExistingSendRoute() throws Exception {
        when(messageService.sendMessage(eq(7L), eq(10L), any())).thenReturn(output ->
                output.write("event: done\ndata: {}\n\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var result = mvc.perform(post("/api/courses/7/chat/sessions/10/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Explain this\",\"replyToMessageId\":25}"))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk());
        verify(messageService).sendMessage(eq(7L), eq(10L), argThat(r -> r.getReplyToMessageId().equals(25L)));
    }

    @Test
    void nonPositiveReplyIdIsRejectedBeforeStartingStream() throws Exception {
        mvc.perform(post("/api/courses/7/chat/sessions/10/messages")
                        .accept(MediaType.TEXT_EVENT_STREAM).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Explain this\",\"replyToMessageId\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_001"));
        verifyNoInteractions(messageService);
    }

    @Test
    void legacyChatEndpointIsNoLongerAvailable() throws Exception {
        mvc.perform(post("/api/courses/7/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"OOP?\"}"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(service, messageService);
    }

    @Test
    void sendsSessionMessageAsAsyncSseOnExistingController() throws Exception {
        when(messageService.sendMessage(eq(7L), eq(10L), any())).thenReturn(output ->
                output.write("event: done\ndata: {}\n\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var result = mvc.perform(post("/api/courses/7/chat/sessions/10/messages")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"OOP?\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(header().string("X-Accel-Buffering", "no"))
                .andExpect(content().string("event: done\ndata: {}\n\n"));
    }

    @Test
    void blankMessageDoesNotStartStream() throws Exception {
        mvc.perform(post("/api/courses/7/chat/sessions/10/messages")
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_001"));
        verifyNoInteractions(messageService);
    }

    @Test
    void deniedSessionReturnsJsonEvenWhenClientAcceptsSse() throws Exception {
        when(messageService.sendMessage(eq(7L), eq(99L), any()))
                .thenThrow(new AppException(ErrorCode.CHAT_SESSION_NOT_FOUND));
        mvc.perform(post("/api/courses/7/chat/sessions/99/messages")
                        .accept(MediaType.TEXT_EVENT_STREAM).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"OOP?\"}"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.errorCode").value("CHAT_001"));
    }

    @Test
    void createsSessionUsingExistingControllerRouteAndResponseEnvelope() throws Exception {
        when(service.createSession(eq(7L), any())).thenReturn(ChatSessionResponse.builder()
                .sessionId(10L).courseId(7L).title("OOP").build());
        mvc.perform(post("/api/courses/7/chat/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"OOP\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.sessionId").value(10));
    }

    @Test
    void validatesTitleBeforeCallingService() throws Exception {
        mvc.perform(post("/api/courses/7/chat/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + "x".repeat(256) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_001"));
        verifyNoInteractions(service);
    }

    @Test
    void listsSessionsWithDefaultPagination() throws Exception {
        when(service.getMySessions(7L, 0, 10)).thenReturn(PageResponse.<ChatSessionResponse>builder()
                .content(List.of()).page(0).size(10).build());
        mvc.perform(get("/api/courses/7/chat/sessions"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content").isArray());
        verify(service).getMySessions(7L, 0, 10);
    }

    @Test
    void retrievesHistoryWithExplicitPagination() throws Exception {
        when(service.getMessages(7L, 10L, 1, 5)).thenReturn(PageResponse.<ChatMessageResponse>builder()
                .content(List.of()).page(1).size(5).build());
        mvc.perform(get("/api/courses/7/chat/sessions/10/messages?page=1&size=5"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.page").value(1));
        verify(service).getMessages(7L, 10L, 1, 5);
    }

    @Test
    void sessionAccessErrorUsesProjectsGlobalExceptionEnvelope() throws Exception {
        when(service.getMessages(7L, 99L, 0, 20))
                .thenThrow(new AppException(ErrorCode.CHAT_SESSION_NOT_FOUND));
        mvc.perform(get("/api/courses/7/chat/sessions/99/messages"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("CHAT_001"));
    }
}
