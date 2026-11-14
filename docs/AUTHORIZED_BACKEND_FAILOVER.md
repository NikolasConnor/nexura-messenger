# Nexura authorized backend failover

This change adds an **optional, explicitly configured compatible Supabase read endpoint**. It does not discover public proxies, alter DNS/IP addresses, impersonate another domain, or tunnel traffic through third-party services.

## Configure

Set the Gradle project property `NEXURA_BACKUP_BASE_URL` to an HTTPS URL for infrastructure that Nexura owns or is authorized to use and that the mobile operator/network permits. Example for a local build:

```properties
NEXURA_BACKUP_BASE_URL=https://backup-api.example.net
```

If unset, the app continues to use the existing Supabase endpoint exactly as before.

## Failover behavior

- Health checks try the primary endpoint and then the configured backup.
- Idempotent GET requests try the primary and then the backup after connection failures or HTTP 5xx responses.
- POST/PATCH/DELETE remain pinned to the primary endpoint. They are never replayed automatically, avoiding duplicate sends and split writes.
- A backup must be a compatible Supabase endpoint with the same API surface, authentication/JWT validation, schema, RLS policies, and consistent data. A second unrelated Supabase project is not a drop-in replica.
- This is not a VPN or a whitelist bypass. A backup URL only helps if the operator/network actually allows that destination.

## Production requirements before enabling

1. Deploy a Nexura-controlled or explicitly authorized compatible backend.
2. Configure secure replication/consistency and shared auth validation; verify refresh-token behavior before enabling account operations.
3. Confirm the endpoint is reachable on the target MTS SIM during the actual restriction window. Hosting provider or Russian IP location alone does not imply allowlisting.
4. Keep TLS valid for the backup hostname. Never point a Nexura hostname at an unrelated service's IP or spoof its SNI/Host.
5. Exercise chat list, message reads, profiles, attachments, login, refresh, sending, and push flows against the production topology before release.

## Current limitation

This app change provides safe GET failover plumbing only. It does not claim a complete offline mode or an operational MTS route until a compatible, approved backup endpoint is deployed and tested.
