with base as materialized (
 select u.id user_id,u.username,u.role,s.id student_id,s.student_code,s.grade,s.section,
 coalesce(s.names||' '||s.last_names,t.names||' '||t.last_names) full_name
 from user_accounts u left join student_profiles s on s.user_id=u.id
 left join teacher_profiles t on t.user_id=u.id
 where (cast(:role as varchar) is null or u.role=:role) and (cast(:grade as varchar) is null or s.grade=:grade)
 and (cast(:section as varchar) is null or upper(s.section)=:section)
 and (cast(:userId as bigint) is null or u.id=cast(:userId as bigint))
 and (cast(:search as varchar) is null or position(lower(:search) in lower(u.username))>0 or position(lower(:search) in lower(u.email))>0 or position(lower(:search) in lower(s.student_code))>0 or position(lower(:search) in lower(s.names||' '||s.last_names))>0 or position(lower(:search) in lower(t.names||' '||t.last_names))>0)
), events as materialized (
 select e.user_id,e.module,e.occurred_at from usage_events e join base b on b.user_id=e.user_id
 where (cast(:module as varchar) is null or e.module=:module)
 and (cast(:from as timestamp) is null or e.occurred_at>=cast(:from as timestamp))
 and (cast(:to as timestamp) is null or e.occurred_at<cast(:to as timestamp))
), logs as materialized (
 select l.actor_user_id user_id,l.event_type,l.severity,l.created_at from system_logs l join base b on b.user_id=l.actor_user_id
 where (l.event_type='LOGIN_SUCCESS' or l.severity in ('WARNING','ERROR'))
 and (cast(:from as timestamp) is null or l.created_at>=cast(:from as timestamp))
 and (cast(:to as timestamp) is null or l.created_at<cast(:to as timestamp))
), attempts as materialized (
 select a.id,a.student_id,b.user_id,a.evaluation_id,a.status,a.started_at,a.submitted_at,a.overall_feedback
 from evaluation_attempts a join base b on b.student_id=a.student_id
 where (cast(:from as timestamp) is null or a.started_at>=cast(:from as timestamp))
 and (cast(:to as timestamp) is null or a.started_at<cast(:to as timestamp))
), assigned as materialized (
 select distinct b.user_id,a.evaluation_id from base b join evaluation_assignments a on trim(a.grade)=trim(b.grade) and upper(trim(a.section))=upper(trim(b.section))
 join evaluations e on e.id=a.evaluation_id where a.active=true and e.active=true and e.status='PUBLISHED'
), moments as (
 select user_id,occurred_at moment from events union all
 select user_id,created_at from logs where event_type='LOGIN_SUCCESS' union all
 select user_id,started_at from attempts union all select user_id,submitted_at from attempts
), gaps as (
 select user_id,moment,lag(moment) over(partition by user_id order by moment) previous from moments where moment is not null
), islands as (
 select user_id,moment,sum(case when previous is null or moment-previous>interval '30 minutes' then 1 else 0 end)
 over(partition by user_id order by moment rows unbounded preceding) island from gaps
), durations as (
 select user_id,island,floor(extract(epoch from max(moment)-min(moment))/60)::bigint minutes,max(moment) last_activity from islands group by user_id,island
), time_totals as (
 select d.user_id,sum(d.minutes)::bigint minutes,max(d.last_activity) last_activity
 from durations d group by d.user_id
), event_stats as (
 select user_id,count(*) event_count,count(distinct module) modules,string_agg(distinct module,',' order by module) visited_modules from events group by user_id
), log_stats as (
 select user_id,count(*) filter(where event_type='LOGIN_SUCCESS') sessions,
 count(*) filter(where event_type<>'LOGIN_SUCCESS' and severity='ERROR') errors,
 count(*) filter(where event_type<>'LOGIN_SUCCESS' and severity='WARNING') warnings from logs group by user_id
), attempt_stats as (
 select user_id,count(*) attempts,count(*) filter(where overall_feedback is not null and length(trim(overall_feedback))>0) feedback from attempts group by user_id
), assignment_stats as (
 select x.user_id,count(*) assigned,count(*) filter(where exists(select 1 from attempts a where a.user_id=x.user_id and a.evaluation_id=x.evaluation_id
 and a.status in ('SUBMITTED','PENDING_MANUAL_REVIEW','GRADED')
 and (cast(:from as timestamp) is null or a.submitted_at>=cast(:from as timestamp))
 and (cast(:to as timestamp) is null or a.submitted_at<cast(:to as timestamp)))) completed from assigned x group by x.user_id
), answer_stats as (
 select b.user_id,count(*) filter(where a.correct=true
 and (cast(:from as timestamp) is null or a.answered_at>=cast(:from as timestamp)) and (cast(:to as timestamp) is null or a.answered_at<cast(:to as timestamp))) correct,
 count(*) filter(where a.correct=false
 and (cast(:from as timestamp) is null or a.answered_at>=cast(:from as timestamp)) and (cast(:to as timestamp) is null or a.answered_at<cast(:to as timestamp))) incorrect,
 count(*) filter(where a.teacher_feedback is not null and (a.reviewed_at is null or
 ((cast(:from as timestamp) is null or a.reviewed_at>=cast(:from as timestamp)) and (cast(:to as timestamp) is null or a.reviewed_at<cast(:to as timestamp))))) feedback
 from evaluation_answers a join evaluation_attempts ea on ea.id=a.attempt_id join base b on b.student_id=ea.student_id group by b.user_id
), records as (
 select b.*,coalesce(tt.minutes,0) minutes,tt.last_activity,coalesce(l.sessions,0) sessions,coalesce(e.modules,0)::integer modules,e.visited_modules,
 coalesce(x.assigned,0) assigned,coalesce(x.completed,0) completed,
 case when b.role='ESTUDIANTE' and b.student_id is not null then round(x.completed*100.0/nullif(x.assigned,0),1)::double precision end progress,
 coalesce(a.attempts,0) attempts,coalesce(ans.correct,0) correct,coalesce(ans.incorrect,0) incorrect,
 case when b.role='ESTUDIANTE' and b.student_id is not null then round(ans.correct*100.0/nullif(ans.correct+ans.incorrect,0),1)::double precision end accuracy,
 coalesce(a.feedback,0)+coalesce(ans.feedback,0) feedback,coalesce(l.errors,0) errors,coalesce(l.warnings,0) warnings,
 (coalesce(l.sessions,0)>0 or coalesce(e.modules,0)>0 or coalesce(a.attempts,0)>0) has_activity
 from base b left join time_totals tt on tt.user_id=b.user_id left join event_stats e on e.user_id=b.user_id
 left join log_stats l on l.user_id=b.user_id left join attempt_stats a on a.user_id=b.user_id
 left join assignment_stats x on x.user_id=b.user_id left join answer_stats ans on ans.user_id=b.user_id
), filtered as materialized (select * from records where :onlyActivity=false or has_activity)
