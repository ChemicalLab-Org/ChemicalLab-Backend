package com.morales.chemicallab.service;
import com.morales.chemicallab.dto.*;
import com.morales.chemicallab.repository.SupervisionQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.List;
@Service @RequiredArgsConstructor @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=15)
public class AcademicSupervisionService {
    private final SupervisionQueryRepository queries;
    public SupervisionSummaryResponse getSummary(){return queries.summary();}
    public List<SupervisionConceptResponse> listConcepts(){return listConcepts(0,25,null,null,null,null).content();}
    public QueryPage<SupervisionConceptResponse> listConcepts(int page,int size,String search,String status,String category,String section){return queries.concepts(page,size,search,status,category,section);}
    public List<SupervisionEvaluationResponse> listEvaluations(){return listEvaluations(0,25,null,null,null,false).content();}
    public QueryPage<SupervisionEvaluationResponse> listEvaluations(int page,int size,String search,String status,String section,boolean withAttempts){return queries.evaluations(page,size,search,status,section,withAttempts);}
    public List<SupervisionAssignmentResponse> listAssignments(){return listAssignments(0,25,null,null,null,null,null).content();}
    public QueryPage<SupervisionAssignmentResponse> listAssignments(int page,int size,String search,String type,String grade,String section,String active){return queries.assignments(page,size,search,type,grade,section,active);}
    public SupervisionQueryRepository.Options options(){return queries.options();}
}
