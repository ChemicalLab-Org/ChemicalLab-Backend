package com.morales.chemicallab.subscription;

import com.fasterxml.jackson.databind.JsonNode;
import com.morales.chemicallab.subscription.SubscriptionLedger.Contract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.morales.chemicallab.subscription.MercadoPagoSubscriptions.*;

@Service
class SubscriptionWorkflow {
    static final Set<String> TOPICS = Set.of("subscription_preapproval", "subscription_authorized_payment", "payment");
    private static final Set<String> RECURRENCE = Set.of("pending", "authorized", "paused", "cancelled");
    private static final ZoneId LIMA = ZoneId.of("America/Lima");
    private static final Logger LOG = LoggerFactory.getLogger(SubscriptionWorkflow.class);

    private final SubscriptionSettings settings;
    private final SubscriptionLedger ledger;
    private final MercadoPagoSubscriptions provider;

    SubscriptionWorkflow(SubscriptionSettings settings, SubscriptionLedger ledger, MercadoPagoSubscriptions provider) {
        this.settings = settings;
        this.ledger = ledger;
        this.provider = provider;
    }

    void checkout(long school, long actor) {
        var contract = ledger.reserve(school, actor);
        if (contract.sentAt() != null || contract.cancelAt() != null) return;
        try {
            provider.verifyTestSeller();
        } catch (Unverified ex) {
            ledger.audit(school, actor, contract.ref(), "TEST_SELLER_UNVERIFIED", null);
            return;
        }
        if (!ledger.claimCreation(contract)) return;
        try {
            var created = provider.create(contract.ref(), contract.plan().name(), contract.plan().monthlyAmount(),
                    contract.plan().currency());
            String id = checkedId(created.path("id").asText());
            observeContract(contract, provider.subscription(id), id);
        } catch (Unverified ex) {
            // The POST may have committed. Retain the reservation and recover using the reference.
            ledger.audit(school, actor, contract.ref(), "CHECKOUT_UNCERTAIN", null);
        }
    }

    @Scheduled(fixedDelayString = "${app.subscriptions.work-delay-ms:60000}")
    void reconcile() {
        if (!settings.enabled()) return;
        for (int i = 0; i < 12; i++) {
            var batch = ledger.dueNotices();
            if (batch.isEmpty()) break;
            var notice = batch.get(0);
            boolean matched = false;
            try {
                matched = process(notice.kind(), notice.resource());
            } catch (RuntimeException ex) {
                LOG.warn("Subscription notification verification deferred");
            }
            ledger.noticeResult(notice, matched || notice.attempts() >= 10);
        }
        for (int i = 0; i < 6; i++) {
            var batch = ledger.dueContracts();
            if (batch.isEmpty()) break;
            var contract = batch.get(0);
            boolean resolved = false;
            try {
                resolved = process(contract);
            } catch (RuntimeException ex) {
                LOG.warn("Subscription reconciliation deferred");
            }
            ledger.reconciliationResult(contract, resolved);
        }
    }

    private boolean process(Contract contract) {
        if (contract.sentAt() == null) {
            ledger.closeUnsent(contract);
            return true;
        }
        String id = contract.providerId();
        if (id == null) {
            List<String> ids = provider.find(contract.ref(), contract.plan().name());
            if (ids.size() != 1) return false;
            id = ids.get(0);
        }
        var remote = provider.subscription(id);
        String status = observeContract(contract, remote, id);
        boolean abandoned = "pending".equals(status) && ledger.authority(contract.institutionId(), contract.actorId()) == null;
        if (!"cancelled".equals(status) && (contract.cancelAt() != null || abandoned)) {
            // The PUT sets a final state. Re-read before issuing it and verify the resulting state.
            provider.cancel(id);
            status = observeContract(contract, provider.subscription(id), id);
            return "cancelled".equals(status);
        }
        if (!"pending".equals(status)) {
            for (String invoice : provider.invoices("preapproval_id", id, 0)) {
                observeInvoice(provider.invoice(invoice), invoice, null, contract, id);
            }
        }
        return true;
    }

    private boolean process(String topic, String id) {
        return switch (topic) {
            case "subscription_preapproval" -> {
                var subscription = provider.subscription(id);
                var contract = byReference(subscription);
                if (contract == null) yield false;
                observeContract(contract, subscription, id);
                yield true;
            }
            case "subscription_authorized_payment" -> observeInvoice(provider.invoice(id), id, null, null, null);
            case "payment" -> {
                var related = provider.invoices("payment_id", id, 0);
                if (related.isEmpty()) yield false;
                require(related.size() == 1);
                String invoice = related.get(0);
                yield observeInvoice(provider.invoice(invoice), invoice, id, null, null);
            }
            default -> true;
        };
    }

    private Contract byReference(JsonNode node) {
        String reference = node.path("external_reference").asText();
        try {
            UUID ref = UUID.fromString(reference);
            return ref.toString().equals(reference) ? ledger.contract(ref) : null;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private String observeContract(Contract contract, JsonNode node, String id) {
        require(id.equals(node.path("id").asText()) && (contract.providerId() == null || id.equals(contract.providerId())));
        var recurring = node.path("auto_recurring");
        String rawStatus = node.path("status").asText();
        String status = "canceled".equals(rawStatus) ? "cancelled" : rawStatus;
        require(contract.ref().toString().equals(node.path("external_reference").asText())
                && node.path("collector_id").asLong() == settings.sellerId()
                && node.path("application_id").asLong() == settings.applicationId()
                && !node.path("live_mode").asBoolean(false)
                && RECURRENCE.contains(status)
                && (node.path("payer_id").asLong() == settings.buyerId()
                    || node.path("payer_id").asLong() == 0 && ("pending".equals(status) || "cancelled".equals(status)))
                && recurring.path("frequency").asInt() == 1 && "months".equals(recurring.path("frequency_type").asText())
                && contract.plan().currency().equals(recurring.path("currency_id").asText())
                && amount(recurring, "transaction_amount", contract.plan().monthlyAmount())
                && !recurring.hasNonNull("free_trial") && !node.hasNonNull("preapproval_plan_id"));
        OffsetDateTime version = time(node, "last_modified");
        require(version != null);
        String checkout = null;
        if ("pending".equals(status) && node.hasNonNull("init_point")) {
            URI url;
            try { url = URI.create(node.path("init_point").asText()); }
            catch (IllegalArgumentException ex) { throw new Unverified(); }
            require(settings.checkoutAllowed(url));
            checkout = url.toString();
        }
        ledger.observeContract(contract, id, status, version, checkout, time(node, "next_payment_date"));
        return status;
    }

    private boolean observeInvoice(JsonNode invoice, String invoiceId, String notifiedPayment, Contract known, String knownId) {
        require(invoiceId.equals(invoice.path("id").asText()));
        String subscriptionId = checkedId(invoice.path("preapproval_id").asText());
        if (knownId != null) require(knownId.equals(subscriptionId));
        Contract contract = known;
        if (contract == null) {
            var subscription = provider.subscription(subscriptionId);
            contract = byReference(subscription);
            if (contract == null) return false;
            observeContract(contract, subscription, subscriptionId);
        }
        require(contract.ref().toString().equals(invoice.path("external_reference").asText())
                && contract.plan().currency().equals(invoice.path("currency_id").asText())
                && amount(invoice, "transaction_amount", contract.plan().monthlyAmount()));
        var due = time(invoice, "debit_date");
        var version = time(invoice, "last_modified");
        require(due != null && version != null);
        String paymentId = invoice.path("payment").path("id").asText("");
        String status = "PENDING";
        OffsetDateTime approved = null;
        if (notifiedPayment != null) require(notifiedPayment.equals(paymentId));
        if (!paymentId.isBlank()) {
            var payment = provider.payment(paymentId);
            require(paymentId.equals(payment.path("id").asText())
                    && payment.path("collector_id").asLong() == settings.sellerId()
                    && payment.path("payer").path("id").asLong() == settings.buyerId()
                    && payment.path("live_mode").isBoolean() && !payment.path("live_mode").asBoolean()
                    && contract.plan().currency().equals(payment.path("currency_id").asText())
                    && amount(payment, "transaction_amount", contract.plan().monthlyAmount())
                    && (!payment.hasNonNull("external_reference")
                        || contract.ref().toString().equals(payment.path("external_reference").asText())));
            var paymentVersion = time(payment, "date_last_updated");
            require(paymentVersion != null);
            if (paymentVersion.isAfter(version)) version = paymentVersion;
            status = paymentStatus(payment);
            if ("APPROVED".equals(status)) {
                approved = time(payment, "date_approved");
                require(approved != null);
            }
        }
        // Debit date identifies the billing cycle. Late approval cannot invent an extra cycle.
        var end = due.atZoneSameInstant(LIMA).plusMonths(1).toOffsetDateTime();
        ledger.observeInvoice(contract, invoiceId, paymentId.isBlank() ? null : paymentId, status, version, due, end, approved);
        return true;
    }

    private static String paymentStatus(JsonNode payment) {
        String status = payment.path("status").asText();
        var refunded = payment.path("transaction_amount_refunded");
        if (Set.of("refunded", "charged_back").contains(status) || refunded.decimalValue().signum() > 0
                || refunded.isTextual() && !amount(payment, "transaction_amount_refunded", java.math.BigDecimal.ZERO)) {
            return "REVERSED";
        }
        return switch (status) {
            case "approved" -> "APPROVED";
            case "rejected", "cancelled" -> "FAILED";
            default -> "PENDING";
        };
    }
}
