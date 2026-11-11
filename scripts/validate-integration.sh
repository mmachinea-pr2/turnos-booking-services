#!/usr/bin/env bash
# Únicamente datos ficticios y cátedra simulada. No lee los .env de desarrollo.
set -euo pipefail
cd "$(dirname "$0")/.."
python3 - <<'PY'
import base64
import os
import secrets
from pathlib import Path
path = Path('.env.validation')
if not path.exists():
    values = {'DB_NAME': 'catalogo', 'DB_USER': 'catalogo_app',
              'BOOKING_DB_NAME': 'booking', 'BOOKING_DB_USER': 'booking_app',
              'BOOKING_JWT_SECRET': base64.b64encode(secrets.token_bytes(32)).decode()}
    for key in ('DB_PASSWORD', 'MYSQL_ROOT_PASSWORD', 'BOOKING_DB_PASSWORD', 'BOOKING_MYSQL_ROOT_PASSWORD'):
        values[key] = secrets.token_urlsafe(24)
    with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'w') as output:
        output.write(''.join(f'{key}={value}\n' for key, value in values.items()))
PY
compose=(docker compose --env-file .env.validation -f compose.integration.yaml -f compose.validation.yaml -p turnos-validation)
"${compose[@]}" config --quiet
"${compose[@]}" build
"${compose[@]}" up -d --wait --wait-timeout 180 catalog-db booking-db kafka catedra-stub
for topic in alumnos.turnos.acciones.proyecto-pilar-machinea catedra.turnos.telefono.proyecto-pilar-machinea; do
    "${compose[@]}" exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 \
        --create --if-not-exists --topic "$topic" --partitions 1 --replication-factor 1
done
"${compose[@]}" run --rm --no-deps catalog --spring.main.web-application-type=none --catalog.synchronization.run-on-startup=true
"${compose[@]}" up -d --wait --wait-timeout 180 catalog booking
python3 src/test/integration/verify_docker.py --env-file .env.validation
printf '%s\n' 'Los contenedores de prueba quedan disponibles. Ver documentación para detenerlos.'
