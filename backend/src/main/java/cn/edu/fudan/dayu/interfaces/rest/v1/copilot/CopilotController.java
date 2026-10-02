package cn.edu.fudan.dayu.interfaces.rest.v1.copilot;

import cn.edu.fudan.dayu.copilot.api.CopilotCommand;
import cn.edu.fudan.dayu.copilot.api.CopilotResponse;
import cn.edu.fudan.dayu.copilot.api.CopilotService;
import cn.edu.fudan.dayu.copilot.api.PageContext;
import cn.edu.fudan.dayu.interfaces.rest.v1.CurrentActorProvider;
import cn.edu.fudan.dayu.shared.kernel.ProductCode;
import cn.edu.fudan.dayu.shared.kernel.BusinessException;
import cn.edu.fudan.dayu.shared.kernel.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.ZoneId;
import java.time.zone.ZoneRulesException;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
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
    public Map<String, Object> query(@Valid @RequestBody QueryRequest body) {
        ZoneId zone = zone(body.displayZone());
        PageContext context = body.pageContext() == null ? null : new PageContext(
                body.pageContext().selectedProduct() == null ? null
                        : new ProductCode(body.pageContext().selectedProduct()),
                body.pageContext().visibleTime(),
                zone(body.pageContext().displayZone()));
        CopilotResponse response = copilot.query(new CopilotCommand(
                body.message(), zone, context, body.recentMessages()), actors.optional());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("understanding", response.understanding());
        if (response.criteria() == null) result.put("criteria", null);
        else {
            Map<String, Object> criteria = new LinkedHashMap<>();
            criteria.put("productCode", response.criteria().productCode() == null
                    ? null : response.criteria().productCode().value());
            criteria.put("dataMode", response.criteria().dataMode());
            criteria.put("from", response.criteria().from()); criteria.put("to", response.criteria().to());
            criteria.put("queryKind", response.criteria().queryKind());
            result.put("criteria", criteria);
        }
        result.put("answer", response.answer());
        result.put("suggestedActions", response.suggestedActions());
        result.put("degraded", response.degraded());
        return result;
    }

    private static ZoneId zone(String value) {
        try {
            return ZoneId.of(value);
        } catch (ZoneRulesException | NullPointerException error) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "invalid IANA time zone");
        }
    }

    public record QueryRequest(
            @NotBlank @Size(max = 2000) String message,
            @NotBlank String displayZone,
            PageContextRequest pageContext,
            @Size(max = 6) List<@Size(max = 2000) String> recentMessages) {
        public QueryRequest {
            recentMessages = recentMessages == null ? List.of() : List.copyOf(recentMessages);
        }
    }
    public record PageContextRequest(
            String selectedProduct, Instant visibleTime, String displayZone) {}
}
