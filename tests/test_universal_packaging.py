import hashlib
import importlib.util
import io
import json
from pathlib import Path
import struct
import sys
import tempfile
import unittest
import zipfile

script = Path(__file__).parents[1] / 'scripts/package-universal.py'
spec = importlib.util.spec_from_file_location('package_universal', script)
pack = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = pack
spec.loader.exec_module(pack)


def klass(major):
    return b'\xca\xfe\xba\xbe\x00\x00' + struct.pack('>H', major) + b'fixture'


class UniversalPackagingTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.bootstrap = self.root / 'bootstrap.jar'
        with zipfile.ZipFile(self.bootstrap, 'w') as z:
            for name in ('FabricBootstrap', 'ForgeBootstrap', 'NeoForgeBootstrap', 'BootstrapRuntime'):
                z.writestr('com/tailscale/mclink/bootstrap/' + name + '.class', klass(52))
            z.writestr('net/fabricmc/api/ClientModInitializer.class', klass(52))
        self.output = self.root / 'universal.jar'

    def artifact(self, loader, mc, java=17, native=b'shared-native'):
        path = self.root / (loader + mc + '.jar')
        entry = {'fabric': 'TailcatFabric', 'forge': 'TailcatForge', 'neoforge': 'TailcatNeoForge'}[loader]
        with zipfile.ZipFile(path, 'w') as z:
            z.writestr('com/tailscale/mclink/' + entry + '.class', klass(java + 44))
            z.writestr('com/tailscale/mclink/ScreenState.class', klass(java + 44))
            for cls in ('HelperProcess', 'NetworkStatus', 'StatusFormatter', 'SpeedTestResult', 'NativeHelper', 'Compatibility'):
                z.writestr('com/tailscale/mclink/' + cls + '.class', klass(52))
            z.writestr('org/tukaani/xz/XZInputStream.class', klass(52))
            z.writestr('assets/mclink/native/helpers.tar.xz', native)
            z.writestr('assets/mclink/native/checksums.json', '{}')
            z.writestr('META-INF/licenses/mclink/LICENSE', 'BSD fixture')
        return pack.Artifact(loader + mc.replace('.', ''), loader, mc, java, path)

    def test_one_shared_payload_and_isolated_hashed_adapters(self):
        artifacts = [self.artifact('fabric', '1.16.5', 8), self.artifact('forge', '26.3', 25)]
        pack.build_universal(artifacts, self.bootstrap, self.output, '0.3.0')
        with zipfile.ZipFile(self.output) as z:
            self.assertEqual(1, z.namelist().count('assets/mclink/native/helpers.tar.xz'))
            self.assertNotIn('com/tailscale/mclink/ScreenState.class', z.namelist())
            self.assertNotIn('net/fabricmc/api/ClientModInitializer.class', z.namelist())
            index = json.loads(z.read('META-INF/tailcat/adapters.json'))
            self.assertEqual(['1.16.5'], index['adapters'][0]['minecraft'])
            self.assertEqual(2, len(index['adapters']))
            for adapter in index['adapters']:
                data = z.read(adapter['resource'])
                self.assertEqual(hashlib.sha256(data).hexdigest(), adapter['sha256'])
                with zipfile.ZipFile(io.BytesIO(data)) as nested:
                    self.assertIn('com/tailscale/mclink/ScreenState.class', nested.namelist())
                    self.assertNotIn('com/tailscale/mclink/HelperProcess.class', nested.namelist())
                    self.assertFalse(any('/native/' in name for name in nested.namelist()))
            fabric = json.loads(z.read('fabric.mod.json'))
            self.assertIn('com/tailscale/mclink/Compatibility.class', z.namelist())
            self.assertNotIn('fabric-api', fabric['depends'])
            for module in ('fabric-api-base', 'fabric-lifecycle-events-v1', 'fabric-networking-api-v1', 'fabric-screen-api-v1'):
                self.assertIn(module, fabric['depends'])
            self.assertEqual(['1.16.5'], fabric['depends']['minecraft'])
            self.assertIn('META-INF/mods.toml', z.namelist())
            self.assertIn('META-INF/neoforge.mods.toml', z.namelist())

    def test_refuses_mixed_native_payloads(self):
        artifacts = [self.artifact('fabric', '1.20.1'), self.artifact('forge', '1.20.1', native=b'wrong')]
        with self.assertRaisesRegex(ValueError, 'shared'):
            pack.build_universal(artifacts, self.bootstrap, self.output, '0.3.0')

    def test_uses_canonical_core_when_remappers_rewrite_constant_pools(self):
        first = self.artifact('fabric', '1.16.5', 8)
        second = self.artifact('forge', '26.3', 25)
        canonical = self.root / 'core.jar'
        with zipfile.ZipFile(first.path) as source, zipfile.ZipFile(canonical, 'w') as target:
            for name in source.namelist():
                if pack.is_core(name): target.writestr(name, source.read(name) + b'canonical')
        pack.build_universal([first, second], self.bootstrap, self.output, '0.3.0', core_jar=canonical)
        with zipfile.ZipFile(self.output) as jar:
            self.assertTrue(jar.read('com/tailscale/mclink/Compatibility.class').endswith(b'canonical'))

    def test_refuses_duplicate_loader_version_pair(self):
        artifact = self.artifact('fabric', '1.20.1')
        with self.assertRaisesRegex(ValueError, 'Duplicate'):
            pack.build_universal([artifact, artifact], self.bootstrap, self.output, '0.3.0')

    def base_package(self, artifacts=None):
        base = self.root / 'base.jar'
        pack.build_universal(artifacts or [self.artifact('fabric', '1.20.1')],
                             self.bootstrap, base, '0.3.1')
        return base, hashlib.sha256(base.read_bytes()).hexdigest()

    def replace_entry(self, path, name, data):
        with zipfile.ZipFile(path) as jar:
            entries = {item: jar.read(item) for item in jar.namelist()}
        if data is None:
            del entries[name]
        else:
            entries[name] = data
        path.write_bytes(pack.jar_bytes(entries))

    def test_incremental_refuses_wrong_or_missing_base_hash(self):
        base, digest = self.base_package()
        artifact = self.artifact('fabric', '1.21.6', 21)
        for supplied in ('0' * 64, None):
            with self.subTest(hash=supplied), self.assertRaisesRegex(ValueError, 'SHA-256'):
                pack.build_universal([artifact], self.bootstrap, self.output, '0.3.2',
                                     base_universal=base, base_sha256=supplied)
        self.assertFalse(self.output.exists())

    def test_incremental_preserves_old_adapters_and_shared_resources(self):
        base, digest = self.base_package([self.artifact('fabric', '1.20.1'),
                                         self.artifact('forge', '1.20.1')])
        artifact = self.artifact('fabric', '1.21.6', 21)
        # Remapping may rewrite the decoder, but incremental output keeps the
        # verified decoder called by the identical base core.
        self.replace_entry(artifact.path, 'org/tukaani/xz/XZInputStream.class', klass(52) + b'remapped')
        pack.build_universal([artifact], self.bootstrap, self.output, '0.3.2',
                             base_universal=base, base_sha256=digest)
        with zipfile.ZipFile(base) as old, zipfile.ZipFile(self.output) as new:
            old_index = json.loads(old.read(pack.INDEX))
            index = json.loads(new.read(pack.INDEX))
            self.assertEqual(3, len(index['adapters']))
            self.assertEqual('0.3.2', index['modVersion'])
            self.assertEqual({'file': 'base.jar', 'sha256': digest, 'modVersion': '0.3.1',
                              'adapters': 2}, index['baseUniversal'])
            self.assertEqual(old_index['adapters'], index['adapters'][:2])
            for adapter in old_index['adapters']:
                self.assertEqual(old.read(adapter['resource']), new.read(adapter['resource']))
            for name in old.namelist():
                if pack.shared_entry(name):
                    self.assertEqual(old.read(name), new.read(name), name)
            fabric = json.loads(new.read('fabric.mod.json'))
            self.assertEqual(['1.20.1', '1.21.6'], fabric['depends']['minecraft'])
            self.assertEqual('0.3.2', fabric['version'])
            self.assertEqual(b'version=0.3.2\nloader=universal\n', new.read('tailcat-build.properties'))
            self.assertEqual(list(pack.TARGETS), json.loads(new.read('META-INF/tailcat/native-profile.json'))['targets'])

    def test_incremental_refuses_duplicate_pair_in_base_and_new_adapter(self):
        base, digest = self.base_package()
        artifact = self.artifact('fabric', '1.20.1')
        with self.assertRaisesRegex(ValueError, 'Duplicate loader/version'):
            pack.build_universal([artifact], self.bootstrap, self.output, '0.3.2',
                                 base_universal=base, base_sha256=digest)

    def test_incremental_refuses_incompatible_shared_resources(self):
        base_artifact = self.artifact('fabric', '1.20.1')
        self.replace_entry(base_artifact.path, 'assets/mclink/lang/en_us.json', b'{}')
        base, digest = self.base_package([base_artifact])
        for name, data in (
                ('com/tailscale/mclink/Compatibility.class', klass(52) + b'changed'),
                ('assets/mclink/native/helpers.tar.xz', b'changed'),
                ('assets/mclink/lang/en_us.json', b'{"changed":true}'),
                ('META-INF/licenses/mclink/LICENSE', b'changed'),
                ('org/tukaani/xz/Unexpected.class', klass(52))):
            artifact = self.artifact('fabric', '1.21.6', 21)
            self.replace_entry(artifact.path, 'assets/mclink/lang/en_us.json', b'{}')
            self.replace_entry(artifact.path, name, data)
            with self.subTest(entry=name), self.assertRaisesRegex(ValueError, 'shared|codec'):
                pack.build_universal([artifact], self.bootstrap, self.output, '0.3.2',
                                     base_universal=base, base_sha256=digest)

    def test_incremental_refuses_changed_canonical_core(self):
        base, digest = self.base_package()
        canonical = self.root / 'core.jar'
        with zipfile.ZipFile(base) as jar:
            entries = {name: jar.read(name) for name in jar.namelist() if pack.is_core(name)}
        entries['com/tailscale/mclink/Compatibility.class'] += b'changed'
        canonical.write_bytes(pack.jar_bytes(entries))
        with self.assertRaisesRegex(ValueError, 'shared core'):
            pack.build_universal([self.artifact('fabric', '1.21.6', 21)], self.bootstrap,
                                 self.output, '0.3.2', core_jar=canonical,
                                 base_universal=base, base_sha256=digest)

    def test_incremental_refuses_corrupted_base_nested_adapter(self):
        base, _ = self.base_package()
        self.replace_entry(base, 'META-INF/tailcat/adapters/fabric1201.jar', b'corrupt')
        digest = hashlib.sha256(base.read_bytes()).hexdigest()
        with self.assertRaisesRegex(ValueError, 'adapter.*SHA-256'):
            pack.build_universal([self.artifact('fabric', '1.21.6', 21)], self.bootstrap,
                                 self.output, '0.3.2', base_universal=base, base_sha256=digest)

    def test_refuses_core_that_cannot_run_on_java8(self):
        artifact = self.artifact('fabric', '1.20.1')
        with zipfile.ZipFile(artifact.path, 'a') as z:
            z.writestr('com/tailscale/mclink/Platform.class', klass(61))
        with self.assertRaisesRegex(ValueError, 'Java 8'):
            pack.build_universal([artifact], self.bootstrap, self.output, '0.3.0')


if __name__ == '__main__':
    unittest.main()
