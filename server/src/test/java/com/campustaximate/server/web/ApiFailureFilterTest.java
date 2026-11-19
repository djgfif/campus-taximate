package com.campustaximate.server.web;
import static org.assertj.core.api.Assertions.*;
import com.campustaximate.server.common.ApiException;import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.extension.ExtendWith;import org.springframework.boot.test.system.*;import org.springframework.mock.web.*;
@ExtendWith(OutputCaptureExtension.class)
class ApiFailureFilterTest {
    @Test void filterAuthenticationRejectionKeeps401JsonWithoutErrorStack(CapturedOutput output)throws Exception{
        var filter=new ApiFailureFilter(new ObjectMapper());var r=new MockHttpServletRequest("GET","/api/state");var response=new MockHttpServletResponse();
        filter.doFilter(r,response,(a,b)->{throw ApiException.unauthorized();});
        assertThat(response.getStatus()).isEqualTo(401);assertThat(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).contains("UNAUTHORIZED","로그인이 필요합니다.");
        assertThat(output.getAll()).doesNotContain("ERROR","ApiException:");
    }
    @Test void wrappedAuthorizationRejectionKeeps403JsonWithoutErrorStack(CapturedOutput output)throws Exception{
        var filter=new ApiFailureFilter(new ObjectMapper());var response=new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET","/api/admin/no-shows"),response,(a,b)->{throw new jakarta.servlet.ServletException(ApiException.forbidden("관리자만 이용할 수 있어요."));});
        assertThat(response.getStatus()).isEqualTo(403);assertThat(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).contains("FORBIDDEN","관리자만 이용할 수 있어요.");assertThat(output.getAll()).doesNotContain("ERROR","ApiException:");
    }
}
