package com.campustaximate.server.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.campustaximate.server.common.ApiException;
import com.campustaximate.server.service.DemoService;
import com.campustaximate.server.service.DemoScenarioService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

class DemoProfileIsolationTest {
    @Configuration @EnableWebMvc static class Mvc { }
    @Test void productionDoesNotRegisterAnyDemoEndpointEvenIfFlagIsTrue() throws Exception {
        try(var context=new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.getEnvironment().setActiveProfiles("prod");
            org.springframework.test.context.support.TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,"app.demo.enabled=true");
            context.register(Mvc.class,DemoController.class,DemoScenarioService.class);
            context.refresh();
            assertThat(context.getBeansOfType(DemoController.class)).isEmpty();
            assertThat(context.getBeansOfType(DemoScenarioService.class)).isEmpty();
            var mvc=MockMvcBuilders.webAppContextSetup(context).build();
            for(String endpoint:new String[]{"login","match","next","scenario","run","advance","samples","act"})
                mvc.perform(post("/api/demo/"+endpoint).contentType(MediaType.APPLICATION_JSON).content("{}"))
                        .andExpect(status().isNotFound());
            mvc.perform(get("/api/demo/status")).andExpect(status().isNotFound());
        }
    }
    @Test void serviceAlsoRejectsOutsideDemoProfile() {
        var service=mock(DemoService.class,CALLS_REAL_METHODS);
        var environment=new MockEnvironment();environment.setActiveProfiles("prod");
        ReflectionTestUtils.setField(service,"environment",environment);
        ReflectionTestUtils.setField(service,"enabled",true);
        assertThat(service.enabled()).isFalse();
        assertThatThrownBy(service::requireEnabled).isInstanceOf(ApiException.class);
    }
    @Test void demoProfileWithDisabledFlagAlsoRejectsService() {
        var service=mock(DemoService.class,CALLS_REAL_METHODS);
        var environment=new MockEnvironment();environment.setActiveProfiles("demo");
        ReflectionTestUtils.setField(service,"environment",environment);
        ReflectionTestUtils.setField(service,"enabled",false);
        assertThatThrownBy(service::requireEnabled).isInstanceOf(ApiException.class);
    }
}
