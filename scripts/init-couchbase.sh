#!/usr/bin/env sh
set -eu

CB_HOST="${CB_HOST:-couchbase}"
CB_ADMIN_USER="${CB_ADMIN_USER:-Administrator}"
CB_ADMIN_PASS="${CB_ADMIN_PASS:-password}"
CB_BUCKET="${CB_BUCKET:-test}"
CB_RAMSIZE_MB="${CB_RAMSIZE_MB:-1024}"
CB_BUCKET_RAM_MB="${CB_BUCKET_RAM_MB:-256}"

echo "Waiting for Couchbase UI/API at http://${CB_HOST}:8091 ..."
i=0
until curl -fs "http://${CB_HOST}:8091/ui/index.html" >/dev/null 2>&1; do
  i=$((i+1))
  echo "Couchbase UI/API not ready yet, retry ${i} ..."
  sleep 5

  if [ "$i" -ge 60 ]; then
    echo "Timed out waiting for Couchbase UI/API."
    exit 1
  fi
done

echo "Couchbase UI/API is reachable."

echo "Checking whether cluster is already initialized ..."
if curl -fs -u "${CB_ADMIN_USER}:${CB_ADMIN_PASS}" "http://${CB_HOST}:8091/pools/default" >/dev/null 2>&1; then
  echo "Cluster already initialized and authenticated."
else
  echo "Cluster is not initialized yet."
  echo "Initializing Couchbase cluster ..."

  i=0
  until couchbase-cli cluster-init \
      -c "${CB_HOST}:8091" \
      --cluster-username "${CB_ADMIN_USER}" \
      --cluster-password "${CB_ADMIN_PASS}" \
      --services data,index,query \
      --cluster-ramsize "${CB_RAMSIZE_MB}" \
      --cluster-index-ramsize 256 \
      --index-storage-setting default; do

    i=$((i+1))
    echo "cluster-init failed, retry ${i} ..."

    if [ "$i" -ge 12 ]; then
      echo "Timed out trying to initialize Couchbase cluster."
      echo "Diagnostic: unauthenticated /pools:"
      curl -s "http://${CB_HOST}:8091/pools" || true
      echo
      echo "Diagnostic: authenticated /pools:"
      curl -s -u "${CB_ADMIN_USER}:${CB_ADMIN_PASS}" "http://${CB_HOST}:8091/pools" || true
      echo
      exit 1
    fi

    sleep 5
  done
fi

echo "Waiting for authenticated cluster access to /pools/default ..."
i=0
until curl -fs -u "${CB_ADMIN_USER}:${CB_ADMIN_PASS}" "http://${CB_HOST}:8091/pools/default" >/dev/null 2>&1; do
  i=$((i+1))
  echo "Authenticated cluster access not ready yet, retry ${i} ..."
  sleep 5

  if [ "$i" -ge 60 ]; then
    echo "Timed out waiting for authenticated cluster access."
    echo "Diagnostic: authenticated /pools response:"
    curl -s -u "${CB_ADMIN_USER}:${CB_ADMIN_PASS}" "http://${CB_HOST}:8091/pools" || true
    echo
    exit 1
  fi
done

echo "Authenticated cluster access is ready."

echo "Checking whether bucket ${CB_BUCKET} exists ..."
if curl -fs -u "${CB_ADMIN_USER}:${CB_ADMIN_PASS}" \
    "http://${CB_HOST}:8091/pools/default/buckets/${CB_BUCKET}" >/dev/null 2>&1; then
  echo "Bucket ${CB_BUCKET} already exists."
else
  echo "Creating bucket ${CB_BUCKET} ..."

  if couchbase-cli bucket-create \
      -c "${CB_HOST}:8091" \
      -u "${CB_ADMIN_USER}" \
      -p "${CB_ADMIN_PASS}" \
      --bucket "${CB_BUCKET}" \
      --bucket-type couchbase \
      --bucket-ramsize "${CB_BUCKET_RAM_MB}" \
      --max-ttl 0 \
      --compression-mode passive \
      --bucket-replica 0 \
      --wait; then
    echo "Bucket ${CB_BUCKET} created."
  else
    echo "bucket-create failed. Checking whether bucket now exists ..."

    if curl -fs -u "${CB_ADMIN_USER}:${CB_ADMIN_PASS}" \
        "http://${CB_HOST}:8091/pools/default/buckets/${CB_BUCKET}" >/dev/null 2>&1; then
      echo "Bucket ${CB_BUCKET} exists after create attempt; continuing."
    else
      echo "Bucket ${CB_BUCKET} does not exist after failed create attempt."
      echo "Diagnostic: bucket list:"
      couchbase-cli bucket-list \
        -c "${CB_HOST}:8091" \
        -u "${CB_ADMIN_USER}" \
        -p "${CB_ADMIN_PASS}" || true
      exit 1
    fi
  fi
fi

echo "Waiting for bucket ${CB_BUCKET} to become visible ..."
i=0
until curl -fs -u "${CB_ADMIN_USER}:${CB_ADMIN_PASS}" \
    "http://${CB_HOST}:8091/pools/default/buckets/${CB_BUCKET}" >/dev/null 2>&1; do

  i=$((i+1))
  echo "Bucket ${CB_BUCKET} not visible yet, retry ${i} ..."
  sleep 5

  if [ "$i" -ge 60 ]; then
    echo "Timed out waiting for bucket ${CB_BUCKET}."
    echo "Diagnostic: bucket list:"
    couchbase-cli bucket-list \
      -c "${CB_HOST}:8091" \
      -u "${CB_ADMIN_USER}" \
      -p "${CB_ADMIN_PASS}" || true
    exit 1
  fi
done

echo "Bucket ${CB_BUCKET} is visible."

# Export the cluster certificate so TLS clients (e.g. the backend-TLS shim) can trust it.
# No-op unless a /shared-certs volume is mounted.
if [ -d /shared-certs ]; then
  echo "Exporting cluster certificate to /shared-certs/couchbase-cert.pem ..."
  if curl -fs "http://${CB_HOST}:8091/pools/default/certificate" -o /shared-certs/couchbase-cert.pem; then
    echo "Cluster certificate exported."
  else
    echo "Cluster certificate export failed (non-fatal)."
  fi
fi

echo "Couchbase initialization complete."