# TokenSea local run

This code revision uses the `392xx` local port range.

## URLs

- Console: http://localhost:39210
- Control Plane API: http://localhost:39211
- Gateway Runtime: http://localhost:39212
- Postgres: `localhost:39213`
- Redis: `localhost:39214`
- Prometheus: http://localhost:39215
- Grafana: http://localhost:39216
- Runtime Core: http://localhost:39218

## Admin

- Username: `admin`
- Password: `TokenSea@local2026`

## Commands

```powershell
cd D:\12_其他项目\30_APIGateway\tokensea\deploy\compose
docker compose -p tokensea up -d
docker compose -p tokensea ps
docker compose -p tokensea logs -f --tail=200
docker compose -p tokensea down
```

## Notes

- Console API and gateway bases are compiled into the Vite static bundle.
- Rebuild `tokensea-console` after changing ports or frontend environment variables.
- The local `tokensea-local` Codex skill has been updated for this code revision.

