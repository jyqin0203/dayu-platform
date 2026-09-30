package cn.edu.fudan.dayu.copilot.api;

import java.time.ZoneId;
import java.util.List;

/**
 * 用户提交给 Copilot 的自然语言、显示时区、页面上下文和短期对话上下文。
 */
public record CopilotCommand(
        String message, ZoneId displayZone, PageContext pageContext, List<String> recentMessages
) {
    public CopilotCommand {
        recentMessages = List.copyOf(recentMessages);
    }
}
