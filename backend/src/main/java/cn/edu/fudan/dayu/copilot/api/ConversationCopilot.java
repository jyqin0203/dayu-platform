package cn.edu.fudan.dayu.copilot.api;

import java.util.function.BiConsumer;

/**
 * Session-owned ephemeral conversation. Owner is server-derived and must never be sent to a model.
 */
public interface ConversationCopilot {
  String create(String owner);

  void clear(String owner, String conversation);

  void query(
      String owner, String conversation, CopilotCommand command, BiConsumer<String, Object> events);
}
