package cn.edu.fudan.dayu.copilot.api;

import java.util.Map;

/**
 * Copilot 建议前端展示的可选动作及其安全参数。
 * 动作只是建议，不会由 Copilot 直接执行下载或管理操作。
 */
public record SuggestedAction(String type, String label, Map<String, String> parameters) {
    public SuggestedAction {
        parameters = Map.copyOf(parameters);
    }
}
