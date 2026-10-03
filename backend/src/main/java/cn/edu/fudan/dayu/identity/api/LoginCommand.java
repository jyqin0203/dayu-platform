package cn.edu.fudan.dayu.identity.api;

/**
 * 用户登录时提交的邮箱和明文密码。
 * 该对象不得写入日志，密码也不得出现在任何输出 DTO 中。
 */
public record LoginCommand(String email, String password) {
    @Override public String toString() { return "LoginCommand[redacted]"; }
}
