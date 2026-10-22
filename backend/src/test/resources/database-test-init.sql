-- Disposable local/test database only. Production credentials are installed separately.
CREATE ROLE personal_os LOGIN PASSWORD 'local-personal-os-only';
ALTER DATABASE personal_os OWNER TO personal_os;
REVOKE CONNECT, TEMPORARY ON DATABASE personal_os FROM PUBLIC;
GRANT CONNECT, TEMPORARY ON DATABASE personal_os TO personal_os;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
-- Model the existing infrastructure marker, owned by the PostgreSQL administrator.
CREATE TABLE public.infrastructure_restore_probe (id integer PRIMARY KEY);
