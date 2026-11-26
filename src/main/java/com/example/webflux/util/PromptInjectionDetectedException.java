package com.example.webflux.util;

/**
 * 적재 문서에서 자연어 프롬프트 인젝션 의심 패턴이 발견되어
 * 적재를 거부할 때 사용하는 예외.
 */
public class PromptInjectionDetectedException extends RuntimeException {

    public PromptInjectionDetectedException(String message) {
        super(message);
    }
}
