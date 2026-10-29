import hashlib
import importlib.util
import io
import json
import lzma
from pathlib import Path
import tarfile
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('verify_artifacts', Path(__file__).parents[1] / 'scripts/verify-artifacts.py')
verify = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verify)


def payload(members):
    raw = io.BytesIO()
    with tarfile.open(fileobj=raw, mode='w', format=tarfile.USTAR_FORMAT) as tar:
        for name, data in members.items():
            info = tarfile.TarInfo(name)
            info.size = len(data)
            tar.addfile(info, io.BytesIO(data))
    return lzma.compress(raw.getvalue(), format=lzma.FORMAT_XZ)


class ArtifactVerificationTest(unittest.TestCase):
    def archive(self, *, corrupt=False, missing=False):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        path = Path(directory.name) / 'tailcat.jar'
        targets = ['windows-amd64', 'windows-arm64', 'darwin-amd64', 'darwin-arm64', 'linux-amd64', 'linux-arm64']
        prefix = 'assets/mclink/native/'
        sums, members = {}, {}
        with zipfile.ZipFile(path, 'w') as jar:
            jar.writestr('fabric.mod.json', json.dumps({'id': 'tailcat', 'version': '0.2.0', 'environment': 'client'}))
            for cls in ['HelperProcess', 'NetworkStatus', 'StatusFormatter', 'SpeedTestResult']:
                jar.writestr('com/tailscale/mclink/' + cls + '.class', b'fixture')
            jar.writestr('org/tukaani/xz/XZInputStream.class', b'fixture')
            jar.writestr('META-INF/licenses/mclink/LICENSE', 'BSD-3-Clause fixture')
            for target in targets:
                name = target + '/mclink-helper' + ('.exe' if target.startswith('windows') else '')
                data = ('fixture:' + target).encode()
                if corrupt and target == 'darwin-arm64':
                    data = b'corrupt'
                sums[name] = hashlib.sha256(('fixture:' + target).encode()).hexdigest()
                if missing and target == 'linux-arm64':
                    continue
                members[name] = data
            jar.writestr(prefix + 'helpers.tar.xz', payload(members))
            jar.writestr(prefix + 'checksums.json', json.dumps(sums))
        return path

    def test_reports_corrupt_native_payload(self):
        errors = verify.verify_archive(self.archive(corrupt=True))
        self.assertTrue(any('checksum' in e.lower() for e in errors), errors)

    def test_requires_every_supported_native_target(self):
        errors = verify.verify_archive(self.archive(missing=True))
        self.assertTrue(any('linux-arm64' in e for e in errors), errors)

    def test_accepts_intact_archive(self):
        self.assertEqual([], verify.verify_archive(self.archive()))

    def test_corrupt_zip_is_reported(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'broken.jar'
            path.write_text('not a jar')
            self.assertTrue(verify.verify_archive(path))

    def test_single_platform_offline_profile_is_valid(self):
        original = self.archive()
        with zipfile.ZipFile(original) as jar:
            entries = {name: jar.read(name) for name in jar.namelist()}
        name = 'darwin-arm64/mclink-helper'
        raw = b'fixture:darwin-arm64'
        entries['assets/mclink/native/helpers.tar.xz'] = payload({name: raw})
        entries['assets/mclink/native/checksums.json'] = json.dumps({name: hashlib.sha256(raw).hexdigest()}).encode()
        entries['META-INF/tailcat/native-profile.json'] = json.dumps({'targets': ['darwin-arm64'], 'offline': True}).encode()
        slim = original.with_name('slim.jar')
        with zipfile.ZipFile(slim, 'w') as jar:
            for path, data in entries.items(): jar.writestr(path, data)
        self.assertEqual([], verify.verify_archive(slim))


if __name__ == '__main__':
    unittest.main()
