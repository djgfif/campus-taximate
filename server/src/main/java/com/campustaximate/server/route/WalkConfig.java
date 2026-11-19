package com.campustaximate.server.route;
import com.campustaximate.server.common.AppClock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
@Configuration
public class WalkConfig {
    @Bean WalkEstimator walkEstimator(RestClient.Builder builder,AppClock clock,
            @Value("${app.tmap.app-key:}")String key,@Value("${app.tmap.base-url:https://apis.openapi.sk.com}")String base,
            @Value("${app.tmap.timeout-ms:3000}")int timeout,@Value("${app.tmap.cache-minutes:30}")int keep,
            @Value("${app.tmap.daily-limit:1000}")int limit){
        if(key.isBlank())return new ConservativeWalkEstimator();
        var factory=new SimpleClientHttpRequestFactory();factory.setConnectTimeout(timeout);factory.setReadTimeout(timeout);
        return new TmapWalkClient(builder.clone().requestFactory(factory).baseUrl(base).defaultHeader("appKey",key).build(),clock,Duration.ofMinutes(keep),limit);
    }
}
