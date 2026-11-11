package com.campustaximate.server.common;

import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 테스트는 회차를 직접 실행하고 운영은 기존 스케줄을 유지한다. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name="app.scheduling.enabled", havingValue="true", matchIfMissing=true)
public class SchedulingConfiguration { }
