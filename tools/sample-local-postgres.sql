select json_build_object(
    'at', clock_timestamp(),
    'deadlocks', (select deadlocks from pg_stat_database where datname=current_database()),
    'backends', coalesce(json_agg(json_build_object(
        'pid', pid, 'state', state, 'wait', wait_event_type, 'event', wait_event,
        'transactionStart', xact_start, 'queryStart', query_start,
        'blocking', case when wait_event_type='Lock' then pg_blocking_pids(pid) else array[]::int[] end
    ) order by pid), '[]'::json)
)
from pg_stat_activity where datname=current_database() and application_name='PostgreSQL JDBC Driver';
\watch i=0.1
