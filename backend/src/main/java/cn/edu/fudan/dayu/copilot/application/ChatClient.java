package cn.edu.fudan.dayu.copilot.application;

/** Provider-neutral outbound port. Only sanitized public context crosses this boundary. */
public interface ChatClient {
    String complete(ChatRequest request);

    /** User/assistant text is untrusted data, never a tool or administrator command. */
    record ChatRequest(String systemPrompt, String userContent) {
        @Override public String toString() { return "ChatRequest[redacted]"; }
    }
}
