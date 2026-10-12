package com.morales.chemicallab.security.limits;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;

class TransportInputFilterTest {
    @Test void actualStreamIsBoundedEvenWithoutDeclaredLengthAndExactByteEdgeIsAccepted() throws Exception {
        var limits = new TransportLimits(); limits.setHttpBytes(32);
        var filter = new TransportInputFilter(limits, new JsonInputGuard(limits), new AccountTrafficLimits(limits));
        for (int size : new int[] {32, 33}) {
            var request = new MockHttpServletRequest() { @Override public long getContentLengthLong() { return -1; } };
            request.setServletPath("/api/whiteboards/teacher/1/state"); request.setMethod("PUT"); request.setContentType("application/json");
            request.setContent(("{}" + " ".repeat(size - 2)).getBytes(StandardCharsets.UTF_8));
            var response = new MockHttpServletResponse(); var chain = new MockFilterChain();
            filter.doFilter(request, response, chain);
            if (size == 32) { assertThat(response.getStatus()).isEqualTo(200); assertThat(chain.getRequest().getInputStream().readAllBytes()).hasSize(32); }
            else { assertThat(response.getStatus()).isEqualTo(413); assertThat(chain.getRequest()).isNull(); }
        }
    }
    @Test void multipartKeepsItsExistingFrameworkAndT06ValidatorPath() throws Exception {
        var limits = new TransportLimits(); limits.setHttpBytes(32);
        var filter = new TransportInputFilter(limits, new JsonInputGuard(limits), new AccountTrafficLimits(limits));
        var request = new MockHttpServletRequest(); request.setServletPath("/api/whiteboards/teacher/1/close"); request.setMethod("POST");
        request.setContentType("multipart/form-data; boundary=fictitious"); request.setContent(new byte[33]);
        var chain = new MockFilterChain(); filter.doFilter(request, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isSameAs(request);
    }
}
