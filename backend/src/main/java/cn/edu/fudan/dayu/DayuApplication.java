package cn.edu.fudan.dayu;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 大禹平台后端的 Spring Boot 启动入口。
 */
@SpringBootApplication
public class DayuApplication {

    /**
     * 启动 Spring 容器并加载当前 Profile 下的应用组件。
     *
     * @param args 启动时传入的命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(DayuApplication.class, args);
    }
}
