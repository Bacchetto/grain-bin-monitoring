# Grafana with this project's data source and dashboard built in.
#
# Built rather than bind-mounted for the same reason as ops/prometheus: the
# repository's network drive cannot be mounted into a container here. After
# changing anything under provisioning/ or dashboards/:
#   docker compose up -d --build

FROM grafana/grafana:13.2.2

# The data source, and the loader that picks up every dashboard file.
COPY provisioning /etc/grafana/provisioning
# The dashboard JSON itself: config as code, not clicks.
COPY dashboards /etc/grafana/dashboards
