#!/usr/bin/env sh
set -eu

CB_HOST="${CB_HOST:-couchbase}"
CB_ADMIN_USER="${CB_ADMIN_USER:-Administrator}"
CB_ADMIN_PASS="${CB_ADMIN_PASS:-password}"
CB_BUCKET="${CB_BUCKET:-test}"
CB_RAMSIZE_MB="${CB_RAMSIZE_MB:-1024}"
CB_BUCKET_RAM_MB="${CB_BUCKET_RAM_MB:-256}"

echo "Waiting for Couchbase REST API at http://${CB_HOST}:8091 ..."
until curl -fs "http://${CB_HOST}:8091/ui/index.html" >/dev/null 2>&1; do
  sleep 5
done

echo "Couchbase UI/API is reachable."

echo "Waiting for Couchbase to expose pools endpoint ..."
until curl -fs "http://${CB_HOST}:8091/pools" >/dev/null 2>&1; do
  sleep 5
done

echo "Checking whether cluster is already initialized ..."
if curl -fs -u "${CB_ADMIN_USER}:${CB_ADMIN_PASS}" "http://${CB_HOST}:8091/pools/default" >/dev/null 2>&1; then
  echo "Cluster already initialized and authenticated."
else
  echo "Initializing Couchbase cluster ..."
  couchbase-cli cluster-init \
    -c "${CB_HOST}:8091" \
    --cluster-username "${CB_ADMIN_USER}" \
    --cluster-password "${CB_ADMIN_PASS}" \
    --services data,index,query \
    --cluster-ramsize "${CB_RAMSIZE_MB}" \
    --cluster-index-ramsize 256 \
    --index-storage-setting default
fi

echo "Waiting for authenticated cluster access to /pools/default ..."
i=0
until curl -fs -u "${CB_ADMIN_USER}:${CB_ADMIN_PASS}" "http://${CB_HOST}:8091/pools/default" >/dev/null 2>&1; do
  i=$((i+1))
  echo "Auth not ready yet, retry ${i} ..."
  sleep 5
  if [ "$i" -ge 60 ]; then
    echo "Timed out waiting for authenticated cluster access."
    echo "Diagnostic: /pools response:"
    curl -s "http://${CB_HOST}:8091/pools" || true
    exit 1
  fi
done

echo "Authenticated cluster access is ready."

echo "Checking whether bucket ${CB_BUCKET} exists ..."
if couchbase-cli bucket-list -c "${CB_HOST}:8091" -u "${CB_ADMIN_USER}" -p "${CB_ADMIN_PASS}" | grep -q "^${CB_BUCKET}[[:space:]]"; then
  echo "Bucket ${CB_BUCKET} already exists."
else
  echo "Creating bucket ${CB_BUCKET} ..."
  couchbase-cli bucket-create \
    -c "${CB_HOST}:8091" \
    -u "${CB_ADMIN_USER}" \
    -p "${CB_ADMIN_PASS}" \
    --bucket "${CB_BUCKET}" \
    --bucket-type couchbase \
    --bucket-ramsize "${CB_BUCKET_RAM_MB}" \
    --max-ttl 0 \
    --compression-mode passive \
    --bucket-replica 0 \
    --wait
fi

echo "Couchbase initialization complete."