package com.example.webflux.util;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RRF(Reciprocal Rank Fusion) 융합 유틸리티.
 *
 * dense 벡터 검색과 lexical 키워드 검색이 각각 반환한 순위 리스트를 하나로 융합한다.
 * 각 키의 융합 점수는 채널별 순위 역수의 가중 합으로 계산한다.
 *
 *   score(d) = Σ_channel  weight / (k + rank)
 *
 * 외부 의존성이 없는 순수 함수로, 동일 입력에 대해 항상 동일한 순서를 반환한다.
 */
public final class EgovRrfFusion {

    /** RRF 표준 상수. 분모에 더해져 1위 항목의 점수 우위를 줄인다. */
    public static final int DEFAULT_K = 60;

    private EgovRrfFusion() {
    }

    /**
     * 두 채널(dense, lexical)의 순위 리스트를 RRF로 융합한다.
     *
     * @param denseRanking   dense 채널 키 순서(상위가 앞)
     * @param lexicalRanking lexical 채널 키 순서(상위가 앞)
     * @param denseWeight    dense 채널 가중치
     * @param lexicalWeight  lexical 채널 가중치
     * @param k              RRF 상수
     * @param topK           반환할 상위 키 개수
     * @param <T>            융합 키 타입
     * @return 융합 점수 내림차순으로 정렬된 상위 topK 키
     */
    public static <T> List<T> fuse(List<T> denseRanking,
                                   List<T> lexicalRanking,
                                   double denseWeight,
                                   double lexicalWeight,
                                   int k,
                                   int topK) {
        Map<T, Double> scores = new LinkedHashMap<>();
        accumulate(scores, denseRanking, denseWeight, k);
        accumulate(scores, lexicalRanking, lexicalWeight, k);

        return scores.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(Math.max(topK, 0))
                .map(Map.Entry::getKey)
                .toList();
    }

    private static <T> void accumulate(Map<T, Double> scores, List<T> ranking, double weight, int k) {
        if (ranking == null) {
            return;
        }
        for (int rank = 0; rank < ranking.size(); rank++) {
            T key = ranking.get(rank);
            if (key == null) {
                continue;
            }
            scores.merge(key, weight / (k + rank), Double::sum);
        }
    }
}
