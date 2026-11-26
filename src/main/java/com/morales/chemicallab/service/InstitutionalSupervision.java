package com.morales.chemicallab.service;

import com.morales.chemicallab.security.SessionPrincipal;
import com.morales.chemicallab.service.InstitutionalAdministration.Library;
import com.morales.chemicallab.service.InstitutionalAdministration.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class InstitutionalSupervision {
    public enum Section { PERIODS, CLASSES, MEMBERS, ENROLLMENTS, TEACHING, RESOURCES, FACULTIES }

    private final JdbcTemplate jdbc;
    private final InstitutionalAdministration administration;

    public Page list(SessionPrincipal actor, long institution, Section section, Library library, long after, int limit) {
        return administration.scoped(actor, institution, false, admin ->
            administration.page(switch (section) {
                case PERIODS -> """
                    select id,name,open,valid_from,valid_until from evaluation_runtime.periods
                    where institution_id=? and id>?
                    """;
                case CLASSES -> """
                    select c.id,c.code,c.name,c.period_id,p.name period,c.active,
                      (c.active and p.open and statement_timestamp()>=p.valid_from and statement_timestamp()<p.valid_until) effective
                    from evaluation_runtime.classes c join evaluation_runtime.periods p on p.id=c.period_id
                    where c.institution_id=? and c.id>?
                    """;
                case MEMBERS -> """
                    select m.id,coalesce(n.display_name,'Miembro #'||m.id) name,
                      case when m.valid_until<=statement_timestamp() then 'ENDED'
                           when not m.active then 'SUSPENDED' else 'ACTIVE' end state,
                      (select string_agg(g.role,',' order by g.role) from evaluation_runtime.roles g
                         where g.membership_id=m.id and g.revoked_at is null
                           and g.valid_from<=statement_timestamp()
                           and (g.valid_until is null or g.valid_until>statement_timestamp())) roles
                    from evaluation_runtime.memberships m
                    left join administration_runtime.member_names n on n.membership_id=m.id and n.institution_id=m.institution_id
                    where m.institution_id=? and m.id>?
                    """;
                case ENROLLMENTS -> episodes("enrollments", "");
                case TEACHING -> episodes("teaching", ",e.faculty");
                case FACULTIES -> "select id,resource_id,membership_id,faculty,valid_from,valid_until,revoked_at from "
                    + library.schema + "faculties where institution_id=? and id>?";
                case RESOURCES -> """
                    select r.id,r.origin_class_id,r.author_membership_id,
                      coalesce(v.title,'Recurso #'||r.id) name, v.status
                    from %1$sresources r left join lateral (
                      select %2$s title,status from %1$srevisions
                      where institution_id=r.institution_id and resource_id=r.id order by revision_number desc limit 1
                    ) v on true where r.institution_id=? and r.id>?
                    """.formatted(library.schema, library == Library.CONTENT ? "title" : "payload->>'title'");
            }, after, limit, "id", institution));
    }

    public Map<String,Object> summary(SessionPrincipal actor, long institution) {
        return administration.scoped(actor, institution, false, admin -> jdbc.queryForMap("""
            with scope as (select ?::bigint id,statement_timestamp() at)
            select (select count(*) from evaluation_runtime.memberships m join user_accounts u on u.id=m.account_id
                      where m.institution_id=s.id and u.active and m.active and m.valid_from<=s.at
                        and (m.valid_until is null or s.at<m.valid_until)) members,
                   (select count(*) from evaluation_runtime.classes c
                      join evaluation_runtime.periods p on p.id=c.period_id
                      where c.institution_id=s.id and c.active and p.open and p.valid_from<=s.at and s.at<p.valid_until) classes,
                   (select count(*) from evaluation_runtime.resources where institution_id=s.id) evaluations,
                   (select count(*) from content_runtime.resources where institution_id=s.id) contents,
                   (select count(*) from board_runtime.boards where institution_id=s.id) boards
            from scope s
            """, institution));
    }

    public Page events(SessionPrincipal actor, long institution, boolean includeAdministration,
                       Long member, long before, int limit) {
        if (before < 0 || limit < 1 || limit > 40) throw InstitutionalAdministration.invalid("Página inválida.");
        return administration.scoped(actor, institution, false, admin -> {
            if (member != null) administration.require("evaluation_runtime.memberships", institution, member);
            var args = new ArrayList<Object>();
            var queries = new ArrayList<String>();
            for (var source : List.of(
                    new Source("evaluation_runtime.operations", "EVALUATIONS", "evaluation_id"),
                    new Source("content_runtime.operations", "CONTENT", "resource_id"),
                    new Source("board_runtime.operations", "BOARDS", "board_id"))) {
                queries.add("""
                    (select o.audit_id id,'%s' source,o.action,o.%s target_id,m.id actor_membership_id,
                            null::bigint subject_membership_id,o.class_id,l.created_at
                     from %s o join system_logs l on l.id=o.audit_id and l.actor_user_id=o.actor_id and l.outcome='SUCCESS'
                     join evaluation_runtime.memberships m on m.institution_id=o.institution_id and m.account_id=o.actor_id
                     where o.institution_id=? and o.audit_id<? %s order by o.audit_id desc limit ?)
                    """.formatted(source.label(), source.target(), source.table(), member == null ? "" : "and m.id=?"));
                eventArgs(args, institution, before, member, limit);
            }
            if (includeAdministration) {
                queries.add("""
                    (select o.audit_id id,'ADMINISTRATION' source,o.action,o.target_id,m.id actor_membership_id,
                            o.subject_membership_id,null::bigint class_id,l.created_at
                     from administration_runtime.changes o join system_logs l on l.id=o.audit_id
                     left join evaluation_runtime.memberships m on m.institution_id=o.institution_id and m.account_id=o.actor_id
                     where o.institution_id=? and o.audit_id<? %s order by o.audit_id desc limit ?)
                    """.formatted(member == null ? "" : "and (o.subject_membership_id=? or m.id=?)"));
                args.add(institution);
                args.add(before == 0 ? Long.MAX_VALUE : before);
                if (member != null) args.addAll(List.of(member, member));
                args.add(limit + 1);
            }
            args.add(limit + 1);
            var rows = jdbc.queryForList(String.join(" union all ", queries) + " order by id desc limit ?", args.toArray());
            return InstitutionalAdministration.page(rows, limit);
        });
    }

    private static void eventArgs(List<Object> args, long institution, long before, Long member, int limit) {
        args.add(institution);
        args.add(before == 0 ? Long.MAX_VALUE : before);
        if (member != null) args.add(member);
        args.add(limit + 1);
    }

    private static String episodes(String table, String projection) {
        return """
            select e.id,e.class_id,e.membership_id,c.name class_name,e.valid_from,e.valid_until,e.ended_at%s
            from evaluation_runtime.%s e join evaluation_runtime.classes c on c.id=e.class_id
            where e.institution_id=? and e.id>?
            """.formatted(projection, table);
    }

    private record Source(String table, String label, String target) {}
}
