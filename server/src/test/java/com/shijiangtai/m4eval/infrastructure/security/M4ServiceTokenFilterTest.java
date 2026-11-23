package com.shijiangtai.m4eval.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Internal callback authentication is fail-closed (M4-T03 / M4-T09). */
class M4ServiceTokenFilterTest {

    private static MockHttpServletRequest request(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/evaluations");
        if (token != null) {
            request.addHeader(M4ServiceTokenFilter.HEADER, token);
        }
        return request;
    }

    @Test
    void validTokenContinuesTheChain() throws Exception {
        MockFilterChain chain = new MockFilterChain();

        new M4ServiceTokenFilter("service-token")
                .doFilter(request("service-token"), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void missingOrWrongTokenIsUnauthorizedAndStopsTheChain() throws Exception {
        for (String token : new String[] {null, "wrong"}) {
            MockFilterChain chain = new MockFilterChain();
            MockHttpServletResponse response = new MockHttpServletResponse();

            new M4ServiceTokenFilter("service-token")
                    .doFilter(request(token), response, chain);

            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(response.getContentAsString()).contains("UNAUTHORIZED");
            assertThat(chain.getRequest()).isNull();
        }
    }

    @Test
    void emptyConfiguredTokenRejectsEverything() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        new M4ServiceTokenFilter(" ")
                .doFilter(request("anything"), response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }
}
