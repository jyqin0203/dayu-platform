package cn.edu.fudan.dayu.copilot.application;

/** Safe infrastructure failure: never carries response bodies, credentials or provider URLs. */
public class AiUnavailableException extends RuntimeException {
    public AiUnavailableException() { super("AI provider temporarily unavailable"); }
}
