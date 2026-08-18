package com.example.task;

import com.example.task.config.TaskProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** 任务调度服务：XXL-JOB 执行器，承载定时缓存刷新任务。 */
@EnableConfigurationProperties(TaskProperties.class)
@MapperScan("com.example.task.mapper")
@SpringBootApplication(scanBasePackages = "com.example")
public class TaskApplication {

    public static void main(String[] args) {
        SpringApplication.run(TaskApplication.class, args);
    }
}
