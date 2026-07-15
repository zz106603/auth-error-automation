package com.yunhwan.auth.error.app.autherror;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("[13] Auth failure context normalizer")
class AuthFailureContextNormalizerTest {

    @Test
    @DisplayName("provider/client/userAgentFamily는 집계 가능한 토큰으로 정규화한다")
    void token_context는_정규화한다() {
        assertThat(AuthFailureContextNormalizer.normalizeProvider(" internal-auth "))
                .isEqualTo("INTERNAL_AUTH");
        assertThat(AuthFailureContextNormalizer.normalizeClientType("mobile app"))
                .isEqualTo("MOBILE_APP");
        assertThat(AuthFailureContextNormalizer.normalizeUserAgentFamily("Chrome/Stable"))
                .isEqualTo("CHROME/STABLE");
    }

    @Test
    @DisplayName("endpoint는 query string을 제거하고 route 중심으로 저장한다")
    void endpoint는_query_string을_제거한다() {
        assertThat(AuthFailureContextNormalizer.normalizeEndpoint("/api//login?token=secret"))
                .isEqualTo("/api/login");
    }

    @Test
    @DisplayName("hash 필드는 SHA-256 hex만 허용하고 raw 값은 저장하지 않는다")
    void hash는_sha256_hex만_허용한다() {
        String valid = "abcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcd";

        assertThat(AuthFailureContextNormalizer.normalizeHash(valid.toUpperCase()))
                .isEqualTo(valid);
        assertThat(AuthFailureContextNormalizer.normalizeHash("raw-user@example.com"))
                .isNull();
    }
}
