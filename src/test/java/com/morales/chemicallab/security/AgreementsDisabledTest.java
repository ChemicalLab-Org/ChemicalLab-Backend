package com.morales.chemicallab.security;

import com.morales.chemicallab.agreement.AgreementReviews;
import com.morales.chemicallab.agreement.AgreementService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class AgreementsDisabledTest {
    @Test
    void noAgreementSchemaOrRuntimePrerequisiteIsReadWhenDisabled() {
        var db = mock(JdbcClient.class);
        var service = new AgreementService(db, false);
        var reviews = new AgreementReviews(db, service);

        assertTrue(service.mayActivateInstitution(2));
        assertFalse(service.requiredOutstanding(1, 2L));
        assertFalse(service.academicHold(1, 2L));
        assertFalse(service.overview(1).enabled());
        assertTrue(service.publicDocuments().isEmpty());
        assertTrue(service.history(1, Long.MAX_VALUE).decisions().isEmpty());
        assertTrue(reviews.familyStatus(1).isEmpty());
        assertFalse(reviews.permissions(1).platform());
        assertThrows(ResponseStatusException.class, () -> service.accountText(1, 3));
        assertThrows(ResponseStatusException.class, () -> reviews.family(1, 2, 3, null));
        verifyNoInteractions(db);
    }
}
