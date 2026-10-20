package fu.coreservice.exception;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import static org.junit.jupiter.api.Assertions.*;

class ChatAsyncExceptionHandlerTest {
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void timeoutBeforeCommitReturns503Json() {
        var response = handler.handleAsyncTimeout(new AsyncRequestTimeoutException(), new MockHttpServletResponse());
        assertNotNull(response);
        assertEquals(503, response.getStatusCode().value());
        assertEquals("CHAT_STREAM_TIMEOUT", response.getBody().getErrorCode());
    }

    @Test
    void timeoutAfterSseCommitDoesNotAppendJson() throws Exception {
        var servletResponse = new MockHttpServletResponse();
        servletResponse.setContentType("text/event-stream");
        servletResponse.getWriter().write("event: token\ndata: {}\n\n");
        servletResponse.flushBuffer();
        assertNull(handler.handleAsyncTimeout(new AsyncRequestTimeoutException(), servletResponse));
        assertEquals("event: token\ndata: {}\n\n", servletResponse.getContentAsString());
    }
}
