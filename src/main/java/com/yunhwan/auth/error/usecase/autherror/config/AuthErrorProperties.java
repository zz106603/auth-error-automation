package com.yunhwan.auth.error.usecase.autherror.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "auth-error")
public class AuthErrorProperties {

    /**
     * 이벤트/에러 발생 주체 서비스명
     * ex) auth-error-api
     */
    private String sourceService;

    /**
     * 실행 환경
     * ex) local / dev / prod
     */
    private String environment;
}
