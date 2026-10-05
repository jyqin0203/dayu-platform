package cn.edu.fudan.dayu.copilot.api;

/**
 * 当前浏览器标签页内的一条短期对话消息。
 *
 * <p>角色只允许 USER 或 ASSISTANT；消息不会写入数据库，也不得携带身份、凭据或下载用途。</p>
 */
public record ConversationMessage(String role, String content) {
    public ConversationMessage {
        role = role == null ? null : role.toUpperCase(java.util.Locale.ROOT);
    }

    @Override public String toString() { return "ConversationMessage[redacted]"; }
}
