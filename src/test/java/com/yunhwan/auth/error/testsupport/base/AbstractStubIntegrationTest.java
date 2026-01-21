package com.yunhwan.auth.error.testsupport.base;

import com.yunhwan.auth.error.infra.messaging.rabbit.RabbitTopologyConfig;
import com.yunhwan.auth.error.testsupport.config.TestFailInjectionConfig;
import com.yunhwan.auth.error.testsupport.config.TestcontainersConfig;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

@ActiveProfiles({"test", "stub"})
@SpringBootTest
@Import({
        TestcontainersConfig.class,
        RabbitTopologyConfig.class,
        TestFailInjectionConfig.class})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
public abstract class AbstractStubIntegrationTest {

    protected String newTestScope() {
        return "T-" + UUID.randomUUID() + "-";
    }

}
