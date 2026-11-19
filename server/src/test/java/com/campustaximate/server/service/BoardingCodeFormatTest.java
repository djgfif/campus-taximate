package com.campustaximate.server.service;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
class BoardingCodeFormatTest {
 @Test void fixedSixDigitsIncludingLeadingZero() {
  assertThat(BoardingCodeService.formatCode(0)).isEqualTo("000000");
  assertThat(BoardingCodeService.formatCode(42)).isEqualTo("000042");
  assertThat(BoardingCodeService.formatCode(999999)).isEqualTo("999999");
 }
}
