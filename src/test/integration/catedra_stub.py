"""Simulador exclusivo para compose.validation.yaml; no contactar cátedra real."""
import json
import uuid
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlsplit, parse_qs

RECORDS = {}


def now():
    return datetime.now(timezone.utc).isoformat().replace('+00:00', 'Z')


def snapshot():
    stamp = now()
    common = {'enabled': True, 'createdAt': stamp, 'updatedAt': stamp}
    return {'snapshotVersion': 1, 'generatedAt': stamp,
            'professionalCategories': [dict(common, id=1, name='Prueba', description=None)],
            'professionals': [dict(common, id=15, categoryId=1, firstName='Ana', lastName='Prueba')],
            'weeklySchedules': [dict(common, id=21+i, professionalId=15, dayOfWeek=day,
                                     startTime='09:00:00', endTime='12:00:00', slotDurationMinutes=30)
                                for i, day in enumerate(['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY',
                                                         'FRIDAY', 'SATURDAY', 'SUNDAY'])]}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass  # No imprimir cuerpos, pacientes ni cabeceras.

    def reply(self, status, body, total=None):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(data)))
        if total is not None:
            self.send_header('X-Total-Count', str(total))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        url = urlsplit(self.path)
        query = parse_qs(url.query)
        if url.path == '/__test__/health':
            return self.reply(200, {'testOnly': True})
        if self.headers.get('Authorization') != 'Bearer local-test-only':
            return self.reply(401, {})
        if url.path == '/api/synchronization/snapshot':
            return self.reply(200, snapshot())
        if url.path == '/api/appointment-occupancies':
            return self.reply(200, {'professionalId': int(query['professionalId'][0]),
                                   'from': query['from'][0], 'to': query['to'][0], 'occupancies': []})
        if url.path == '/api/appointments':
            rows = [row for row in RECORDS.values() if row['externalPatientId'] == query['externalPatientId'][0]
                    and row['status'] != 'HELD']
            page, size = int(query.get('page', ['0'])[0]), int(query.get('size', ['20'])[0])
            return self.reply(200, rows[page*size:(page+1)*size], len(rows))
        return self.reply(404, {})

    def read_body(self):
        if self.headers.get('Transfer-Encoding', '').lower() != 'chunked':
            return self.rfile.read(int(self.headers.get('Content-Length', '0')))
        chunks = bytearray()
        while True:
            size = int(self.rfile.readline().split(b';')[0].strip(), 16)
            if size == 0:
                while self.rfile.readline().strip():
                    pass
                return bytes(chunks)
            if len(chunks) + size > 65536:
                raise ValueError('Test request too large')
            chunks.extend(self.rfile.read(size))
            self.rfile.read(2)

    def do_POST(self):
        body = json.loads(self.read_body() or '{}')
        path = urlsplit(self.path).path
        if path == '/__test__/result':
            row = RECORDS[body['reservationProcessId']]
            row['status'] = 'CONFIRMED'
            row['confirmedAt'] = now()
            return self.reply(200, row)
        if self.headers.get('Authorization') != 'Bearer local-test-only':
            return self.reply(401, {})
        if path == '/api/appointment-holds':
            process = 'process-' + str(uuid.uuid4())
            expiry = (datetime.now(timezone.utc)+timedelta(minutes=10)).isoformat().replace('+00:00', 'Z')
            row = dict(body, reservationProcessId=process, holdId='hold-'+str(uuid.uuid4()),
                       reservationId=1000+len(RECORDS), appointmentDate=body['date'],
                       endTime='09:30:00', status='HELD', createdAt=now(), confirmedAt=None,
                       cancelledAt=None, expiresAt=expiry)
            RECORDS[process] = row
            return self.reply(201, row)
        if path.startswith('/api/appointment-holds/') and path.endswith('/confirm'):
            hold = path.split('/')[3]
            row = next(row for row in RECORDS.values() if row['holdId'] == hold)
            if row['externalPatientId'] != body['externalPatientId']:
                return self.reply(400, {'code': 'EXTERNAL_PATIENT_MISMATCH'})
            row['status'] = 'PHONE_PENDING'
            return self.reply(202, dict(row, status='WAITING_FOR_PHONE'))
        if path.startswith('/api/appointments/') and path.endswith('/cancel'):
            row = RECORDS[path.split('/')[3]]
            row['status'], row['cancelledAt'] = 'CANCELLED', now()
            return self.reply(200, row)
        return self.reply(404, {})


if __name__ == '__main__':
    HTTPServer(('0.0.0.0', 8090), Handler).serve_forever()
