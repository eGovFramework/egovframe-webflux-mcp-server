package com.example.webflux.service;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.webflux.service.impl.EgovDocumentManagementServiceImpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 재인덱싱이 이미 돌고 있을 때 두 번째 요청을 거부하는지 확인하는 테스트.
 *
 * 전에는 reindexDocuments 가 isProcessing.get() 으로 확인만 하고 실제 선점은
 * loadDocumentsAsync 의 compareAndSet 이 했다. 그 확인과 선점 사이에 틈이 있어서, 거의 동시에 들어온
 * 두 요청이 모두 그 확인을 통과하면 뒤쪽은 탈락했는데도 "처리되었습니다"(202)를 받았다.
 * 호출자는 시작된 줄 알고 기다린다.
 *
 * 넘겨받은 작업을 실행하지 않는 executor 를 주입해 플래그가 잡힌 상태를 고정한다. 그래야
 * 타이밍에 기대지 않고 선점 검사만 떼어내 확인할 수 있다.
 */
class EgovDocumentManagementReindexGateTest {

    @Test
    @DisplayName("재인덱싱이 진행 중이면 두 번째 요청은 거절된다")
    void secondRequestIsRejectedWhileRunning() {
        AtomicInteger submitted = new AtomicInteger();
        var service = serviceWith(task -> submitted.incrementAndGet());   // 제출만 세고 실행하지 않는다

        assertThat(service.reindexDocuments()).contains("처리되었습니다");
        assertThat(service.isProcessing()).isTrue();

        assertThatThrownBy(service::reindexDocuments)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("이미 문서 인덱싱이 진행 중입니다")
                .hasMessageContaining("/api/documents/status");

        // 두 번째 요청은 파이프라인을 제출조차 하지 않아야 한다
        assertThat(submitted).hasValue(1);
    }

    @Test
    @DisplayName("기동 경로도 같은 선점 검사를 쓴다 - 진행 중이면 예외 없이 건너뛴다")
    void startupPathSkipsWithoutThrowing() throws Exception {
        AtomicInteger submitted = new AtomicInteger();
        var service = serviceWith(task -> submitted.incrementAndGet());

        service.reindexDocuments();

        assertThat(service.loadDocumentsAsync().get(1, TimeUnit.SECONDS)).isZero();
        assertThat(submitted).hasValue(1);
    }

    @Test
    @DisplayName("동시 요청에서 통과 수와 파이프라인 제출 수가 어긋나지 않는다")
    void acceptedCountNeverExceedsSubmittedCount() throws Exception {
        // 경합이 생기는 틈은 두 연산 사이라 한 번의 시도로는 거의 걸리지 않는다. 서비스 인스턴스를
        // 새로 만들어 '첫 전이' 를 반복해서 겨룬다 - 플래그가 한 번 올라가면 그 인스턴스에서는
        // 더 겨룰 일이 없기 때문이다.
        final int rounds = 300;
        final int racers = 8;

        ExecutorService pool = Executors.newFixedThreadPool(racers);
        try {
            for (int round = 0; round < rounds; round++) {
                AtomicInteger submitted = new AtomicInteger();
                var service = serviceWith(task -> submitted.incrementAndGet());

                CountDownLatch start = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(racers);
                AtomicInteger accepted = new AtomicInteger();

                for (int i = 0; i < racers; i++) {
                    pool.execute(() -> {
                        try {
                            start.await();
                            service.reindexDocuments();
                            accepted.incrementAndGet();
                        } catch (IllegalStateException ignored) {
                            // 거절은 정상이다
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    });
                }
                start.countDown();
                assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();

                // 핵심 불변식: "시작했다" 고 답한 횟수와 실제로 파이프라인을 제출한 횟수가 같아야 한다.
                // 확인만 하던 방식에서는 경합에 진 요청도 성공 문자열(→ 202)을 받아 이 값이 어긋났다.
                assertThat(accepted.get())
                        .as("round %d - 통과 %d건인데 제출은 %d건", round, accepted.get(), submitted.get())
                        .isEqualTo(submitted.get());
                assertThat(accepted).hasValue(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** 파이프라인 본문은 실행되지 않으므로 나머지 협력자는 쓰이지 않는다. */
    private EgovDocumentManagementServiceImpl serviceWith(java.util.concurrent.Executor executor) {
        return new EgovDocumentManagementServiceImpl(
                null, null, null, null, null, null,
                null, null, null, null, null, null, executor);
    }
}
