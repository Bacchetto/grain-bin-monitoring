# Prometheus with this project's scrape config built in.
#
# Why build an image rather than bind-mount the file: on the development
# machine the repository lives on a mapped network drive, which the Docker
# engine inside Rancher Desktop's VM cannot see, so a bind mount of it is
# refused ("invalid volume specification"). A build sends its files to the
# engine over the Docker API instead, which works from any drive and on any
# machine. After changing prometheus.yml: docker compose up -d --build.
# See docs/decisions/0021-local-tooling-on-a-network-drive.md.

# Pinned, so a rebuild months from now runs what this was tested with.
FROM prom/prometheus:v3.15.0

COPY prometheus.yml /etc/prometheus/prometheus.yml
