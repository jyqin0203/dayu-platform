package cn.edu.fudan.dayu.copilot.api;

import java.time.ZoneId;
import java.util.List;

/**
 * 用户提交给 Copilot 的自然语言、显示时区、页面上下文和短期对话上下文。
 */
public record CopilotCommand(
        String message, ZoneId displayZone, PageContext pageContext, List<ConversationMessage> recentMessages
) {
    public CopilotCommand {
        recentMessages = recentMessages == null ? List.of()
                : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(recentMessages));
    }
    @Override public String toString() { return "CopilotCommand[redacted]"; }
}
