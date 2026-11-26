begin;
do $guard$
begin
    if current_database() <> 'chemicallab_t12_runtime_test' or inet_server_addr() is distinct from '127.0.0.1'::inet
        or (select count(*) from user_accounts where username like 'green-roster-%') <> 20
        or not exists (select 1 from evaluation_runtime.institutions where name='Fictitious A') then
        raise exception 'Use a disposable copy of the prepared fictional dossier fixture';
    end if;
end $guard$;

insert into user_accounts(username,password,role,active,temporary_password,credentials_version,created_at)
select 'green-roster-'||n,u.password,'ESTUDIANTE',true,false,0,statement_timestamp()
from generate_series(21,30) n cross join user_accounts u where u.username='runtime-student';
insert into evaluation_runtime.memberships(institution_id,account_id,valid_from)
select i.id,u.id,statement_timestamp()-interval '1 minute'
from user_accounts u cross join evaluation_runtime.institutions i
where i.name='Fictitious A' and u.username like 'green-roster-%'
    and not exists (select 1 from evaluation_runtime.memberships m where m.account_id=u.id and m.institution_id=i.id);
insert into evaluation_runtime.roles(membership_id,institution_id,role,valid_from)
select m.id,m.institution_id,'ESTUDIANTE',statement_timestamp()-interval '1 minute'
from evaluation_runtime.memberships m join user_accounts u on u.id=m.account_id
where u.username like 'green-roster-%'
    and not exists (select 1 from evaluation_runtime.roles r where r.membership_id=m.id);
insert into evaluation_runtime.enrollments(institution_id,class_id,membership_id,account_id,valid_from,valid_until)
select m.institution_id,c.id,m.id,m.account_id,statement_timestamp()-interval '1 minute',statement_timestamp()+interval '1 day'
from evaluation_runtime.memberships m join user_accounts u on u.id=m.account_id
join evaluation_runtime.classes c on c.institution_id=m.institution_id and c.code='CHEM'
where u.username like 'green-roster-%'
    and not exists (select 1 from evaluation_runtime.enrollments e where e.class_id=c.id and e.account_id=m.account_id);
insert into student_profiles(user_id,teacher_id,student_code,names,last_names,grade,section)
select u.id,t.id,'LOCAL-'||u.id,'Estudiante ficticio','#'||u.id,'3','A'
from user_accounts u cross join teacher_profiles t join user_accounts a on a.id=t.user_id
where u.username like 'green-roster-%' and a.username='runtime-teacher-a'
    and not exists (select 1 from student_profiles s where s.user_id=u.id);
commit;
