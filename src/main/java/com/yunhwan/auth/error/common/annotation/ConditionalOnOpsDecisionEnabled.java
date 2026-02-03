package com.yunhwan.auth.error.common.annotation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ ElementType.TYPE, ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Profile("ops")
@ConditionalOnProperty(name = "auth-error.ops.decision.enabled", havingValue = "true")
public @interface ConditionalOnOpsDecisionEnabled {
}
