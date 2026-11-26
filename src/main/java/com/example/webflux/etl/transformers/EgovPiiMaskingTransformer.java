package com.example.webflux.etl.transformers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentTransformer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * 색인 저장 단계에서 민감정보(PII)를 선택적으로 마스킹하는 문서 변환기.
 *
 * egovframe-ai-rag PR #39(spring-ai-rag-redis-stack)의 EgovPiiMaskingTransformer 를 옮겨 온 것이다.
 * 규칙과 설정 키 이름은 같게 유지하고, 접두만 이 프로젝트의 app.document 로 바꿨다.
 *
 * 이 서버는 searchDocuments 결과를 VS Code·Claude Desktop 같은 범용 MCP 클라이언트에도 내보낸다.
 * 그 클라이언트의 모델은 외부 클라우드 LLM 일 수 있고, 서버 쪽에서는 그 경로를 통제할 수 없다.
 * 따라서 원문이 벡터 저장소에 들어가지 않게 하는 것이 서버가 할 수 있는 보호다.
 *
 * 설계 원칙(노이즈·검색 품질 보호):
 *   - 기본 비활성화: enabled 가 false 면 원본을 그대로 반환한다.
 *   - 선택적 적용: 유출 위험이 크고 검색 기여가 낮은 항목(주민등록번호·카드번호·인증키)만 대상으로 한다.
 *     전화번호·이메일·담당자명·주소처럼 업무 검색에 필요할 수 있는 정보는 마스킹하지 않는다.
 *   - 오탐 억제: 주민등록번호는 검증자리, 카드번호는 Luhn 검사를 통과한 경우에만 치환한다.
 *     계좌번호는 기관별 형식이 제각각이라 운영자가 정규식을 지정한 경우에만 동작한다(기본 미지정).
 *
 * 단위 테스트가 Spring 컨텍스트 없이 만들 수 있도록 설정을 생성자로 받는다.
 */
@Slf4j
@Component
public class EgovPiiMaskingTransformer implements DocumentTransformer {

    /** 마스킹이 일어난 문서에 붙이는 메타데이터 키. */
    public static final String METADATA_MASKING_APPLIED = "pii_masking_applied";

    /** 주민등록번호/외국인등록번호: 생년월일 6자리 + 성별 1자리(1~8) + 6자리. */
    private static final Pattern RRN_PATTERN =
            Pattern.compile("(?<![0-9])(\\d{6})[- ]?([1-8]\\d{6})(?![0-9])");

    /** 주민등록번호 검증자리(13번째) 가중치. 앞 12자리에 순서대로 곱한다. */
    private static final int[] RRN_WEIGHTS = {2, 3, 4, 5, 6, 7, 8, 9, 2, 3, 4, 5};

    /** 인증키 값 판별용 한글(음절·자모) 탐지 패턴. */
    private static final Pattern HANGUL_PATTERN = Pattern.compile("[가-힣ㄱ-ㅎㅏ-ㅣ]");

    /** 영문자 이외(숫자·기호) 문자 탐지. 자격증명은 숫자·기호를 포함하는 경향이 있다. */
    private static final Pattern NON_LETTER_PATTERN = Pattern.compile("[^A-Za-z]");

    /** 인증키로 인정할 값의 최소 길이. */
    private static final int MIN_SECRET_VALUE_LENGTH = 6;

    /** 카드번호 후보: 13~19자리 숫자(자리 사이에만 공백/하이픈 허용). Luhn 검사로 최종 확정. */
    private static final Pattern CARD_CANDIDATE_PATTERN =
            Pattern.compile("(?<![0-9])\\d(?:[ -]?\\d){12,18}(?![0-9])");

    /** 인증키/비밀키: key=value 또는 key: value 형태의 값만 치환. */
    private static final Pattern SECRET_PATTERN = Pattern.compile(
            "(?i)\\b(api[_-]?key|secret(?:[_-]?key)?|access[_-]?key|private[_-]?key|"
                    + "password|passwd|pwd|token|authorization)\\b\\s*[:=]\\s*[\"']?([^\\s\"',]+)");

    private final boolean enabled;
    private final boolean maskRrn;
    private final boolean maskCard;
    private final boolean maskSecret;
    private final String rrnToken;
    private final String cardToken;
    private final String secretToken;
    private final String accountToken;
    private final Pattern accountPattern;

    /**
     * @param enabled        마스킹 활성화 여부(false면 원본 그대로 반환)
     * @param maskRrn        주민등록번호 마스킹 여부
     * @param maskCard       카드번호(Luhn 통과) 마스킹 여부
     * @param maskSecret     인증키/비밀키 값 마스킹 여부
     * @param rrnToken       주민등록번호 치환 토큰
     * @param cardToken      카드번호 치환 토큰
     * @param secretToken    인증키 값 치환 토큰
     * @param accountToken   계좌번호 치환 토큰
     * @param accountRegex   계좌번호 정규식(비어 있으면 계좌 마스킹 비활성)
     */
    public EgovPiiMaskingTransformer(
            @Value("${app.document.pii-masking.enabled:false}") boolean enabled,
            @Value("${app.document.pii-masking.mask-rrn:true}") boolean maskRrn,
            @Value("${app.document.pii-masking.mask-card:true}") boolean maskCard,
            @Value("${app.document.pii-masking.mask-secret:true}") boolean maskSecret,
            @Value("${app.document.pii-masking.rrn-token:[RRN]}") String rrnToken,
            @Value("${app.document.pii-masking.card-token:[CARD]}") String cardToken,
            @Value("${app.document.pii-masking.secret-token:[SECRET]}") String secretToken,
            @Value("${app.document.pii-masking.account-token:[ACCOUNT]}") String accountToken,
            @Value("${app.document.pii-masking.account-regex:}") String accountRegex) {
        this.enabled = enabled;
        this.maskRrn = maskRrn;
        this.maskCard = maskCard;
        this.maskSecret = maskSecret;
        this.rrnToken = rrnToken;
        this.cardToken = cardToken;
        this.secretToken = secretToken;
        this.accountToken = accountToken;
        this.accountPattern = (accountRegex == null || accountRegex.isBlank())
                ? null : Pattern.compile(accountRegex);
        log.info("EgovPiiMaskingTransformer 생성 (enabled={})", enabled);
    }

    @Override
    public List<Document> apply(List<Document> documents) {
        if (!enabled) {
            // 기본 비활성이 정상 운영 경로이므로 매 적재마다 INFO 로그 노이즈를 피한다.
            log.debug("PII 마스킹이 비활성화되어 있습니다. 원본 문서를 그대로 반환합니다.");
            return documents;
        }

        log.info("PII 마스킹 시작: {}개 문서 (주민번호: {}, 카드: {}, 인증키: {}, 계좌: {})",
                documents.size(), maskRrn, maskCard, maskSecret, accountPattern != null);

        return documents.stream().map(this::maskDocument).toList();
    }

    /**
     * 원본 문서는 바꾸지 않고 새 문서를 만든다.
     *
     * MCP 적재 경로는 해시 저장·구버전 벡터 삭제에 원본 문서를 계속 쓰므로,
     * 메타데이터도 복사본에만 플래그를 단다.
     */
    private Document maskDocument(Document document) {
        String original = document.getText();
        String masked = mask(original);

        if (original == null || original.equals(masked)) {
            return document;
        }

        Map<String, Object> metadata = new HashMap<>(document.getMetadata());
        metadata.put(METADATA_MASKING_APPLIED, true);
        return new Document(document.getId(), masked, metadata);
    }

    /**
     * 문자열에 활성화된 PII 규칙을 순서대로 적용한다.
     * 패키지 가시성으로 노출하여 단위 테스트가 직접 호출한다.
     */
    String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        String result = text;
        if (maskRrn) {
            result = maskRrnNumbers(result);
        }
        if (maskCard) {
            result = maskCardNumbers(result);
        }
        if (accountPattern != null) {
            result = accountPattern.matcher(result).replaceAll(Matcher.quoteReplacement(accountToken));
        }
        if (maskSecret) {
            result = maskSecrets(result);
        }
        return result;
    }

    /** 주민등록번호 후보 중 검증자리(체크섬)까지 통과한 것만 토큰으로 치환한다. */
    private String maskRrnNumbers(String text) {
        Matcher matcher = RRN_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String digits = matcher.group(1) + matcher.group(2); // 13자리(구분자 제외)
            String replacement = isValidRrn(digits) ? rrnToken : matcher.group();
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * 카드번호 후보 중 Luhn 검사를 통과한 것만 토큰으로 치환한다.
     *
     * 주민등록번호 형태(6-7자리)인 13자리 숫자는 카드로 취급하지 않는다. 이 단계는 RRN 마스킹
     * 이후 실행되므로, 남아 있는 RRN 형태 숫자는 검증자리를 통과하지 못한 값(행정 문서의
     * 관리코드·기안번호 등)이다. 이를 카드로 재분류하면 다시 오탐되므로 제외한다.
     */
    private String maskCardNumbers(String text) {
        Matcher matcher = CARD_CANDIDATE_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String candidate = matcher.group();
            String replacement;
            if (RRN_PATTERN.matcher(candidate).matches()) {
                replacement = candidate; // 주민번호 형태 → 카드 오탐 제외
            } else {
                String digits = candidate.replaceAll("[ -]", "");
                replacement = isLuhnValid(digits) ? cardToken : candidate;
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * key=value / key: value 형태에서 값 부분만 토큰으로 치환(키 이름은 보존).
     *
     * 값이 실제 자격증명처럼 보일 때만 치환한다. "password: 8자 이상으로 설정하세요",
     * "token: production 환경"처럼 키워드 뒤에 안내문이 오는 경우를 오탐하지 않도록,
     * 값에 한글이 없고 최소 길이(6자) 이상이며 숫자나 기호를 하나 이상 포함할 때만 마스킹한다.
     */
    private String maskSecrets(String text) {
        Matcher matcher = SECRET_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String replacement;
            if (isSecretLike(matcher.group(2))) {
                String keyPart = text.substring(matcher.start(), matcher.start(2));
                replacement = keyPart + secretToken;
            } else {
                replacement = matcher.group(); // 자격증명으로 보이지 않으면 원문 유지
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /** 값이 자격증명처럼 보이는지 판정한다. 한글이 없고, 최소 길이 이상이며, 숫자·기호를 포함해야 한다. */
    private boolean isSecretLike(String value) {
        return value.length() >= MIN_SECRET_VALUE_LENGTH
                && !HANGUL_PATTERN.matcher(value).find()
                && NON_LETTER_PATTERN.matcher(value).find();
    }

    /**
     * 주민등록번호 검증자리(체크섬) 검사.
     *
     * 앞 12자리에 가중치를 곱해 합한 뒤 (11 - sum % 11) % 10 이 13번째 자리와 같은지 확인한다.
     * 2020년 10월 이후 발급된 무작위 외국인등록번호는 이 검증을 따르지 않으므로 마스킹되지 않을 수 있다.
     */
    private boolean isValidRrn(String digits) {
        if (digits.length() != 13) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < RRN_WEIGHTS.length; i++) {
            sum += (digits.charAt(i) - '0') * RRN_WEIGHTS[i];
        }
        int check = (11 - (sum % 11)) % 10;
        return check == (digits.charAt(12) - '0');
    }

    /** Luhn(모듈러스 10) 검사. 13~19자리 카드번호의 오탐을 줄인다. */
    private boolean isLuhnValid(String digits) {
        int len = digits.length();
        if (len < 13 || len > 19) {
            return false;
        }
        int sum = 0;
        boolean doubleDigit = false;
        for (int i = len - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (doubleDigit) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubleDigit = !doubleDigit;
        }
        return sum % 10 == 0;
    }
}
