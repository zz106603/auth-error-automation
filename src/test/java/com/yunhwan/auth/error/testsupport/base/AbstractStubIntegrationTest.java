package com.yunhwan.auth.error.testsupport.base;

import com.yunhwan.auth.error.testsupport.config.TestcontainersConfig;
import com.yunhwan.auth.error.usecase.outbox.port.OutboxScopeResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

@ActiveProfiles({"test", "stub"})
@SpringBootTest
@Import(TestcontainersConfig.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
public abstract class AbstractStubIntegrationTest {

    @Autowired(required = false)
    OutboxScopeResolver outboxScopeResolver;

    protected String scopePrefix;

    @BeforeEach
    void initScope() {
        scopePrefix = "T-" + UUID.randomUUID() + "-";
        if (outboxScopeResolver != null) outboxScopeResolver.set(scopePrefix);
    }

    @AfterEach
    void clearScope() {
        if (outboxScopeResolver != null) outboxScopeResolver.clear();
    }

    protected String scoped(String raw) {
        return scopePrefix + raw;
    }
}
