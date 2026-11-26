package com.morales.chemicallab.subscription;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Arrays;

@Component
@ConfigurationProperties("app.subscriptions")
public class SubscriptionSettings {
    public enum Mode { DISABLED, LOCAL_TEST, MERCADO_PAGO_TEST }

    private Mode mode = Mode.DISABLED;
    private URI localApi;
    private URI returnUrl;
    private String token = "";
    private String webhookSecret = "";
    private long sellerId;
    private long applicationId;
    private long buyerId;
    private String buyerEmail = "";

    public void validate(Environment environment) {
        if (!enabled()) return;
        if (Arrays.asList(environment.getActiveProfiles()).contains("prod")) {
            throw new IllegalStateException("Subscription testing is unavailable with the prod profile");
        }
        if (sellerId <= 0 || applicationId <= 0 || buyerId <= 0 || buyerId == sellerId
                || !buyerEmail.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")
                || webhookSecret.length() < 16 || !validReturnUrl()) {
            throw new IllegalStateException("Incomplete subscription testing configuration");
        }
        if (mode == Mode.LOCAL_TEST && !loopback(localApi) || mode == Mode.MERCADO_PAGO_TEST && token.isBlank()) {
            throw new IllegalStateException("Invalid subscription provider configuration");
        }
    }

    private boolean validReturnUrl() {
        return returnUrl != null && returnUrl.getHost() != null && returnUrl.getUserInfo() == null
                && returnUrl.getFragment() == null && ("https".equals(returnUrl.getScheme()) || loopback(returnUrl));
    }

    private static boolean loopback(URI url) {
        return url != null && "http".equals(url.getScheme()) && "127.0.0.1".equals(url.getHost())
                && url.getUserInfo() == null && url.getPort() > 0;
    }

    public boolean checkoutAllowed(URI url) {
        if (url.getUserInfo() != null || url.getFragment() != null) return false;
        if (mode == Mode.LOCAL_TEST) return loopback(url) && url.getPort() == localApi.getPort();
        return "https".equals(url.getScheme()) && "www.mercadopago.com.pe".equals(url.getHost())
                && url.getPort() == -1 && "/subscriptions/checkout".equals(url.getPath());
    }

    public boolean enabled() { return mode != Mode.DISABLED; }
    Mode mode() { return mode; }
    URI api() { return mode == Mode.LOCAL_TEST ? localApi : URI.create("https://api.mercadopago.com"); }
    URI returnUrl() { return returnUrl; }
    String token() { return token; }
    String webhookSecret() { return webhookSecret; }
    long sellerId() { return sellerId; }
    long applicationId() { return applicationId; }
    long buyerId() { return buyerId; }
    String buyerEmail() { return buyerEmail; }
    public void setMode(Mode mode) { this.mode = mode; }
    public void setLocalApi(URI localApi) { this.localApi = localApi; }
    public void setReturnUrl(URI returnUrl) { this.returnUrl = returnUrl; }
    public void setToken(String token) { this.token = token; }
    public void setWebhookSecret(String webhookSecret) { this.webhookSecret = webhookSecret; }
    public void setSellerId(long sellerId) { this.sellerId = sellerId; }
    public void setApplicationId(long applicationId) { this.applicationId = applicationId; }
    public void setBuyerId(long buyerId) { this.buyerId = buyerId; }
    public void setBuyerEmail(String buyerEmail) { this.buyerEmail = buyerEmail; }
}
