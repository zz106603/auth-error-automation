package com.yunhwan.auth.error.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
@Configuration
public class JpaAuditConfig {

    @Bean
    public DateTimeProvider auditingDateTimeProvider() {
        return () -> Optional.of(OffsetDateTime.now(ZoneOffset.UTC));
    }

}
