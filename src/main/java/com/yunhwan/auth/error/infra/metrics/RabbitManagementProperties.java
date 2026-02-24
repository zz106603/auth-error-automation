package com.yunhwan.auth.error.infra.metrics;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "rabbitmq.management")
public class RabbitManagementProperties {

    // MQ 상태 수집용 (STOP 5.5)
    private String baseUrl;
    // 관리 API 인증용 (환경변수로 주입)
    private String username;
    private String password;
    // vhost 기준 큐 통계 수집
    private String vhost;
}
