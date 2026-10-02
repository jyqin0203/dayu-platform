package cn.edu.fudan.dayu.copilot.application;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Configuration selects one adapter; no user text can select a provider, model or network address. */
public final class RoutingChatClient implements ChatClient {
    private final boolean enabled;
    private final String provider;
    private final Map<String, AiClient> clients;
    public RoutingChatClient(boolean enabled, String provider, List<AiClient> adapters) {
        this.enabled = enabled; this.provider = provider;
        var registered = new HashMap<String, AiClient>();
        for (AiClient adapter : adapters) {
            if (registered.putIfAbsent(adapter.provider(), adapter) != null)
                throw new IllegalArgumentException("Duplicate AI provider registration");
        }
        clients = Map.copyOf(registered);
    }
    @Override public String complete(ChatRequest request) {
        if (!enabled || !clients.containsKey(provider)) throw new AiUnavailableException();
        return clients.get(provider).complete(request);
    }
}
