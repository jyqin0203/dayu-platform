package cn.edu.fudan.dayu.copilot.application;

/** A registered vendor adapter; adding a provider does not change the Copilot business flow. */
public interface AiClient extends ChatClient {
    String provider();
}
