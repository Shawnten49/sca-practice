package com.example.task.config;

import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** XXL-JOB 执行器配置：注册到本地 Admin（http://127.0.0.1:7080/），端口 9999。 */
@Configuration
public class XxlJobConfig {

    @Bean
    public XxlJobSpringExecutor xxlJobExecutor(
            @Value("${xxl.job.admin-addresses}") String adminAddresses,
            @Value("${xxl.job.appname}") String appname,
            @Value("${xxl.job.port}") int port,
            @Value("${xxl.job.log-path}") String logPath,
            @Value("${xxl.job.access-token:}") String accessToken) {
        XxlJobSpringExecutor executor = new XxlJobSpringExecutor();
        executor.setAdminAddresses(adminAddresses);
        executor.setAppname(appname);
        executor.setPort(port);
        executor.setAccessToken(accessToken);
        executor.setLogPath(logPath);
        return executor;
    }
}
