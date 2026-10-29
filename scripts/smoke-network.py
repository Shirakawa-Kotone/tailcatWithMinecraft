#!/usr/bin/env python3
"""Smoke-test real bundled helpers with two visitors and tiny TCP echo traffic.

This contacts Tailcat's bootstrap/DERP infrastructure. It performs no bandwidth
test and does not validate Minecraft account authentication or GUI integration.
"""
import argparse
import hashlib
import json
from pathlib import Path
import platform
import queue
import socket
import subprocess
import threading
import time
import tarfile
import tempfile


class Helper:
    def __init__(self, executable, *arguments):
        self.process = subprocess.Popen([str(executable), *arguments], stdin=subprocess.PIPE,
                                        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
        self.events = queue.Queue()
        threading.Thread(target=self.read, daemon=True).start()

    def read(self):
        try:
            for line in self.process.stdout:
                self.events.put(json.loads(line))
        finally:
            self.events.put({'type': 'eof'})

    def wait(self, kind, predicate=lambda event: True, timeout=35):
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            event = self.events.get(timeout=max(.1, end - time.monotonic()))
            if event.get('type') in ('error', 'eof'):
                raise RuntimeError('Helper failed: ' + event.get('code', event['type']))
            if event.get('type') == kind and predicate(event):
                return event
        raise TimeoutError('Waiting for helper ' + kind)

    def close(self):
        if self.process.stdin and not self.process.stdin.closed:
            self.process.stdin.close()
        try:
            self.process.wait(timeout=8)
        except subprocess.TimeoutExpired:
            self.process.kill()
            self.process.wait()
            raise RuntimeError('Helper did not stop after stdin EOF')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--allow-network', action='store_true', help='enable this optional network test')
    parser.add_argument('--helper', type=Path)
    args = parser.parse_args()
    if not args.allow_network:
        parser.error('This optional test contacts Tailcat relays; pass --allow-network to run it')
    system = {'Darwin': 'darwin', 'Linux': 'linux', 'Windows': 'windows'}[platform.system()]
    arch = {'arm64': 'arm64', 'aarch64': 'arm64', 'x86_64': 'amd64', 'AMD64': 'amd64'}[platform.machine()]
    name = 'mclink-helper.exe' if system == 'windows' else 'mclink-helper'
    temporary = tempfile.TemporaryDirectory(prefix='tailcat-network-')
    executable = args.helper
    if executable is None:
        native = Path(__file__).resolve().parents[1] / 'native/assets/mclink/native'
        member = f'{system}-{arch}/{name}'
        checksums = json.loads((native / 'checksums.json').read_text())
        with tarfile.open(native / 'helpers.tar.xz', mode='r:xz') as archive:
            data = archive.extractfile(member).read()
        if hashlib.sha256(data).hexdigest() != checksums[member]:
            raise RuntimeError('Bundled helper checksum mismatch')
        executable = Path(temporary.name) / name
        executable.write_bytes(data)
        executable.chmod(0o700)
    helpers, sockets = [], []
    backend = socket.socket()
    backend.bind(('127.0.0.1', 0))
    backend.listen()
    backend.settimeout(1)
    stopped = threading.Event()

    def echo(connection):
        with connection:
            while data := connection.recv(1024):
                connection.sendall(data)

    def serve():
        while not stopped.is_set():
            try:
                connection, _ = backend.accept()
                threading.Thread(target=echo, args=(connection,), daemon=True).start()
            except socket.timeout:
                continue
            except OSError:
                return

    threading.Thread(target=serve, daemon=True).start()
    try:
        host = Helper(executable, 'host', '--target', f'127.0.0.1:{backend.getsockname()[1]}')
        helpers.append(host)
        invitation = host.wait('ready')['invite']
        for number in range(2):
            visitor = Helper(executable, 'join', '--invite', invitation)
            helpers.append(visitor)
            endpoint = visitor.wait('ready')['address']
            local_host, port = endpoint.rsplit(':', 1)
            stream = socket.create_connection((local_host, int(port)), timeout=20)
            sockets.append(stream)
            payload = f'tailcat-smoke-{number}'.encode()
            stream.sendall(payload)
            received = bytearray()
            while len(received) < len(payload):
                data = stream.recv(len(payload) - len(received))
                if not data:
                    raise RuntimeError('Echo stream closed prematurely')
                received.extend(data)
            assert received == payload, 'Echo payload mismatch'
            # An already queued sample can see the open stream before the echo.
            # Wait for the asynchronous sampler to observe the actual payload.
            stats = visitor.wait('stats', lambda event: event['stats']['activeConnections'] >= 1
                                 and event['stats']['txBytes'] >= len(payload)
                                 and event['stats']['rxBytes'] >= len(payload))['stats']
            assert stats['txBytes'] >= len(payload) and stats['rxBytes'] >= len(payload)
            print(f"Visitor {number + 1}: echo OK, observed path={stats['path']}")
        stats = host.wait('stats', lambda event: event['stats']['activeConnections'] >= 2)['stats']
        print(f"Host: {stats['activeConnections']} active streams, observed path={stats['path']}")
        # Stop the host with both game sockets still open: shutdown must close them.
        host.close()
        helpers.remove(host)
        print('Host EOF shutdown OK with two active visitors')
    finally:
        for stream in sockets:
            stream.close()
        for helper in reversed(helpers):
            helper.close()
        stopped.set()
        backend.close()
        temporary.cleanup()


if __name__ == '__main__':
    main()
