package cn.edu.fudan.dayu.interfaces.rest.v1.copilot;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.edu.fudan.dayu.copilot.api.ConversationCopilot;
import cn.edu.fudan.dayu.interfaces.rest.v1.V1ExceptionHandler;
import java.util.Map;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ConversationControllerTest {
  private final ConversationCopilot service = mock(ConversationCopilot.class);
  private final ConversationController controller = new ConversationController(service);
  private final MockHttpSession session = new MockHttpSession();
  private final MockMvc mvc =
      MockMvcBuilders.standaloneSetup(controller)
          .setControllerAdvice(new V1ExceptionHandler())
          .build();

  @AfterEach
  void close() {
    controller.close();
  }

  @Test
  void createAndClearUseServerSessionIdentity() throws Exception {
    when(service.create(session.getId())).thenReturn("fixture-id");
    mvc.perform(post("/api/v1/copilot/conversations").session(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.conversationId").value("fixture-id"));
    mvc.perform(delete("/api/v1/copilot/conversations/fixture-id").session(session))
        .andExpect(status().isNoContent());
    verify(service).clear(session.getId(), "fixture-id");
  }

  @Test
  void invalidZoneAndMismatchedPageZoneFailBeforeStartingStream() throws Exception {
    for (String body :
        new String[] {
          "{\"message\":\"hi\",\"displayZone\":\"bad/zone\"}",
          "{\"message\":\"hi\",\"displayZone\":\"UTC\",\"pageContext\":{\"displayZone\":\"Asia/Shanghai\"}}"
        })
      mvc.perform(
              post("/api/v1/copilot/conversations/id/stream")
                  .session(session)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isUnprocessableEntity());
    verifyNoInteractions(service);
  }

  @Test
  void streamsEventsWithNoBufferingHeaders() throws Exception {
    doAnswer(
            call -> {
              BiConsumer<String, Object> emit = call.getArgument(3);
              emit.accept("answer_delta", Map.of("text", "降水"));
              emit.accept("done", Map.of("completed", true));
              return null;
            })
        .when(service)
        .query(eq(session.getId()), eq("id"), any(), any());
    var result =
        mvc.perform(
                post("/api/v1/copilot/conversations/id/stream")
                    .session(session)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"message\":\"hi\",\"displayZone\":\"UTC\"}"))
            .andExpect(request().asyncStarted())
            .andExpect(header().string("X-Accel-Buffering", "no"))
            .andReturn();
    mvc.perform(asyncDispatch(result))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("event:done")));
  }
}
