package com.morales.chemicallab.service;
import com.morales.chemicallab.dto.SupervisionEvaluationResponse;
import com.morales.chemicallab.repository.SupervisionQueryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.Arrays;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
/** Aggregation and pagination are exercised against PostgreSQL in StorageQueriesDbTest. */
@ExtendWith(MockitoExtension.class)
class AcademicSupervisionServiceTest {
    @Mock SupervisionQueryRepository queries;
    @InjectMocks AcademicSupervisionService service;
    @Test void forwardsFiltersAndPageWithoutMaterializingEntities() {
        service.listEvaluations(2,25,"ácido","PUBLISHED","3 A",true);
        verify(queries).evaluations(2,25,"ácido","PUBLISHED","3 A",true);
    }
    @Test void metadataDoesNotExposeQuestionsOrAnswerKeys() {
        assertThat(Arrays.stream(SupervisionEvaluationResponse.class.getRecordComponents())
            .map(c->c.getName().toLowerCase()).filter(n->n.contains("correct")||n.contains("answer")||n.contains("questiontext"))).isEmpty();
    }
}
