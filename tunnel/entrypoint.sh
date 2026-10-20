#!/bin/sh
# Opens  127.0.0.1:${LOCAL_PORT}  inside the pod and forwards it over SSH to
# ${REMOTE_DB_HOST}:${REMOTE_DB_PORT} as seen from ${SSH_HOST}.
#
# Expects the Secret "db-ssh-key" mounted at /ssh with:
#   id_ed25519   private key (required)
#   known_hosts  host key of SSH_HOST (optional, but recommended)
set -eu

: "${SSH_HOST:?set SSH_HOST}"
: "${SSH_USER:?set SSH_USER}"
SSH_PORT="${SSH_PORT:-22}"
LOCAL_PORT="${LOCAL_PORT:-3306}"
REMOTE_DB_HOST="${REMOTE_DB_HOST:-127.0.0.1}"
REMOTE_DB_PORT="${REMOTE_DB_PORT:-3306}"
KEY_FILE="${KEY_FILE:-id_ed25519}"

# ssh needs a passwd entry for the current (random) UID.
if ! whoami >/dev/null 2>&1; then
    echo "tunnel:x:$(id -u):0:ssh tunnel:${HOME}:/sbin/nologin" >> /etc/passwd
fi

# Secret files are mounted group-readable; ssh rejects keys that aren't private to the
# user, so copy the key somewhere we own and lock it down.
mkdir -p "${HOME}/.ssh"
cp "/ssh/${KEY_FILE}" "${HOME}/.ssh/id_key"
chmod 700 "${HOME}/.ssh"
chmod 600 "${HOME}/.ssh/id_key"

if [ -s /ssh/known_hosts ]; then
    cp /ssh/known_hosts "${HOME}/.ssh/known_hosts"
    HOST_CHECK="StrictHostKeyChecking=yes"
else
    echo "WARNING: no known_hosts in Secret db-ssh-key; trusting the server's host key on first connect"
    HOST_CHECK="StrictHostKeyChecking=accept-new"
fi

echo "Tunnel 127.0.0.1:${LOCAL_PORT} -> ${SSH_USER}@${SSH_HOST}:${SSH_PORT} -> ${REMOTE_DB_HOST}:${REMOTE_DB_PORT}"

# -N: no remote command, just forward. ExitOnForwardFailure + keepalives make ssh exit
# when the tunnel breaks. We retry in a loop instead of letting the container exit:
# a crashing sidecar would mark the whole pod NotReady and take the web page offline,
# when what you want is the page up and showing the DB error.
# Running as PID 1, so forward pod shutdown to ssh ourselves.
trap 'kill "${SSH_PID:-}" 2>/dev/null; exit 0' TERM INT
while true; do
    ssh -N \
        -i "${HOME}/.ssh/id_key" \
        -p "${SSH_PORT}" \
        -o "${HOST_CHECK}" \
        -o UserKnownHostsFile="${HOME}/.ssh/known_hosts" \
        -o ExitOnForwardFailure=yes \
        -o ConnectTimeout=10 \
        -o ServerAliveInterval=30 \
        -o ServerAliveCountMax=3 \
        -o BatchMode=yes \
        -L "127.0.0.1:${LOCAL_PORT}:${REMOTE_DB_HOST}:${REMOTE_DB_PORT}" \
        "${SSH_USER}@${SSH_HOST}" &
    SSH_PID=$!
    wait "${SSH_PID}" || true
    echo "$(date -u +%FT%TZ) tunnel down (ssh exited), retrying in ${RETRY_SECONDS:-10}s"
    sleep "${RETRY_SECONDS:-10}"
done
