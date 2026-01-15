package com.yunhwan.auth.error.domain.outbox.policy;

/**
 * outbox payload 직렬화 정책을 분리하기 위한 인터페이스.
 * JSON/Jackson, Avro/Protobuf 등으로 교체 가능하게 만든다.
 */
public interface PayloadSerializer {
    String serialize(Object payload);
}
