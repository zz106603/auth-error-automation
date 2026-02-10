package com.yunhwan.auth.error;

import com.yunhwan.auth.error.testsupport.base.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@Tag("smoke")
@Tag("non-scenario")
@DisplayName("[Smoke] Spring context bootstraps successfully")
@SpringBootTest
class AuthErrorAutomationApplicationTests extends AbstractIntegrationTest {

	@Test
	void contextLoads() {
	}

}
