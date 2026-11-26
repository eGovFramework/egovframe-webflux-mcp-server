package com.example.webflux.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

/**
 * app.security.api-keys 설정을 바인딩한다.
 * 빈 문자열이 섞여 있을 수 있으므로, 유효 여부는 isValidKey() 로 판정한다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.security")
public class SecurityProperties {

    private List<String> apiKeys = List.of();

    /**
     * 유효한 키가 하나라도 등록돼 있으면 true.
     *
     * 인증 실패 사유를 가르는 데 쓴다. 등록된 키가 없으면 어떤 요청도 통과할 수 없으므로
     * '클라이언트가 틀렸다' 가 아니라 '서버가 설정되지 않았다' 가 맞는 진단이다.
     * 환경변수 MCP_API_KEY 미설정 시 목록이 [""] 가 되므로 공백만으로도 이 상태가 된다.
     */
    public boolean hasConfiguredKey() {
        return apiKeys.stream().anyMatch(key -> key != null && !key.isBlank());
    }

    /** key가 설정 목록에 있고 비어 있지 않으면 true. 타이밍 공격 방지를 위해 상수 시간으로 비교한다. */
    public boolean isValidKey(String key) {
        if (key == null || key.isBlank()) return false;
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        return apiKeys.stream()
                .anyMatch(storedKey -> !storedKey.isBlank()
                        && MessageDigest.isEqual(storedKey.getBytes(StandardCharsets.UTF_8), keyBytes));
    }
}
