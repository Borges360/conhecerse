"""Apply the migrations to an isolated local PostgreSQL used only by tests."""
import os
from pathlib import Path
import pg8000.native

root=Path(__file__).resolve().parents[1]
port=int(os.environ.get('TEST_PG_PORT','15439'))
conn=pg8000.native.Connection(user='postgres',host='127.0.0.1',port=port,database='postgres')
for role in ('conhecerse_runtime','conhecerse_web','conhecerse_migrator'):
    if not conn.run('select 1 from pg_roles where rolname=:role',role=role):
        conn.run(f'CREATE ROLE {role} LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS')
if not conn.run("select 1 from pg_database where datname='conhecerse'"):
    conn.run('CREATE DATABASE conhecerse OWNER conhecerse_migrator')
conn.close()
conn=pg8000.native.Connection(user='conhecerse_migrator',host='127.0.0.1',port=port,database='conhecerse')
conn.run('CREATE TABLE IF NOT EXISTS public.local_schema_history(name text primary key)')
for file in sorted((root/'backend/src/main/resources/db/migration').glob('*.sql')):
    if conn.run('select 1 from public.local_schema_history where name=:name',name=file.name):continue
    conn.run('BEGIN')
    try:
        conn.run(file.read_text(encoding='utf-8'))
        conn.run('insert into public.local_schema_history values(:name)',name=file.name)
        conn.run('COMMIT')
        print(file.name+' OK')
    except Exception:
        conn.run('ROLLBACK');raise
conn.close()
