package com.yunhwan.auth.error.infra.messaging.rabbit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class RabbitTemplateConfig {

    /**
     * RabbitTemplate 전역 설정
     * - mandatory: true (라우팅 실패 시 Return 콜백 수신)
     * - ReturnsCallback: 라우팅 실패 시 로그 기록
     */
    @Bean
    public RabbitTemplateCustomizer rabbitTemplateCustomizer() {
        return template -> {
            template.setMandatory(true);
            template.setReturnsCallback(returned -> {
                var props = returned.getMessage().getMessageProperties();
                log.warn("[RabbitTemplate] Message RETURNED (Routing Failed). outboxId={}, correlationId={}, replyCode={}, replyText={}, exchange={}, routingKey={}",
                        props.getHeaders().get("outboxId"),
                        props.getCorrelationId(),
                        returned.getReplyCode(),
                        returned.getReplyText(),
                        returned.getExchange(),
                        returned.getRoutingKey()
                );
            });
        };
    }
}
