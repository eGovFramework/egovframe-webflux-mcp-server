package com.example.webflux.util;

/**
 * 적재된 내용의 실제 바이트가 파일 확장자와 맞지 않을 때 사용하는 예외.
 *
 * 주로 범용 MCP 클라이언트에서 바이너리 파일을 적재하려다 발생한다.
 * LLM 이 파일을 읽어 content 파라미터를 채우는 경로에서는 원본 바이트가 바뀌기 쉽다.
 */
public class FileSignatureMismatchException extends RuntimeException {

    public FileSignatureMismatchException(String message) {
        super(message);
    }
}
