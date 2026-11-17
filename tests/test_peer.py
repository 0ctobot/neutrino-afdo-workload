#!/usr/bin/env python3
"""Host tests of peer/peer.py on loopback (a throwaway APK with one clip)."""
import http.client, json, os, socket, subprocess, sys, tempfile, threading, time, unittest, zipfile

PEER = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'peer', 'peer.py')
TOKEN = 'TestToken123'


def free_port():
    s = socket.socket()
    s.bind(('127.0.0.1', 0))
    p = s.getsockname()[1]
    s.close()
    return p


class Peer:
    def __init__(self, budget, run='run1'):
        self.dir = tempfile.mkdtemp(prefix='peer-test-')
        self.apk = os.path.join(self.dir, 'w.apk')
        with zipfile.ZipFile(self.apk, 'w', zipfile.ZIP_STORED) as z:
            z.writestr('assets/media/clip.mp4', bytes(range(256)) * (12 << 10))     # 3 MiB
        self.port = free_port()
        self.log = os.path.join(self.dir, 'peer.json')
        args = [sys.executable, '-I', PEER, '--bind', '127.0.0.1', '--port', str(self.port), '--token', TOKEN,
                '--apk', self.apk, '--budget', str(budget), '--log', self.log]
        if run:
            args += ['--run', run]
        self.p = subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        assert 'listening' in self.p.stdout.readline()

    def get(self, path, run='run1', key='k.1', token=TOKEN, method='GET', headers=None, body=None):
        c = http.client.HTTPConnection('127.0.0.1', self.port, timeout=10)
        h = {'X-Afdo-Token': token, 'X-Afdo-Run': run, 'X-Afdo-Key': key}
        h.update(headers or {})
        c.request(method, path, body=body, headers=h)
        r = c.getresponse()
        data = r.read()
        c.close()
        return r.status, data

    def stop(self):
        self.p.terminate()
        self.p.wait(timeout=10)
        self.p.stdout.close()
        self.p.stderr.close()
        with open(self.log) as f:
            return json.load(f)


class BudgetTests(unittest.TestCase):
    def test_rpc_is_charged(self):
        p = Peer(budget=1024)
        try:
            codes = [p.get('/rpc?id=%d&size=512' % i)[0] for i in range(4)]
        finally:
            log = p.stop()
        self.assertEqual(codes, [200, 200, 429, 429])           # two 512-byte bodies fill 1 KiB
        self.assertLessEqual(log['used'], 1024)
        self.assertEqual(log['runs']['run1']['bytes_out'], 1024)

    def test_concurrent_bulk_reserves(self):
        p = Peer(budget=1 << 20)
        codes = []
        try:
            ts = [threading.Thread(target=lambda: codes.append(p.get('/bulk?id=1&bytes=%d' % (400 << 10))[0])) for _ in range(4)]
            for t in ts:
                t.start()
            for t in ts:
                t.join()
        finally:
            log = p.stop()
        self.assertEqual(sorted(codes), [200, 200, 429, 429])
        self.assertLessEqual(log['used'], 1 << 20)

    def test_upload_and_ranges(self):
        p = Peer(budget=4 << 20)
        try:
            self.assertEqual(p.get('/media/clip.mp4', headers={'Range': 'bytes=0-%d' % ((2 << 20) - 1)})[0], 413)
            st, data = p.get('/media/clip.mp4', headers={'Range': 'bytes=0-1023'})
            self.assertEqual((st, len(data)), (206, 1024))
            st, data = p.get('/upload?id=1', method='POST', body=b'x' * 1000)
            self.assertEqual(st, 200)
            self.assertTrue(data.endswith(b' 1000'))
            st, _ = p.get('/media/clip.mp4', method='HEAD')
            self.assertEqual(st, 200)
        finally:
            log = p.stop()
        r = log['runs']['run1']
        self.assertEqual((r['bytes_in'], r['bytes_out'] >= 1024), (1000, True))

    def test_udp_is_charged(self):
        p = Peer(budget=600)
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.settimeout(0.5)
            got = 0
            for i in range(4):
                s.sendto(('%d:%s:' % (i, TOKEN)).encode() + b'p' * 100, ('127.0.0.1', p.port))
                try:
                    data, _ = s.recvfrom(2048)
                    self.assertEqual(data, ('%d:' % i).encode() + b'p' * 100)
                    got += 1
                except socket.timeout:
                    pass
        finally:
            s.close()
            log = p.stop()
        self.assertEqual(got, 2)                                   # each echo moves about 230 bytes
        self.assertLessEqual(log['used'], 600)


class AccessTests(unittest.TestCase):
    def test_run_token_and_attempts(self):
        p = Peer(budget=1 << 20)
        try:
            self.assertEqual(p.get('/rpc?id=1&size=16', run='other')[0], 403)
            self.assertEqual(p.get('/rpc?id=1&size=16', token='WrongToken1')[0], 403)
            for _ in range(3):
                self.assertEqual(p.get('/rpc?id=1&size=16', key='net-rpc.main.1.1')[0], 200)
            self.assertEqual(p.get('/bulk?id=5&bytes=1000', key='net-bulk.main.1.2')[0], 200)
            st, data = p.get('/stats?run=run1&key=net-rpc.main.1.1', key='net-rpc.main.1.1')
            a = json.loads(data)
            st2, data2 = p.get('/stats?run=run1&key=net-bulk.main.1.2', key='net-bulk.main.1.2')
            b = json.loads(data2)
        finally:
            p.stop()
        self.assertEqual((st, a['requests'], a['bulk_crc']), (200, 3, {}))
        self.assertEqual((st2, b['requests'], list(b['bulk_crc'])), (200, 1, ['5']))
        self.assertGreaterEqual(b['run']['requests'], 5)


if __name__ == '__main__':
    unittest.main(verbosity=1)
