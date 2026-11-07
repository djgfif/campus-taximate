package com.campustaximate.server.service;

import com.campustaximate.server.repository.SettlementRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 예상 요금이 실제 결제와 얼마나 맞는지 정산이 쌓일 때마다 잰다. 개인 정보 없이 건수와 비율만 낸다. */
@Service
@RequiredArgsConstructor
public class FareAccuracyService {

    static final double TOLERANCE = 0.15;

    private final SettlementRepository settlements;

    public record Accuracy(int settled, int within, double rate) {
    }

    @Transactional(readOnly = true)
    public Accuracy measure() {
        return of(settlements.findPaidAndExpected());
    }

    static Accuracy of(List<Object[]> pairs) {
        int within = 0;
        for (Object[] pair : pairs) {
            long paid = ((Number) pair[0]).longValue();
            long expected = ((Number) pair[1]).longValue();
            if (expected > 0 && Math.abs(paid - expected) <= expected * TOLERANCE) {
                within++;
            }
        }
        return new Accuracy(pairs.size(), within, pairs.isEmpty() ? 0 : (double) within / pairs.size());
    }
}
