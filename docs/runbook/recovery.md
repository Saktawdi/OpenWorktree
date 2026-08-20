# Recovery Runbook (ADR-008, production-architecture §15)

## DB PITR

1. Pause publish traffic (`gatectl pause-publish`)
2. Verify no RUNNING tasks: `SELECT count(*) FROM gate_task WHERE status='RUNNING'` must 0; else `BackupService.restoreDbPreCheck()` fails
3. Restore DB from `backup_manifest.s3_manifest_key` (S3)
4. Run `SELECT * FROM audit_checkpoint` verify chain
5. Execute full publish reconcile: `gate reconcile --all` (checks `refObserver.published`)
6. Verify `gate_nonce` and `task_event` sequence continuity
7. Resume traffic

## Object Store

- Manifest stored `backup/<id>/manifest.json` with sha256
- Orphan GC: `S3Store.gcOrphans(threshold 7d)`; enterprise S3 Object Lock WORM retains audit/* 365d

## Git

- Backup: `git clone --mirror` + `git bundle create auth.bundle --all` -> S3 `backup/<id>/auth.bundle`
- Restore: `git bundle verify` + `git push --mirror`
- Post-restore: run `publishIntentRepository.findPending()` + `refObserver.published` converge

## KMS Unavailable

- Fail-closed: `LocalAuthoritativeGitService.casPublish` returns `authorization expired`/`nonce` error
- Read-only `/status/slo` remains available; writes block until `kms.keyRing()` ok

## Rolling Upgrade

1. Deploy new Web with `priority DESC` claim intact (expand/contract)
2. Drain Worker: `Worker draining` + lease renewal stop, reaper will RETRY_WAIT
3. Verify `/readyz` fails during draining, `/livez` stays ok
4. Upgrade DB migration (Flyway) with `V14__...` expand phase; old code reads both
