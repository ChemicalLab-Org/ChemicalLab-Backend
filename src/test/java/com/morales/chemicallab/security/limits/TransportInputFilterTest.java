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
    @Test void institutionalBodiesRespectLowerHttpBudgetEvenChunkedOrMultipart() throws Exception {
        var limits=new TransportLimits();limits.setHttpBytes(32);
        var filter=new TransportInputFilter(limits,new JsonInputGuard(limits),new AccountTrafficLimits(limits));
        for(String route:new String[]{"evaluation-writes/LEGACY","evaluation-execution/NEW/v1"})
        for(String contentType:new String[]{"application/json","text/plain","multipart/form-data; boundary=fictitious"}) {
            var request=new MockHttpServletRequest(){@Override public long getContentLengthLong(){return -1;}};
            request.setServletPath("/api/institutions/1/classes/2/"+route+"/evaluations/3/deliveries/4/revisions/5/own-attempts");
            request.setMethod("POST");request.setContentType(contentType);request.setContent(new byte[33]);
            var response=new MockHttpServletResponse();var chain=new MockFilterChain();filter.doFilter(request,response,chain);
            assertThat(response.getStatus()).isEqualTo(413);assertThat(chain.getRequest()).isNull();
        }
    }
    @Test void newExecutionRejectsCoercibleIdsAndTextsBeforeMvc() throws Exception {
        var limits=new TransportLimits();var filter=new TransportInputFilter(limits,new JsonInputGuard(limits),new AccountTrafficLimits(limits));
        for(String body:new String[]{"{\"questionId\":17.5}","{\"questionId\":\"17\"}","{\"answerText\":42}","{\"score\":1.5}","{\"amount\":\"1.25\"}","{\"amount\":1e-100000000}","{\"amount\":1e999999999}","{\"questionId\":17,\"answerText\":\"Text\"}"}) {
            var request=new MockHttpServletRequest();request.setServletPath("/api/institutions/1/classes/2/evaluation-execution/NEW/v1/evaluations/3/deliveries/4/revisions/5/own-attempts/6/answers");request.setMethod("POST");request.setContentType("application/json");request.setContent(body.getBytes(StandardCharsets.UTF_8));
            var response=new MockHttpServletResponse();var chain=new MockFilterChain();filter.doFilter(request,response,chain);
            if(body.contains("\"Text\"")){assertThat(response.getStatus()).isEqualTo(200);assertThat(chain.getRequest()).isNotNull();}
            else{assertThat(response.getStatus()).isEqualTo(400);assertThat(chain.getRequest()).isNull();}
        }
    }
}
