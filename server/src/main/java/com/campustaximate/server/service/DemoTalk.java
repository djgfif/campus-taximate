package com.campustaximate.server.service;

import java.util.List;

/** 시연 샘플 계정이 채팅에 남기는 말. 같은 문장이 반복되지 않게 사람마다 조금씩 다르게 말한다. */
final class DemoTalk {
    private static final List<String> GREET = List.of("안녕하세요! 이따 봬요", "안녕하세요~ 저 조금 일찍 가 있을게요", "넵 반가워요 🙂");
    private static final List<String> PAID = List.of("결제 완료했어요!", "저도 결제했습니다", "결제했어요 👍");
    private static final List<String> ARRIVED = List.of("도착했어요! %s", "저 도착했습니다. %s", "먼저 와 있어요~ %s");

    private DemoTalk() {
    }

    static String greet(long seed) {
        return pick(GREET, seed);
    }

    static String paid(long seed) {
        return pick(PAID, seed);
    }

    static String arrived(long seed, String memo) {
        String line = pick(ARRIVED, seed);
        return memo == null || memo.isBlank() ? line.replace(" %s", "").replace("~ %s", "~") : line.formatted(memo);
    }

    private static String pick(List<String> lines, long seed) {
        return lines.get((int) Math.floorMod(seed, (long) lines.size()));
    }
}
