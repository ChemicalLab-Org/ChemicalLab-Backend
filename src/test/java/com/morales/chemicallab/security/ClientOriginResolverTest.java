package com.morales.chemicallab.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.assertj.core.api.Assertions.*;

class ClientOriginResolverTest {
    @Test void untrustedPeerCannotSpoofEitherHeader() {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.10");
        request.addHeader("X-Forwarded-For", "198.51.100.8");
        request.addHeader("Forwarded", "for=198.51.100.9");
        assertThat(new ClientOriginResolver("").resolve(request)).isEqualTo("192.0.2.10");
        assertThat(new ClientOriginResolver("10.0.0.0/24").resolve(request)).isEqualTo("192.0.2.10");
    }
    @Test void walksTrustedHopsFromRightAndIgnoresClientPrefix() {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.2");
        request.addHeader("X-Forwarded-For", "198.51.100.99, 192.0.2.8, 10.0.0.1");
        assertThat(new ClientOriginResolver("10.0.0.0/24").resolve(request)).isEqualTo("192.0.2.8");
        request.removeHeader("X-Forwarded-For");
        request.addHeader("X-Forwarded-For", "not-an-ip");
        assertThat(new ClientOriginResolver("10.0.0.0/24").resolve(request)).isEqualTo("10.0.0.2");
        request.removeHeader("X-Forwarded-For");
        request.addHeader("Forwarded", "for=192.0.2.7");
        assertThat(new ClientOriginResolver("10.0.0.0/24").resolve(request)).isEqualTo("10.0.0.2");
    }
    @Test void ipv6AndInvalidConfiguration() {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("2001:db8::1");
        request.addHeader("X-Forwarded-For", "2001:db9::5");
        assertThat(new ClientOriginResolver("2001:db8::/32").resolve(request)).isEqualTo("2001:db9:0:0:0:0:0:5");
        assertThatThrownBy(() -> new ClientOriginResolver("proxy.example.test")).isInstanceOf(IllegalStateException.class);
    }
}
