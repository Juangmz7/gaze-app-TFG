#!/usr/bin/env bash
# End-to-end test of the post-command-service native image against real backing services.
#
#   native-e2e/e2e.sh <image>              e.g. post-command-service:native-local
#   native-e2e/e2e.sh --binary <path>      a target/post-command-service built on this machine
#
# A native image can compile and still fail at run time (reflection, proxies or JSON types the
# build did not see), and a health check passes regardless, so this drives the real flows:
# JWT validation, a RabbitMQ consumer, post creation -> blob upload -> media verification ->
# outbox, a like round trip through RabbitMQ, a report with a JSON column and the ShedLock jobs.
# CI runs it on the built image before pushing it (.github/workflows/docker-publish.yml).
#
# Needs Docker, python3 and openssl. Everything runs on a private Docker network with no
# published ports, so it does not clash with a local stack.
set -u
if [ "${1:-}" = "--binary" ]; then MODE=binary; TARGET=$(realpath "$2"); else MODE=image; TARGET=${1:?usage: e2e.sh <image> | --binary <path>}; fi
DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
E=""
P=pcs-e2e                                        # container name prefix
NET=$P-net
# Like the clusters: tokens carry the public issuer, which the pods cannot reach, and the keys
# come from the in-cluster JWKS URL (infra/k8s/services/post-command-service/values-*.yaml).
ISS=https://auth.e2e.invalid/realms/auth-service
JWKS=http://$P-oidc:8080/realms/auth-service/protocol/openid-connect/certs
APP=http://$P-app:8082
PASS=0; FAIL=0
ok()    { echo "  PASS  $*"; PASS=$((PASS+1)); }
bad()   { echo "  FAIL  $*"; FAIL=$((FAIL+1)); }
check() { if [ "$2" = "$3" ]; then ok "$1 ($2)"; else bad "$1: expected $3, got $2"; fi; }
sql()   { docker exec $P-pg psql -U post_command_user -d post_command_service_db -tAc "$1" 2>/dev/null; }
api()   { docker exec -i $P-client curl -s "$@"; }
code()  { docker exec -i $P-client curl -s -o /dev/null -w "%{http_code}" "$@"; }
uuid()  { cat /proc/sys/kernel/random/uuid; }
cleanup() {
  docker rm -f $P-pg $P-rabbit $P-azurite $P-redis $P-oidc $P-client $P-app >/dev/null 2>&1
  docker network rm $NET >/dev/null 2>&1
  [ -n "$E" ] && rm -rf "$E"
}
trap cleanup EXIT
cleanup; E=$(mktemp -d); cp "$DIR"/*.py "$E/"

python3 "$E/jwt.py" init "$E/srv" "$ISS"
docker network create $NET >/dev/null
docker run -d --name $P-pg --network $NET -e POSTGRES_DB=post_command_service_db -e POSTGRES_USER=post_command_user \
  -e POSTGRES_PASSWORD=e2e_postgres_password postgres:16-alpine >/dev/null
docker run -d --name $P-rabbit --network $NET -e RABBITMQ_DEFAULT_USER=admin \
  -e RABBITMQ_DEFAULT_PASS=e2e_rabbitmq_password rabbitmq:4.3-management-alpine >/dev/null
docker run -d --name $P-azurite --network $NET mcr.microsoft.com/azure-storage/azurite \
  azurite-blob --blobHost 0.0.0.0 --skipApiVersionCheck --disableProductStyleUrl >/dev/null
docker run -d --name $P-redis --network $NET redis:7-alpine >/dev/null
docker run -d --name $P-oidc --network $NET -v "$E:/e2e:ro" -v "$E/srv:/srv:ro" python:3.13-alpine python /e2e/oidc.py >/dev/null
docker run -d --name $P-client --network $NET --entrypoint sleep curlimages/curl:8.11.1 infinity >/dev/null
for i in $(seq 1 90); do
  docker exec $P-pg pg_isready -U post_command_user >/dev/null 2>&1 \
    && docker exec $P-rabbit rabbitmq-diagnostics -q check_port_connectivity >/dev/null 2>&1 && break
  sleep 2
done
docker exec $P-oidc python /e2e/azurite_container.py http://$P-azurite:10000/devstoreaccount1 post-media-dev

APP_ENV=(-e SPRING_PROFILES_ACTIVE=dev
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://$P-pg:5432/post_command_service_db -e SPRING_DATASOURCE_PASSWORD=e2e_postgres_password
  -e SPRING_RABBITMQ_HOST=$P-rabbit -e SPRING_RABBITMQ_PASSWORD=e2e_rabbitmq_password -e SPRING_DATA_REDIS_HOST=$P-redis
  -e SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI=$ISS
  -e SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI=$JWKS
  -e AZURE_STORAGE_ACCOUNT_URL=http://$P-azurite:10000/devstoreaccount1
  # run the hourly ShedLock jobs every 5 s, to exercise their lock proxies
  -e "POSTS_MEDIACLEANUP_CRON=*/5 * * * * *" -e "POSTS_FAILEDPURGE_CRON=*/5 * * * * *"
  -e POSTS_MEDIACLEANUP_LOCKATLEASTFOR=PT1S -e POSTS_FAILEDPURGE_LOCKATLEASTFOR=PT1S)
# The pod as the chart runs it (infra/k8s/charts/microservice/values.yaml + the service's limit):
# non-root, read-only root filesystem with an emptyDir on /tmp, no capabilities, 1 GiB.
POD=(--user 10001:10001 --read-only --tmpfs /tmp:uid=10001,gid=10001 --cap-drop ALL
  --security-opt no-new-privileges --memory 1g)
if [ "$MODE" = image ]; then
  docker run -d --name $P-app --network $NET "${POD[@]}" "${APP_ENV[@]}" "$TARGET" >/dev/null
else
  docker run -d --name $P-app --network $NET "${POD[@]}" -v "$TARGET:/app/post-command-service:ro" \
    "${APP_ENV[@]}" container-registry.oracle.com/os/oraclelinux:10-slim /app/post-command-service >/dev/null
fi
for i in $(seq 1 60); do docker logs $P-app 2>&1 | grep -q "Started PostCommandServiceApplication\|APPLICATION FAILED\|Application run failed" && break; sleep 1; done

echo "=== startup ($MODE: $TARGET)"
STARTED=$(docker logs $P-app 2>&1 | grep -o "Started PostCommandServiceApplication in [0-9.]* seconds")
if [ -n "$STARTED" ]; then ok "$STARTED"; else bad "did not start"; fi
check "health" "$(api $APP/actuator/health | grep -o '"status":"[A-Z]*"' | head -1)" '"status":"UP"'

echo "=== security (JWT: public issuer + in-cluster JWKS, as in the charts)"
USER_ID=$(uuid)
AUTH=(-H "Authorization: Bearer $(python3 "$E/jwt.py" token "$E/srv" "$ISS" "$USER_ID")")
check "no token -> 401" "$(code $APP/api/posts/$USER_ID/collab-status)" 401
check "valid token, unknown post -> 404" "$(code "${AUTH[@]}" $APP/api/posts/$USER_ID/collab-status)" 404

echo "=== RabbitMQ consumer: UserRegisteredEvent -> users read model"
EVENT=$(printf '{"id":"%s","correlationId":"%s","occurredAt":"2026-01-01T10:00:00Z","userId":"%s","username":"e2e_user","email":"e2e@example.com","bio":{"description":"native bio","socialMedia":{"github":"e2e"}}}' \
  "$(uuid)" "$(uuid)" "$USER_ID")
BODY=$(python3 -c 'import json,sys; print(json.dumps({"properties":{"content_type":"application/json","headers":{"__TypeId__":"UserRegisteredEvent"}},"routing_key":"rk.user.registered","payload":sys.argv[1],"payload_encoding":"string"}))' "$EVENT")
for i in $(seq 1 20); do  # the management API may come up after AMQP
  PUBLISHED=$(api -u admin:e2e_rabbitmq_password -H 'content-type: application/json' -d "$BODY" http://$P-rabbit:15672/api/exchanges/%2F/x.user.events/publish)
  [ "$PUBLISHED" = '{"routed":true}' ] && break; sleep 1
done
check "published to x.user.events" "$PUBLISHED" '{"routed":true}'
for i in $(seq 1 20); do [ -n "$(sql "select username from users where id='$USER_ID'")" ] && break; sleep 1; done
check "user stored by the listener" "$(sql "select username from users where id='$USER_ID'")" e2e_user
check "bio (embedded, socialMedia JSON column)" "$(sql "select description || '/' || (social_media::jsonb->>'github') from users where id='$USER_ID'")" "native bio/e2e"

echo "=== create post -> upload to Azurite -> confirm -> RabbitMQ -> media verification -> outbox"
CREATE='{"correlationId":"'$(uuid)'","description":"native e2e","postTags":["java"],"media":[{"mediaType":"IMAGE","order":1,"taggedUsers":[]}]}'
RESP=$(api "${AUTH[@]}" -H 'content-type: application/json' -d "$CREATE" -w '\n%{http_code}' $APP/api/posts)
check "POST /api/posts" "$(echo "$RESP" | tail -1)" 201
POST_ID=$(echo "$RESP" | head -n -1 | python3 -c 'import json,sys; print(json.load(sys.stdin).get("postId",""))' 2>/dev/null)
UPLOAD_URL=$(echo "$RESP" | head -n -1 | python3 -c 'import json,sys; print(json.load(sys.stdin)["media"][0]["uploadUrl"])' 2>/dev/null)
check "status after create" "$(sql "select status from posts where id='$POST_ID'")" PENDING
echo "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==" | base64 -d > "$E/pixel.png"  # 1x1 PNG
check "PUT blob with the upload SAS" "$(docker exec -i $P-client curl -s -o /dev/null -w '%{http_code}' -X PUT -H 'x-ms-blob-type: BlockBlob' \
  -H 'content-type: image/png' --data-binary @- "${UPLOAD_URL:-http://$P-azurite:10000/no-upload-url}" < "$E/pixel.png")" 201
CONFIRM=$(code "${AUTH[@]}" -X POST $APP/api/posts/$POST_ID/media/confirm)
case "$CONFIRM" in 2??) ok "confirm media upload ($CONFIRM)";; *) bad "confirm media upload: $CONFIRM";; esac
for i in $(seq 1 30); do [ "$(sql "select status from posts where id='$POST_ID'")" = ACCEPTED ] && break; sleep 1; done
check "status after verification" "$(sql "select status from posts where id='$POST_ID'")" ACCEPTED
check "outbox events published" "$(sql "select string_agg(event_type||'='||status, ', ' order by event_type) from outbox_event")" \
  "PostCreatedEvent=PROCESSED, PostMediaUploadValidationSucceededEvent=PROCESSED"
check "collab-status (lazy media, no open-in-view)" "$(code "${AUTH[@]}" $APP/api/posts/$POST_ID/collab-status)" 200

echo "=== like -> outbox -> RabbitMQ command -> own listener -> post_likes"
LIKE=$(code "${AUTH[@]}" -H 'content-type: application/json' -d '{"context":{"source":"home_feed","feedPosition":0}}' -X POST $APP/api/posts/$POST_ID/like)
case "$LIKE" in 2??) ok "POST like ($LIKE)";; *) bad "POST like: $LIKE";; esac
for i in $(seq 1 20); do [ "$(sql "select count(*) from post_likes")" = 1 ] && break; sleep 1; done
check "like stored after the command round trip" "$(sql "select count(*) from post_likes")" 1

echo "=== report by another user (JSON column through Hibernate + Jackson)"
OTHER=(-H "Authorization: Bearer $(python3 "$E/jwt.py" token "$E/srv" "$ISS" "$(uuid)")")
REPORT=$(code "${OTHER[@]}" -H 'content-type: application/json' -d '{"reason":"SPAM","details":"native e2e"}' -X POST $APP/api/posts/$POST_ID/reports)
case "$REPORT" in 2??) ok "POST report ($REPORT)";; *) bad "POST report: $REPORT";; esac
check "snapshot stored as JSON" "$(sql "select snapshot::jsonb->>'description' from post_reports where post_id='$POST_ID'")" "native e2e"

echo "=== scheduled jobs through ShedLock"
for i in $(seq 1 15); do [ "$(sql "select count(*) from shedlock")" = 2 ] && break; sleep 1; done
check "locks taken by both jobs" "$(sql "select string_agg(name, ', ' order by name) from shedlock")" "failedPostPurgeJob, postMediaCleanupJob"

echo "=== native-specific errors in the log"
ERRS=$(docker logs $P-app 2>&1 | grep -E "MissingReflection|MissingResource|MissingJNI|UnsupportedFeature|ClassCastException|NoSuchMethod|ClassNotFound|InaccessibleObject|LazyInitialization| ERROR " \
  | grep -v -E "4318|OtlpHttp|Failed to export" | cut -c1-300 | sort | uniq -c | head -15)
if [ -z "$ERRS" ]; then ok "none"; else bad "found:"; echo "$ERRS"; fi
echo "  memory: $(docker stats --no-stream --format '{{.MemUsage}}' $P-app)"

echo "=== RESULT: $PASS passed, $FAIL failed"
if [ "$FAIL" != 0 ]; then
  echo "--- application log (last 120 lines) ---"; docker logs $P-app 2>&1 | tail -120
  exit 1
fi
