package com.morales.chemicallab.service;
import com.morales.chemicallab.dto.*;
import com.morales.chemicallab.entity.*;
import com.morales.chemicallab.repository.StudentUsageQueryRepository;
import com.morales.chemicallab.security.storage.QueryLimits;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;
@Service @RequiredArgsConstructor @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=15)
public class StudentUsageRecordService {
    private final StudentUsageQueryRepository queries;
    private static final Pattern GRADE_PATTERN=Pattern.compile("^[1-5]$");
    private static final Pattern SECTION_PATTERN=Pattern.compile("^[A-Za-z]$");
    public StudentUsageRecordsResponse getRecords(String role,String search,String grade,String section,String from,String to,String module,String only) {
        return getRecords(role,search,grade,section,from,to,module,only,0,25);
    }
    public StudentUsageRecordsResponse getRecords(String role,String search,String grade,String section,String from,String to,String module,String only,int page,int size) {
        Filters f=parseFilters(role,QueryLimits.search(search),grade,section,from,to,module,only);
        var p=StudentUsageQueryRepository.empty();
        p.put("role",f.role()==null?null:f.role().name());p.put("search",f.search());p.put("grade",f.grade());p.put("section",f.section());
        p.put("from",f.from());p.put("to",f.to());p.put("module",f.module()==null?null:f.module().name());p.put("onlyActivity",f.onlyStudentsWithActivity());
        return queries.records(p,page,size);
    }
    public StudentUsageRecordDetailResponse getDetail(Long userId) { return getDetail(userId,0,20); }
    public StudentUsageRecordDetailResponse getDetail(Long userId,int page,int size) { return queries.detail(userId,page,size); }
    private record Filters(Role role, String search, String grade, String section,
                           LocalDateTime from, LocalDateTime to, UsageModule module,
                           boolean onlyStudentsWithActivity) {

        static Filters none() {
            return new Filters(null, null, null, null, null, null, null, false);
        }
    }

    /**
     * Valida y normaliza los parámetros del listado. Todos son opcionales; cualquier valor
     * inválido lanza {@link IllegalArgumentException}, que el manejador global convierte en
     * una respuesta 400 (nunca en un 500).
     */
    private Filters parseFilters(String roleParam, String search, String gradeParam, String sectionParam,
                                 String fromParam, String toParam, String moduleParam,
                                 String onlyWithActivityParam) {
        Role role = parseEnum(roleParam, Role.class, "El filtro de rol no es válido.");
        UsageModule module = parseEnum(moduleParam, UsageModule.class, "El filtro de módulo no es válido.");

        String grade = blankToNull(gradeParam);
        if (grade != null && !GRADE_PATTERN.matcher(grade.trim()).matches()) {
            throw new IllegalArgumentException("El grado debe ser un número entero del 1 al 5.");
        }
        String section = blankToNull(sectionParam);
        if (section != null) {
            if (!SECTION_PATTERN.matcher(section.trim()).matches()) {
                throw new IllegalArgumentException("La sección debe ser una sola letra (A-Z).");
            }
            section = section.trim().toUpperCase(Locale.ROOT);
        }

        LocalDate from = parseDate(fromParam, "La fecha «desde» no es válida (formato AAAA-MM-DD).");
        LocalDate to = parseDate(toParam, "La fecha «hasta» no es válida (formato AAAA-MM-DD).");
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("La fecha «desde» no puede ser posterior a la fecha «hasta».");
        }

        boolean onlyWithActivity = parseBoolean(onlyWithActivityParam);

        return new Filters(role, blankToNull(search), grade == null ? null : grade.trim(), section,
                from == null ? null : from.atStartOfDay(),
                to == null ? null : to.plusDays(1).atStartOfDay(),
                module, onlyWithActivity);
    }

    private <E extends Enum<E>> E parseEnum(String value, Class<E> type, String message) {
        String normalized = blankToNull(value);
        if (normalized == null || normalized.trim().equalsIgnoreCase("TODOS")) {
            return null;
        }
        try {
            return Enum.valueOf(type, normalized.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(message);
        }
    }

    private LocalDate parseDate(String value, String message) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            return null;
        }
        try {
            return LocalDate.parse(normalized.trim());
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException(message);
        }
    }

    private boolean parseBoolean(String value) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            return false;
        }
        String trimmed = normalized.trim();
        if (trimmed.equalsIgnoreCase("true")) {
            return true;
        }
        if (trimmed.equalsIgnoreCase("false")) {
            return false;
        }
        throw new IllegalArgumentException("El filtro de actividad debe ser true o false.");
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

}
