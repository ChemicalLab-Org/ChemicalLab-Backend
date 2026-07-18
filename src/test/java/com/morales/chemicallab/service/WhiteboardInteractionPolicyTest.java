package com.morales.chemicallab.service;

import com.morales.chemicallab.entity.WhiteboardInteractionOverride;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pruebas de la regla de permiso efectivo de interacción, combinando el permiso global de la
 * sesión con el permiso individual del participante.
 */
class WhiteboardInteractionPolicyTest {

    @Test
    void globalFalseConSeguirGlobalNoPuede() {
        assertThat(WhiteboardInteractionPolicy.effective(false, WhiteboardInteractionOverride.FOLLOW_GLOBAL))
                .isFalse();
    }

    @Test
    void globalTrueConSeguirGlobalPuede() {
        assertThat(WhiteboardInteractionPolicy.effective(true, WhiteboardInteractionOverride.FOLLOW_GLOBAL))
                .isTrue();
    }

    @Test
    void permitidoIndividualPuedeAunqueGlobalEsteDesactivado() {
        assertThat(WhiteboardInteractionPolicy.effective(false, WhiteboardInteractionOverride.ALLOWED))
                .isTrue();
    }

    @Test
    void bloqueadoIndividualNoPuedeAunqueGlobalEsteActivado() {
        assertThat(WhiteboardInteractionPolicy.effective(true, WhiteboardInteractionOverride.BLOCKED))
                .isFalse();
    }

    @Test
    void overrideNuloSigueElGlobal() {
        assertThat(WhiteboardInteractionPolicy.effective(true, null)).isTrue();
        assertThat(WhiteboardInteractionPolicy.effective(false, null)).isFalse();
    }
}
