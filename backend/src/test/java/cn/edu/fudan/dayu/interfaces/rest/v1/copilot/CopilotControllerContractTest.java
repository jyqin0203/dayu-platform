package cn.edu.fudan.dayu.interfaces.rest.v1.copilot;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import cn.edu.fudan.dayu.copilot.api.*;
import cn.edu.fudan.dayu.interfaces.rest.v1.CurrentActorProvider;
import cn.edu.fudan.dayu.interfaces.rest.v1.V1ExceptionHandler;
import cn.edu.fudan.dayu.shared.kernel.ProductCode;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CopilotControllerContractTest {
    private final CopilotService copilot = mock(CopilotService.class);
    private MockMvc mvc;
    @BeforeEach void setup() {
        var actor = mock(CurrentActorProvider.class); when(actor.optional()).thenReturn(Optional.empty());
        mvc = MockMvcBuilders.standaloneSetup(new CopilotController(copilot, actor))
                .setControllerAdvice(new V1ExceptionHandler()).build();
    }
    @Test void nullableCriteriaAndProductHelpTimesSerializeWithoutMapNullFailures() throws Exception {
        when(copilot.query(any(), any())).thenReturn(new CopilotResponse("补充条件", null, "请选择产品", List.of(), false));
        mvc.perform(post("/api/v1/copilot/queries").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"查询\",\"displayZone\":\"UTC\",\"recentMessages\":[]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.criteria").isEmpty());
        when(copilot.query(any(), any())).thenReturn(new CopilotResponse("产品说明",
                new InterpretedCriteria(new ProductCode("PRECIP"), null, null, null, "PRODUCT_HELP"), "说明", List.of(), true));
        mvc.perform(post("/api/v1/copilot/queries").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"查询\",\"displayZone\":\"Asia/Shanghai\",\"pageContext\":{\"selectedProduct\":\"PRECIP\"},\"recentMessages\":[]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.criteria.from").isEmpty()).andExpect(jsonPath("$.degraded").value(true));
    }
    @Test void timezoneMismatchOffsetOnlyZoneAndNullHistoryAreRejected() throws Exception {
        for (String body : List.of(
                "{\"message\":\"query\",\"displayZone\":\"UTC\",\"pageContext\":{\"displayZone\":\"Asia/Shanghai\"}}",
                "{\"message\":\"query\",\"displayZone\":\"+08:00\"}",
                "{\"message\":\"query\",\"displayZone\":\"UTC\",\"recentMessages\":[null]}"))
            mvc.perform(post("/api/v1/copilot/queries").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnprocessableEntity());
        verifyNoInteractions(copilot);
    }
}
