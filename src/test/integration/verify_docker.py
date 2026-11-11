"""Prueba local explícita. Requiere compose.validation.yaml y datos descartables."""
import argparse
import http.cookiejar
import json
import subprocess
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone

BOOKING = 'http://127.0.0.1:18081'
CATALOG = 'http://127.0.0.1:18080'
STUB = 'http://127.0.0.1:18090'
GROUP = 'proyecto-pilar-machinea'
ACTIONS = 'alumnos.turnos.acciones.'+GROUP
PHONE = 'catedra.turnos.telefono.'+GROUP


def request(base, path, method='GET', body=None, token=None, expected=200, opener=None, headers=None):
    fields = dict(headers or {})
    if token:
        fields['Authorization'] = 'Bearer '+token
    data = None if body is None else json.dumps(body).encode()
    if data is not None:
        fields['Content-Type'] = 'application/json'
    req = urllib.request.Request(base+path, data=data, headers=fields, method=method)
    try:
        response = (opener or urllib.request.build_opener()).open(req, timeout=10)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        raw = response.read()
        assert response.status in (expected if isinstance(expected, tuple) else (expected,)), f'{method} {path}: esperado {expected}, obtenido {response.status}'
        return json.loads(raw) if raw else None


def wait_for(check, message, seconds=60):
    end = time.monotonic()+seconds
    while time.monotonic() < end:
        if check():
            return
        time.sleep(1)
    raise AssertionError(message)


def account():
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    username = 'docker'+uuid.uuid4().hex[:20]
    password = 'Only-local-validation-'+uuid.uuid4().hex
    csrf = request(BOOKING, '/api/csrf', opener=opener)
    fields = {csrf['headerName']: csrf['token']}
    request(BOOKING, '/api/register', 'POST', {'login': username, 'email': username+'@example.test',
            'password': password, 'firstName': 'Prueba', 'lastName': 'Docker'}, expected=201, opener=opener, headers=fields)
    csrf = request(BOOKING, '/api/csrf', opener=opener)
    response = request(BOOKING, '/api/authenticate', 'POST', {'username': username, 'password': password},
                       opener=opener, headers={csrf['headerName']: csrf['token']})
    return response['id_token']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--env-file', required=True, help='Archivo de claves descartables, nunca el .env real')
    args = parser.parse_args()
    compose = ['docker', 'compose', '--env-file', args.env_file, '-f', 'compose.integration.yaml',
               '-f', 'compose.validation.yaml', '-p', 'turnos-validation']

    def command(arguments, data=None, timeout=60):
        result = subprocess.run(compose+arguments, input=data, text=True, capture_output=True, timeout=timeout)
        assert result.returncode == 0, 'Falló comando de Compose de prueba: '+' '.join(arguments[:3])
        return result.stdout

    def publish(event):
        command(['exec', '-T', 'kafka', '/opt/kafka/bin/kafka-console-producer.sh', '--bootstrap-server', 'kafka:9092',
                 '--topic', ACTIONS, '--property', 'parse.key=true', '--property', 'key.separator=|'],
                event['reservationProcessId']+'|'+json.dumps(event)+'\n')

    def stamp():
        return datetime.now(timezone.utc).isoformat().replace('+00:00', 'Z')

    # Guard against accidentally using real integrations or an unrelated deployment.
    assert request(STUB, '/__test__/health') == {'testOnly': True}
    config = json.loads(command(['config', '--format', 'json']))
    environment = config['services']['booking']['environment']
    assert environment['BOOKING_CATEDRA_BASE_URL'] == 'http://catedra-stub:8090'
    assert environment['BOOKING_KAFKA_BOOTSTRAP_SERVERS'] == 'kafka:9092'
    assert config['services']['catalog']['environment']['CATEDRA_API_BASE_URL'] == 'http://catedra-stub:8090'
    assert request(CATALOG, '/actuator/health')['status'] == 'UP'
    assert request(BOOKING, '/actuator/health')['status'] == 'UP'
    token, other = account(), account()
    request(CATALOG, '/api/catalog/categories', expected=401)
    categories = request(CATALOG, '/api/catalog/categories', token=token)
    assert categories and categories[0]['id'] == 1
    assert request(CATALOG, '/api/catalog/synchronization/status', token=token)['initialized']
    print('OK: login, JWT entre servicios y catálogo cargado', flush=True)

    date = (datetime.now(timezone.utc)+timedelta(days=2)).date().isoformat()
    availability = request(BOOKING, '/api/availability?professionalId=15&date='+date, token=token)
    assert any(slot['startTime'] == '09:00:00' for slot in availability['slots'])
    hold_body = {'requestId': str(uuid.uuid4()), 'professionalId': 15, 'date': date, 'startTime': '09:00:00'}
    process = request(BOOKING, '/api/appointment-holds', 'POST', hold_body, token, 201)
    prefix = '/api/reservation-processes/'+process['id']
    repeated = request(BOOKING, '/api/appointment-holds', 'POST', hold_body, token)
    assert repeated['id'] == process['id']
    request(BOOKING, prefix, token=other, expected=404)
    request(BOOKING, prefix+'/confirm', 'POST', {'patientFirstName': 'Prueba', 'patientLastName': 'Docker'}, token, 202)
    phone_event = {'eventId': str(uuid.uuid4()), 'eventType': 'AdditionalInformationRequested',
                   'reservationProcessId': process['reservationProcessId'], 'holdId': process['holdId'],
                   'requiredField': 'PHONE_NUMBER', 'message': 'Prueba local', 'expiresAt': process['expiresAt'],
                   'occurredAt': stamp(), 'schemaVersion': 1}
    publish(phone_event)
    publish(phone_event)
    wait_for(lambda: (request(BOOKING, prefix+'/phone-request', token=token, expected=(200, 204)) or {}).get('requestEventId') == phone_event['eventId'],
             'No se recibió la solicitud de teléfono')
    phone = request(BOOKING, prefix+'/phone', 'POST', {'requestEventId': phone_event['eventId'], 'phoneNumber': '+5492610000000'}, token, 202)
    wait_for(lambda: request(BOOKING, prefix+'/phone-submission', token=token)['deliveryStatus'] == 'PUBLISHED',
             'El outbox no publicó el teléfono')
    consumed = subprocess.run(compose+['exec', '-T', 'kafka', '/opt/kafka/bin/kafka-console-consumer.sh',
        '--bootstrap-server', 'kafka:9092', '--topic', PHONE, '--from-beginning', '--timeout-ms', '5000'],
        capture_output=True, text=True, timeout=30)
    messages = [json.loads(line) for line in consumed.stdout.splitlines() if line.startswith('{')]
    submitted = next((item for item in messages if item.get('eventId') == phone['eventId']), None)
    assert submitted is not None, 'El teléfono no apareció en el topic local'
    assert submitted['eventId'] == phone['eventId']
    assert submitted['requestEventId'] == phone_event['eventId']
    assert submitted['reservationProcessId'] == process['reservationProcessId']
    print('OK: disponibilidad, hold idempotente, propietario y teléfono por Kafka', flush=True)

    remote = request(STUB, '/__test__/result', 'POST', {'reservationProcessId': process['reservationProcessId']})
    confirmed = dict(phone_event, eventId=str(uuid.uuid4()), eventType='AppointmentConfirmed', groupId=GROUP,
                     requestEventId=phone_event['eventId'], reservationId=remote['reservationId'], professionalId=15,
                     date=date, startTime='09:00:00', status='CONFIRMED', occurredAt=stamp())
    publish(confirmed)
    wait_for(lambda: request(BOOKING, prefix, token=token)['status'] == 'CONFIRMED', 'No se confirmó la reserva')
    mine = request(BOOKING, '/api/appointments', token=token)
    assert any(row['reservationProcessId'] == process['reservationProcessId'] for row in mine['content'])
    assert request(BOOKING, '/api/appointments', token=other)['content'] == []
    request(BOOKING, prefix+'/cancel', 'POST', {'reason': 'Prueba Docker'}, other, 404)
    cancelled = request(BOOKING, prefix+'/cancel', 'POST', {'reason': 'Prueba Docker'}, token)
    assert cancelled['processStatus'] == 'CANCELLED'
    print('OK: confirmación Kafka, consulta propia y cancelación REST', flush=True)

    command(['restart', 'catalog', 'booking'], timeout=120)
    command(['up', '-d', '--wait', '--wait-timeout', '180', 'catalog', 'booking'], timeout=210)
    assert request(CATALOG, '/api/catalog/synchronization/status', token=token)['initialized']
    assert request(BOOKING, prefix, token=token)['status'] == 'CANCELLED'
    publish(confirmed)  # Duplicate after restart must not reopen the process.
    reconciled = request(BOOKING, prefix+'/reconcile', 'POST', token=token)
    assert reconciled['process']['status'] == 'CANCELLED'
    print('OK: persistencia tras reinicio, duplicado y reconciliación sin regresión', flush=True)
    print('VALIDACIÓN LOCAL COMPLETA. Pendiente repetir contra cátedra real.', flush=True)


if __name__ == '__main__':
    main()
