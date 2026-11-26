package com.example.webflux.config;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SecurityProperties 단위 테스트.
 */
class SecurityPropertiesTest {

    @Test
    @DisplayName("등록된 키와 일치하면 유효하다")
    void validKeyMatchesRegisteredKey() {
        SecurityProperties props = withApiKeys("secret-key-1");

        assertThat(props.isValidKey("secret-key-1")).isTrue();
    }

    @Test
    @DisplayName("등록되지 않은 키는 유효하지 않다")
    void unlistedKeyIsInvalid() {
        SecurityProperties props = withApiKeys("secret-key-1");

        assertThat(props.isValidKey("wrong-key")).isFalse();
    }

    @Test
    @DisplayName("null 키는 유효하지 않다")
    void nullKeyIsInvalid() {
        SecurityProperties props = withApiKeys("secret-key-1");

        assertThat(props.isValidKey(null)).isFalse();
    }

    @Test
    @DisplayName("빈 문자열/공백 키는 유효하지 않다")
    void blankKeyIsInvalid() {
        SecurityProperties props = withApiKeys("secret-key-1");

        assertThat(props.isValidKey("")).isFalse();
        assertThat(props.isValidKey("   ")).isFalse();
    }

    @Test
    @DisplayName("유효 키 목록이 비어 있으면 어떤 키도 통과하지 못한다")
    void emptyKeyListRejectsEverything() {
        SecurityProperties props = withApiKeys();

        assertThat(props.isValidKey("secret-key-1")).isFalse();
    }

    @Test
    @DisplayName("목록에 빈 문자열이 섞여 있어도 그 항목만으로는 통과하지 못한다")
    void blankEntryInListDoesNotMatchAnything() {
        SecurityProperties props = withApiKeys("", "secret-key-1");

        assertThat(props.isValidKey("")).isFalse();
        assertThat(props.isValidKey("secret-key-1")).isTrue();
    }

    @Test
    @DisplayName("여러 키가 등록된 경우 그 중 하나와 일치하면 유효하다")
    void matchesAnyOfMultipleRegisteredKeys() {
        SecurityProperties props = withApiKeys("key-a", "key-b", "key-c");

        assertThat(props.isValidKey("key-b")).isTrue();
        assertThat(props.isValidKey("key-z")).isFalse();
    }

    @Test
    @DisplayName("등록된 키의 부분 문자열은 유효하지 않다")
    void partialMatchIsInvalid() {
        SecurityProperties props = withApiKeys("secret-key-1");

        assertThat(props.isValidKey("secret-key")).isFalse();
        assertThat(props.isValidKey("secret-key-12")).isFalse();
    }

    // hasConfiguredKey: 인증 실패 사유를 가르는 데 쓴다

    @Test
    @DisplayName("유효한 키가 하나라도 있으면 설정된 상태다")
    void hasConfiguredKeyWhenAnyKeyPresent() {
        assertThat(withApiKeys("secret-key-1").hasConfiguredKey()).isTrue();
        assertThat(withApiKeys("", "secret-key-1").hasConfiguredKey()).isTrue();
    }

    @Test
    @DisplayName("목록이 비어 있으면 설정되지 않은 상태다")
    void hasNoConfiguredKeyWhenListEmpty() {
        assertThat(withApiKeys().hasConfiguredKey()).isFalse();
    }

    @Test
    @DisplayName("환경변수 미설정 시의 [\"\"] 를 설정되지 않은 상태로 본다")
    void hasNoConfiguredKeyWhenOnlyBlankEntries() {
        // application.yml 의 "${MCP_API_KEY:}" 가 미치환되면 이 모양이 된다.
        // 이 상태를 KEY_MISMATCH 로 진단하면 사용자를 클라이언트 설정으로 보내게 된다.
        assertThat(withApiKeys("").hasConfiguredKey()).isFalse();
        assertThat(withApiKeys("", "   ").hasConfiguredKey()).isFalse();
    }

    @Test
    @DisplayName("null 항목이 섞여 있어도 터지지 않는다")
    void hasConfiguredKeyToleratesNullEntries() {
        assertThat(withApiKeys(null, "secret-key-1").hasConfiguredKey()).isTrue();
        assertThat(withApiKeys(null, "").hasConfiguredKey()).isFalse();
    }

    private SecurityProperties withApiKeys(String... keys) {
        SecurityProperties props = new SecurityProperties();
        List<String> apiKeys = keys.length == 0 ? List.of() : Arrays.asList(keys);
        props.setApiKeys(apiKeys);
        return props;
    }
}
