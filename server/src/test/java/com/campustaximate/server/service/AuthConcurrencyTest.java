package com.campustaximate.server.service;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
@SpringBootTest(properties = "app.tmap.app-key=")
@ActiveProfiles("demo")
class AuthConcurrencyTest extends AuthConcurrencyCase { }
