package com.campustaximate.server.service;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("demo")
class AuthConcurrencyTest extends AuthConcurrencyCase { }
