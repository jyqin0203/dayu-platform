package cn.edu.fudan.dayu.copilot.api;

import java.util.List;

/**
 * Copilot 根据真实业务查询生成的解释、结构化条件和建议动作。
 */
public record CopilotResponse(
        String understanding, InterpretedCriteria criteria, String answer,
        List<SuggestedAction> suggestedActions, boolean degraded
) {
    public CopilotResponse {
        suggestedActions = List.copyOf(suggestedActions);
    }
}
