package com.morales.chemicallab.service;
import com.morales.chemicallab.repository.StudentUsageQueryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.assertj.core.api.Assertions.*;
@ExtendWith(MockitoExtension.class)
class StudentUsageRecordServiceTest {
 @Mock StudentUsageQueryRepository queries;
 @InjectMocks StudentUsageRecordService service;
    @Test
    void getRecords_filtrosInvalidos_lanzanIllegalArgument() {
        assertThatThrownBy(() -> service.getRecords(null, null, "7", null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("grado");

        assertThatThrownBy(() -> service.getRecords("SUPERUSUARIO", null, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rol");

        assertThatThrownBy(() -> service.getRecords(null, null, null, "AB", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sección");

        assertThatThrownBy(() -> service.getRecords(null, null, null, null, "2026-13-40", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("desde");

        assertThatThrownBy(() -> service.getRecords(null, null, null, null, "2026-07-05", "2026-07-01", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("posterior");

        assertThatThrownBy(() -> service.getRecords(null, null, null, null, null, null, "MODULO_FALSO", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("módulo");
    }

    // =========================================================================
    // DETALLE
    // =========================================================================

}
