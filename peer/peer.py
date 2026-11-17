#!/usr/bin/env python3
"""The LAN peer of the workload's network and streaming cases (stdlib only).

Runs on the host for the duration of one suite run, bound to its LAN address:

    peer.py --bind 192.168.1.210 --port 47123 --token TOKEN --run RUN_ID --apk workload.apk [--log peer.json]

Every request needs the run's token (header X-Afdo-Token) and, with --run,
that run's ID (X-Afdo-Run); X-Afdo-Key names the attempt, and the counters
are kept per attempt as well as per run.  Endpoints:
  HEAD/GET /media/NAME      a clip from the APK's assets/media (stored
                            uncompressed), single ranges of at most 1 MiB
  GET  /rpc?id=N&size=S     S bytes (at most 64 KiB) of a deterministic
                            payload; X-Afdo-Crc holds its CRC32
  GET  /bulk?id=N&bytes=B   B bytes (at most 64 MiB) streamed; the CRC32 is
                            recorded under the id in the attempt's counters
  POST /upload?id=N         body at most 64 MiB; replies with its CRC32
  GET  /stats?run=R[&key=K] the run's counters (JSON); with key, the
                            attempt's counters, with the run's under "run"
  UDP  same port            "N:TOKEN:payload" is echoed back as "N:payload"
Limits: 16 concurrent requests (503 beyond); a budget of 4 GiB for
everything the peer sends and receives, UDP included: each transfer
reserves its size before it starts (429 or a dropped datagram when it
doesn't fit) and is charged what it moved, a failed send in full; 30 s
socket timeouts; nothing outside the APK's media is ever served.  The
counters are written to --log on exit.
"""
import argparse, json, re, signal, socket, sys, threading, time, zipfile, zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

MAX_SMALL = 64 << 10
MAX_RANGE = 1 << 20
MAX_BULK = 64 << 20
RUN_BUDGET = 4 << 30
CONCURRENCY = 16
ID = re.compile(r'[A-Za-z0-9_-]{1,64}')
KEY = re.compile(r'[A-Za-z0-9._-]{1,128}')


def payload(n, size):
    """Deterministic bytes for request n: a counter pattern seeded by n."""
    b = bytearray(size)
    x = (n * 2654435761) & 0xffffffff
    for i in range(0, size, 4):
        x = (x * 1103515245 + 12345) & 0xffffffff
        b[i:i + 4] = x.to_bytes(4, 'little')[:size - i]
    return bytes(b)


def counters():
    return {'requests': 0, 'bytes_out': 0, 'bytes_in': 0, 'rejected': 0, 'udp_in': 0, 'udp_out': 0,
            'bulk_crc': {}, 'by_path': {}}


class Stats:
    """Counters per run and per attempt, and the budget every transfer reserves from."""

    def __init__(self, budget):
        self.lock = threading.Lock()
        self.budget = budget
        self.used = 0
        self.reserved = 0
        self.runs = {}

    def _entries(self, run, key):
        r = self.runs.setdefault(run, dict(counters(), attempts={}))
        return r, r['attempts'].setdefault(key, counters())

    def add(self, run, key, path=None, crc=None, **kv):
        with self.lock:
            for e in self._entries(run, key):
                for k, v in kv.items():
                    e[k] += v
                if path:
                    e['by_path'][path] = e['by_path'].get(path, 0) + 1
                if crc:
                    e['bulk_crc'][crc[0]] = crc[1]

    def reserve(self, n):
        """Reserve n bytes of the budget; False when they don't fit."""
        with self.lock:
            if n < 0 or self.used + self.reserved + n > self.budget:
                return False
            self.reserved += n
            return True

    def settle(self, reserved, moved):
        """A reserved transfer ended: release its reservation, charge what it moved."""
        with self.lock:
            self.reserved -= reserved
            self.used += moved

    def snapshot(self, run, key=None):
        with self.lock:
            r = self.runs.get(run)
            if r is None:
                return {}
            if key is None:
                return json.loads(json.dumps(r))
            a = dict(r['attempts'].get(key, counters()))
            a['run'] = {k: v for k, v in r.items() if k != 'attempts'}
            return json.loads(json.dumps(a))


def main():
    ap = argparse.ArgumentParser(description=__doc__.split('\n\n')[0])
    ap.add_argument('--bind', required=True, help='the LAN address to listen on')
    ap.add_argument('--port', type=int, default=47123)
    ap.add_argument('--token', required=True)
    ap.add_argument('--run', help='serve only this run ID')
    ap.add_argument('--budget', type=int, default=RUN_BUDGET, help='bytes moved in all (default 4 GiB)')
    ap.add_argument('--apk', required=True, help='the workload APK (its assets/media are served)')
    ap.add_argument('--log', help='write the counters here on exit (JSON)')
    a = ap.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9]{8,64}', a.token):
        sys.exit('the token must be 8-64 letters and digits')
    if a.run is not None and not ID.fullmatch(a.run):
        sys.exit('bad --run')
    media = {}
    with zipfile.ZipFile(a.apk) as z:
        for info in z.infolist():
            if info.filename.startswith('assets/media/'):
                media[info.filename[len('assets/media/'):]] = z.read(info)
    stats = Stats(a.budget)
    slots = threading.BoundedSemaphore(CONCURRENCY)

    class Handler(BaseHTTPRequestHandler):
        protocol_version = 'HTTP/1.1'
        timeout = 30

        def log_message(self, fmt, *args):
            pass

        def deny(self, code, run='-', key='-'):
            stats.add(run, key, rejected=1)
            self.send_response(code)
            self.send_header('Content-Length', '0')
            self.end_headers()

        def handle_one_request(self):
            if not slots.acquire(blocking=False):
                self.raw_requestline = self.rfile.readline(65537)
                if self.raw_requestline and self.parse_request():
                    self.close_connection = True
                    self.deny(503)
                self.close_connection = True
                return
            try:
                super().handle_one_request()
            finally:
                slots.release()

        def check(self):
            """(run, key) of an authorized request, else None (denied)."""
            run = self.headers.get('X-Afdo-Run', '-')
            if not ID.fullmatch(run):
                run = '-'
            key = self.headers.get('X-Afdo-Key', '-')
            if not KEY.fullmatch(key):
                key = '-'
            if self.headers.get('X-Afdo-Token') != a.token or (a.run is not None and run != a.run):
                self.deny(403, run, key)
                return None
            return run, key

        def send(self, run, key, code, body, headers=(), path=None):
            n = 0 if self.command == 'HEAD' else len(body)
            if not stats.reserve(n):
                return self.deny(429, run, key)
            moved = 0
            try:
                self.send_response(code)
                for k, v in headers:
                    self.send_header(k, v)
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                if n:
                    moved = n                      # a failed send is charged in full
                    self.wfile.write(body)
            finally:
                stats.settle(n, moved)
                stats.add(run, key, path=path, requests=1, bytes_out=moved)

        def do_HEAD(self):
            self.do_GET()

        def do_GET(self):
            ok = self.check()
            if ok is None:
                return
            run, key = ok
            u = urlparse(self.path)
            q = {k: v[0] for k, v in parse_qs(u.query).items()}
            path = u.path.split('/')[1] if '/' in u.path else u.path
            if u.path.startswith('/media/'):
                data = media.get(u.path[len('/media/'):])
                if data is None:
                    return self.deny(404, run, key)
                rng = self.headers.get('Range')
                if rng:
                    m = re.fullmatch(r'bytes=(\d+)-(\d*)', rng)
                    if not m:
                        return self.deny(416, run, key)
                    lo = int(m.group(1)); hi = int(m.group(2)) if m.group(2) else len(data) - 1
                    if lo >= len(data) or hi < lo:
                        return self.deny(416, run, key)
                    hi = min(hi, len(data) - 1)
                    if hi - lo + 1 > MAX_RANGE:
                        return self.deny(413, run, key)
                    return self.send(run, key, 206, data[lo:hi + 1], [('Content-Range', 'bytes %d-%d/%d' % (lo, hi, len(data))),
                                                                      ('Accept-Ranges', 'bytes'), ('Content-Type', 'video/mp4')], path)
                if self.command == 'HEAD':
                    return self.head(run, key, len(data), path)
                return self.deny(416, run, key)    # whole-file GETs are not served: ranges only
            if u.path == '/rpc':
                try:
                    n, size = int(q.get('id', '0')), int(q.get('size', '0'))
                except ValueError:
                    return self.deny(400, run, key)
                if not 0 <= size <= MAX_SMALL:
                    return self.deny(413, run, key)
                body = payload(n, size)
                return self.send(run, key, 200, body, [('X-Afdo-Crc', '%08x' % zlib.crc32(body))], path)
            if u.path == '/bulk':
                try:
                    n, size = int(q.get('id', '0')), int(q.get('bytes', '0'))
                except ValueError:
                    return self.deny(400, run, key)
                if not 0 < size <= MAX_BULK:
                    return self.deny(413, run, key)
                if not stats.reserve(size):
                    return self.deny(429, run, key)
                block, crc, sent = payload(n, 1 << 16), 0, 0
                try:
                    self.send_response(200)
                    self.send_header('Content-Length', str(size))
                    self.end_headers()
                    while sent < size:
                        part = block[:min(len(block), size - sent)]
                        sent += len(part)           # charged even if this write fails
                        self.wfile.write(part)
                        crc = zlib.crc32(part, crc)
                finally:
                    stats.settle(size, sent)
                    stats.add(run, key, path=path, requests=1, bytes_out=sent,
                              crc=(str(n), '%08x' % crc) if sent == size else None)
                return
            if u.path == '/stats':
                r = q.get('run', run)
                k = q.get('key')
                if not ID.fullmatch(r) or (k is not None and not KEY.fullmatch(k)):
                    return self.deny(400, run, key)
                body = json.dumps(stats.snapshot(r, k), sort_keys=True).encode()
                return self.send(run, key, 200, body, [('Content-Type', 'application/json')], path)
            return self.deny(404, run, key)

        def head(self, run, key, length, path):
            self.send_response(200)
            self.send_header('Content-Length', str(length))
            self.send_header('Accept-Ranges', 'bytes')
            self.end_headers()
            stats.add(run, key, path=path, requests=1)

        def do_POST(self):
            ok = self.check()
            if ok is None:
                return
            run, key = ok
            u = urlparse(self.path)
            if u.path != '/upload':
                return self.deny(404, run, key)
            try:
                size = int(self.headers.get('Content-Length', '-1'))
            except ValueError:
                return self.deny(411, run, key)
            if not 0 < size <= MAX_BULK:
                return self.deny(413, run, key)
            if not stats.reserve(size):
                return self.deny(429, run, key)
            crc, got = 0, 0
            try:
                while got < size:
                    part = self.rfile.read(min(1 << 16, size - got))
                    if not part:
                        break
                    crc = zlib.crc32(part, crc)
                    got += len(part)
            finally:
                stats.settle(size, got)
                stats.add(run, key, bytes_in=got)
            self.send(run, key, 200, ('%08x %d' % (crc, got)).encode(), path='upload')

    def udp():
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.bind((a.bind, a.port))
        tok = a.token.encode()
        while True:
            data, addr = s.recvfrom(2048)
            parts = data.split(b':', 2)
            if len(parts) != 3 or parts[1] != tok:
                continue
            reply = parts[0] + b':' + parts[2]
            n = len(data) + len(reply)
            if not stats.reserve(n):
                stats.add('udp', '-', rejected=1)
                continue
            stats.settle(n, n)
            stats.add('udp', '-', udp_in=1, bytes_in=len(data))
            s.sendto(reply, addr)
            stats.add('udp', '-', udp_out=1, bytes_out=len(reply))

    def stop(signum, frame):
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, stop)
    threading.Thread(target=udp, daemon=True).start()
    srv = ThreadingHTTPServer((a.bind, a.port), Handler)
    srv.daemon_threads = True
    print('peer: listening on %s:%d (tcp, udp), %d media file(s)' % (a.bind, a.port, len(media)), flush=True)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        if a.log:
            with stats.lock, open(a.log, 'w') as f:
                json.dump({'ended': time.strftime('%FT%TZ', time.gmtime()), 'run': a.run, 'budget': stats.budget,
                           'used': stats.used, 'runs': stats.runs}, f, indent=1, sort_keys=True)


if __name__ == '__main__':
    main()
