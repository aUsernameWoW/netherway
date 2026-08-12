# Anonymous quality telemetry

The Forge client sends schema v1 batches to:

```text
POST https://telemetry.ripplecraft.cn/v1/batches
Content-Type: application/json
```

The client uses a two-second connect timeout and a two-second response timeout on a daemon
thread. Failed sends remain in the bounded in-memory queue. A UUID v4 `batchId` is frozen with
the payload across retries, allowing the ingest process to deduplicate ambiguous retries for 24
hours without creating a persistent client identity.

The schema contains only the normalized build/runtime environment, coarse connection outcomes,
fixed failure codes, and attempt/latency buckets. It has no username, server address, room,
credential, stable installation ID, or arbitrary metadata field.

## Ingest behavior

- Only schema version 1 is accepted; unknown JSON fields and unknown enum values are rejected.
- Request bodies are capped at 64 KiB and batches at 128 summaries.
- The process has a global 10 request/second rate with a burst of 30 and accepts at most 16
  concurrent requests.
- Accepted or duplicate batches return `204`. Invalid JSON returns `400`, invalid schema values
  return `422`, rate limiting returns `429`, and the storage ceiling returns `507`.
- Neither the Go process nor the Caddy HTTP server has an access log. Source IPs are not written,
  hashed, or added to the event envelope.

On the server, the current UTC hour is an owner-only `*.ndjson.current` file under
`/var/lib/netherway-telemetry/YYYY/MM/DD/`. At the next hour it is atomically closed and compressed
to `*.ndjson.zst`. Each line is an object with a server `receivedAt` timestamp and the validated
client `batch`. Every accepted line is synced before the `204` response. Storage has a hard 500 MiB
ceiling and is never silently deleted.

Useful server-side checks:

```bash
systemctl status telemetry-ingest
journalctl -u telemetry-ingest --since today
find /var/lib/netherway-telemetry -type f -ls
```
