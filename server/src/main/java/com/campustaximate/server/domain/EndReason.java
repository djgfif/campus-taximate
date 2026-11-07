package com.campustaximate.server.domain;

/** 일행이 있었던 요청이 어떻게 끝났는지. 일행 정보를 지운 뒤에도 화면과 이용 내역에 남긴다. */
public enum EndReason {
    NO_SHOW, PARTY_CANCELED, LEFT_ALONE
}
