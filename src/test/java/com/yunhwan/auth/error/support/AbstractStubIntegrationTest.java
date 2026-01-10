package com.yunhwan.auth.error.support;

import com.yunhwan.auth.error.outbox.support.OutboxTestScope;
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
    OutboxTestScope outboxTestScope;

    protected String scopePrefix;

    @BeforeEach
    void initScope() {
        scopePrefix = "T-" + UUID.randomUUID() + "-";
        if (outboxTestScope != null) outboxTestScope.set(scopePrefix);
    }

    @AfterEach
    void clearScope() {
        if (outboxTestScope != null) outboxTestScope.clear();
    }

    protected String scoped(String raw) {
        return scopePrefix + raw;
    }
}
