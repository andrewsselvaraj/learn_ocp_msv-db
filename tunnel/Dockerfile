# Tiny image whose only job is to hold an SSH tunnel open.
# Runs as a sidecar next to the app: app -> 127.0.0.1:3306 -> ssh -> MySQL on the remote server.
FROM registry.access.redhat.com/ubi9/ubi-minimal:latest

RUN microdnf install -y openssh-clients && microdnf clean all \
    # OpenShift runs containers with a random UID that has no /etc/passwd entry, and ssh
    # refuses to start without one. Let the entrypoint add it (group 0 can write).
    && chmod g=u /etc/passwd

COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod 755 /usr/local/bin/entrypoint.sh

ENV HOME=/tmp
USER 1001
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
