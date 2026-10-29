package cn.edu.fudan.dayu.interfaces.rest.v1.copilot;

import cn.edu.fudan.dayu.copilot.api.*;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * POST streams retain same-origin session and CSRF protection. No identity is serialized to models.
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression(
    "${dayu.copilot.enabled:false} and ${dayu.copilot.conversations.enabled:false}")
@RestController
@Profile("!skeleton")
@RequestMapping("/api/v1/copilot/conversations")
public class ConversationController {
  private final ConversationCopilot service;
  private final ThreadPoolExecutor workers =
      new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new SynchronousQueue<>());
  private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor();

  public ConversationController(ConversationCopilot service) {
    this.service = service;
  }

  @PostMapping
  public Map<String, String> create(HttpSession session) {
    return Map.of("conversationId", service.create(session.getId()));
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
  public void clear(@PathVariable String id, HttpSession session) {
    service.clear(session.getId(), id);
  }

  @PostMapping(value = "/{id}/stream", produces = "text/event-stream")
  public SseEmitter stream(
      @PathVariable String id,
      @Valid @RequestBody CopilotController.QueryRequest body,
      HttpSession session,
      HttpServletResponse response) {
    ZoneId zone = CopilotController.zone(body.displayZone());
    PageContext page =
        body.pageContext() == null
            ? null
            : new PageContext(
                body.pageContext().selectedProduct() == null
                    ? null
                    : new cn.edu.fudan.dayu.shared.kernel.ProductCode(
                        body.pageContext().selectedProduct()),
                body.pageContext().visibleTime(),
                zone);
    if (body.pageContext() != null
        && body.pageContext().displayZone() != null
        && !CopilotController.zone(body.pageContext().displayZone()).equals(zone))
      throw new cn.edu.fudan.dayu.shared.kernel.BusinessException(
          cn.edu.fudan.dayu.shared.kernel.ErrorCode.VALIDATION_FAILED, "页面时区与请求显示时区必须一致");
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("X-Accel-Buffering", "no");
    var emitter = new SseEmitter(50000L);
    var task = new AtomicReference<Future<?>>();
    String owner = session.getId();
    Runnable cancel =
        () -> {
          var f = task.get();
          if (f != null) f.cancel(true);
        };
    var heartbeat =
        heartbeats.scheduleAtFixedRate(
            () -> {
              try {
                emitter.send(SseEmitter.event().comment("keepalive"));
              } catch (Exception disconnected) {
                cancel.run();
              }
            },
            2,
            2,
            TimeUnit.SECONDS);
    emitter.onTimeout(
        () -> {
          heartbeat.cancel(false);
          cancel.run();
        });
    emitter.onError(
        e -> {
          heartbeat.cancel(false);
          cancel.run();
        });
    emitter.onCompletion(
        () -> {
          heartbeat.cancel(false);
          cancel.run();
        });
    try {
      task.set(
          workers.submit(
              () -> {
                try {
                  service.query(
                      owner,
                      id,
                      new CopilotCommand(body.message(), zone, page, List.of()),
                      (event, value) -> {
                        try {
                          if (value instanceof CopilotResponse result)
                            value =
                                new CopilotController.QueryResponse(
                                    result.understanding(),
                                    CopilotController.CriteriaResponse.from(result.criteria()),
                                    result.answer(),
                                    result.suggestedActions(),
                                    result.degraded());
                          emitter.send(SseEmitter.event().name(event).data(value));
                        } catch (java.io.IOException e) {
                          throw new java.io.UncheckedIOException(e);
                        }
                      });
                  emitter.complete();
                } catch (Exception failure) {
                  try {
                    String code =
                        failure instanceof cn.edu.fudan.dayu.shared.kernel.BusinessException b
                            ? b.errorCode().name()
                            : "AI_UNAVAILABLE";
                    emitter.send(
                        SseEmitter.event()
                            .name("error")
                            .data(Map.of("code", code, "message", "本次回答未完成，请重试；会话失效时请清空重新开始")));
                    emitter.complete();
                  } catch (java.io.IOException ignored) {
                    emitter.completeWithError(failure);
                  }
                }
              }));
    } catch (RejectedExecutionException busy) {
      heartbeat.cancel(false);
      throw new cn.edu.fudan.dayu.shared.kernel.BusinessException(
          cn.edu.fudan.dayu.shared.kernel.ErrorCode.RATE_LIMITED, "AI繁忙，请稍后重试");
    }
    return emitter;
  }

  @jakarta.annotation.PreDestroy
  public void close() {
    workers.shutdownNow();
    heartbeats.shutdownNow();
  }
}
