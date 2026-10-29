package cn.edu.fudan.dayu.copilot.application;

import java.util.*;
import java.util.function.*;

/**
 * Bounded model runtime. Tools contain only public business queries; identity never enters the
 * prompt.
 */
public interface AgentModel {
  record Tool(String name, String description, String schema, Function<String, String> execute) {}

  String run(String system, String input, List<Tool> tools, Consumer<String> delta);
}
