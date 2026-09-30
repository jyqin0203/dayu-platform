package cn.edu.fudan.dayu.identity.api;

/**
 * 用户注册时提交的邮箱、明文密码和所属单位。
 * 该对象不得写入日志，真实实现必须对密码进行安全哈希。
 */
public record RegisterCommand(String email, String password, String organization) {}
