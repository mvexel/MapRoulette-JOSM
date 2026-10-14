# MapRoulette error responses

Error bodies as maproulette-backend produces them, used by the client error tests.
Taken from the backend source at commit d998921 (2026-10-10), because maproulette.org is not reachable from CI:

| File | Source |
|---|---|
| 401-not-authorized.json | MPExceptionUtil.manageException, OAuthNotAuthorizedException (no session; also what an old API-key header gets). `message` is null. |
| 403-locked-by-other.json | MPExceptionUtil, LockedException (another user holds the task lock) |
| 404-not-found.json | MPExceptionUtil, NotFoundException (TaskController.startOnTask message) |
| 409-lock-held.json | MPExceptionUtil, LockConflictException (the user already holds a lock on another task) |
| 409-change-conflict.json | MPExceptionUtil, ChangeConflictException (no lockedTaskId) |
| 410-api-keys-disabled.json | AuthController.apiKeysDisabled (generate/reset API key endpoints only) |
| 500-internal.json | MPExceptionUtil, any other exception (the message text is a placeholder) |

Message strings other than 401's null and 410's are illustrative; the plugin must not depend on them.
