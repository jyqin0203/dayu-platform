package cn.edu.fudan.dayu.interfaces.rest.v1.copilot;

import cn.edu.fudan.dayu.copilot.api.CopilotCommand;
import cn.edu.fudan.dayu.copilot.api.CopilotResponse;
import cn.edu.fudan.dayu.copilot.api.CopilotService;
import cn.edu.fudan.dayu.copilot.api.ConversationMessage;
import cn.edu.fudan.dayu.copilot.api.PageContext;
import cn.edu.fudan.dayu.copilot.api.InterpretedCriteria;
import cn.edu.fudan.dayu.copilot.api.SuggestedAction;
import cn.edu.fudan.dayu.interfaces.rest.v1.CurrentActorProvider;
import cn.edu.fudan.dayu.shared.kernel.ProductCode;
import cn.edu.fudan.dayu.shared.kernel.BusinessException;
import cn.edu.fudan.dayu.shared.kernel.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 自然语言数据查询的 HTTP 适配器；不执行下载和管理操作。 */
@RestController
@RequestMapping("/api/v1/copilot")
public class CopilotController {
    private final CopilotService copilot;
    private final CurrentActorProvider actors;

    public CopilotController(CopilotService copilot, CurrentActorProvider actors) {
        this.copilot = copilot;
        this.actors = actors;
    }

    @PostMapping("/queries")
    public QueryResponse query(@Valid @RequestBody QueryRequest body) {
        ZoneId zone = zone(body.displayZone());
        PageContext context = body.pageContext() == null ? null : new PageContext(
                body.pageContext().selectedProduct() == null ? null
                        : new ProductCode(body.pageContext().selectedProduct()),
                body.pageContext().visibleTime(),
                body.pageContext().displayZone() == null ? zone : zone(body.pageContext().displayZone()));
        if (context != null && !context.displayZone().equals(zone))
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "页面时区与请求显示时区必须一致");
        CopilotResponse response = copilot.query(new CopilotCommand(
                body.message(), zone, context, body.recentMessages().stream()
                        .map(message -> new ConversationMessage(message.role(), message.content())).toList()), actors.optional());
        return new QueryResponse(response.understanding(), CriteriaResponse.from(response.criteria()), response.answer(),
                response.suggestedActions(), response.degraded());
    }

    private static ZoneId zone(String value) {
        try {
            if (!ZoneId.getAvailableZoneIds().contains(value)) throw new IllegalArgumentException();
            return ZoneId.of(value);
        } catch (java.time.DateTimeException | IllegalArgumentException | NullPointerException error) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "invalid IANA time zone");
        }
    }

    public record QueryRequest(
            @NotBlank @Size(max = 2000) String message,
            @NotBlank String displayZone,
            @Valid PageContextRequest pageContext,
            @Size(max = 12) List<@jakarta.validation.constraints.NotNull @Valid ConversationMessageRequest> recentMessages) {
        public QueryRequest {
            recentMessages = recentMessages == null ? List.of()
                    : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(recentMessages));
        }
        @Override public String toString() { return "CopilotQueryRequest[redacted]"; }
    }
    public record ConversationMessageRequest(
            @NotBlank @jakarta.validation.constraints.Pattern(regexp = "USER|ASSISTANT") String role,
            @NotBlank @Size(max = 2000) String content) {}
    public record PageContextRequest(
            @Size(max = 64) String selectedProduct, Instant visibleTime, @Size(max = 100) String displayZone) {}
    public record CriteriaResponse(String productCode, cn.edu.fudan.dayu.shared.kernel.DataMode dataMode,
            Instant from, Instant to, String queryKind, Instant cycleTime, Integer leadMinutes, String interpretedZone) {
        static CriteriaResponse from(InterpretedCriteria criteria) {
            return criteria == null ? null : new CriteriaResponse(criteria.productCode() == null ? null : criteria.productCode().value(),
                    criteria.dataMode(), criteria.from(), criteria.to(), criteria.queryKind(), criteria.cycleTime(), criteria.leadMinutes(),
                    criteria.interpretedZone());
        }
    }
    public record QueryResponse(String understanding, CriteriaResponse criteria, String answer,
            List<SuggestedAction> suggestedActions, boolean degraded) {}
}
