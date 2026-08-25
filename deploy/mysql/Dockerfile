FROM mysql:8.4

# Patch the vendor OS packages and remove administration-only runtimes that are
# not required by the PMS server process. The container runs directly as the
# mysql user, so the root-only gosu bootstrap helper is intentionally absent.
USER root
RUN microdnf upgrade -y \
    && microdnf clean all \
    && rm -rf /usr/lib/mysqlsh /usr/share/mysqlsh /usr/bin/mysqlsh /usr/local/bin/gosu
USER 999:999
