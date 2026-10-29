#!/usr/bin/env python3
"""Verify ordinary, universal, and single-platform offline release JARs."""
import argparse
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import sys
import tarfile
import zipfile

TARGETS = ('windows-amd64', 'windows-arm64', 'darwin-amd64', 'darwin-arm64', 'linux-amd64', 'linux-arm64')
NATIVE = 'assets/mclink/native/'
INDEX = 'META-INF/tailcat/adapters.json'


def native_name(target):
    return target + '/mclink-helper' + ('.exe' if target.startswith('windows') else '')


def verify_universal(jar):
    errors, pairs = [], set()
    index = json.loads(jar.read(INDEX))
    if index.get('format') != 1 or not isinstance(index.get('adapters'), list) or not index['adapters']:
        return ['Invalid universal adapter index']
    if len(index['adapters']) > 512:
        return ['Too many universal adapters']
    for item in index['adapters']:
        resource = item['resource']
        if not resource.startswith('META-INF/tailcat/adapters/') or '..' in resource.split('/'):
            errors.append('Unsafe adapter resource path')
            continue
        data = jar.read(resource)
        if hashlib.sha256(data).hexdigest() != item['sha256']:
            errors.append('Adapter checksum mismatch: ' + item['id'])
        for minecraft in item['minecraft']:
            pair = (item['loader'], minecraft)
            if pair in pairs:
                errors.append('Duplicate loader/version selection: ' + str(pair))
            pairs.add(pair)
        with zipfile.ZipFile(io.BytesIO(data)) as nested:
            names = nested.namelist()
            if item['entrypoint'].replace('.', '/') + '.class' not in names:
                errors.append('Missing nested entrypoint: ' + item['id'])
            if any('/native/' in name or 'org/tukaani/' in name or name.endswith('/HelperProcess.class') for name in names):
                errors.append('Duplicated shared payload in adapter: ' + item['id'])
    for cls in ('FabricBootstrap', 'ForgeBootstrap', 'NeoForgeBootstrap', 'BootstrapRuntime'):
        if 'com/tailscale/mclink/bootstrap/' + cls + '.class' not in jar.namelist():
            errors.append('Missing universal bootstrap: ' + cls)
    for name in jar.namelist():
        if name.endswith('.class'):
            data = jar.read(name)
            if len(data) < 8 or data[:4] != b'\xca\xfe\xba\xbe' or int.from_bytes(data[6:8], 'big') > 52:
                errors.append('Outer class is not Java8 compatible: ' + name)
    for forbidden in ('net/fabricmc/api/ClientModInitializer.class', 'net/minecraftforge/fml/common/Mod.class', 'net/neoforged/fml/common/Mod.class'):
        if forbidden in jar.namelist():
            errors.append('Compile-only loader SDK declaration leaked: ' + forbidden)
    return errors


def verify_archive(path):
    errors = []
    try:
        with zipfile.ZipFile(path) as jar:
            names = set(jar.namelist())
            for cls in ('HelperProcess', 'NetworkStatus', 'StatusFormatter', 'SpeedTestResult'):
                entry = 'com/tailscale/mclink/' + cls + '.class'
                if entry not in names:
                    errors.append('Missing bundled core class: ' + entry)
            if 'org/tukaani/xz/XZInputStream.class' not in names:
                errors.append('Missing bundled LZMA decoder')
            if not {'fabric.mod.json', 'META-INF/mods.toml', 'META-INF/neoforge.mods.toml'} & names:
                errors.append('Missing loader metadata')
            if 'fabric.mod.json' in names:
                metadata = json.loads(jar.read('fabric.mod.json'))
                if metadata.get('id') != 'tailcat' or '${' in str(metadata.get('version', '')):
                    errors.append('Invalid or unexpanded Fabric mod metadata')
            if 'META-INF/licenses/mclink/LICENSE' not in names:
                errors.append('Missing upstream license')
            targets = TARGETS
            if 'META-INF/tailcat/native-profile.json' in names:
                profile = json.loads(jar.read('META-INF/tailcat/native-profile.json'))
                targets = profile.get('targets')
                if profile.get('offline') is not True or not isinstance(targets, list) or not targets \
                        or len(set(targets)) != len(targets) or not set(targets).issubset(TARGETS):
                    return errors + ['Invalid offline native profile']
            sums = json.loads(jar.read(NATIVE + 'checksums.json'))
            if not isinstance(sums, dict):
                return errors + ['Invalid native checksum manifest']
            archive = NATIVE + 'helpers.tar.xz'
            if archive not in names:
                return errors + ['Missing bundled native payload: helpers.tar.xz']
            members = {}
            with tarfile.open(fileobj=io.BytesIO(jar.read(archive)), mode='r|xz') as tar:
                for member in tar:
                    if not member.isfile():
                        continue
                    if member.name in members:
                        errors.append('Duplicate native archive entry: ' + member.name)
                    digest = hashlib.sha256()
                    with tar.extractfile(member) as source:
                        while chunk := source.read(65536): digest.update(chunk)
                    members[member.name] = digest.hexdigest()
            expected = {native_name(target) for target in targets}
            for relative in expected:
                if relative not in sums:
                    errors.append('Missing native checksum: ' + relative)
                if relative not in members:
                    errors.append('Missing bundled native: ' + relative)
                elif members[relative] != sums.get(relative):
                    errors.append('Native checksum mismatch: ' + relative)
            if set(members) - expected or set(sums) - expected:
                errors.append('Unexpected native payload outside declared profile')
            if INDEX in names:
                errors.extend(verify_universal(jar))
                size = Path(path).stat().st_size
                if size > 50_000_000:
                    errors.append('Universal package exceeds50MB')
                if len(targets) == 1 and size >= 10_000_000:
                    errors.append('Single-platform profile exceeds10MB')
    except (OSError, KeyError, TypeError, ValueError, tarfile.TarError, zipfile.BadZipFile) as failure:
        errors.append('Invalid archive: ' + str(failure))
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('jars', type=Path, nargs='*')
    args = parser.parse_args()
    archives = args.jars
    if not archives:
        root = Path(__file__).resolve().parents[1]
        spec = importlib.util.spec_from_file_location('package_universal_verify', root / 'scripts/package-universal.py')
        module = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = module
        spec.loader.exec_module(module)
        archives = sorted((root / 'dist').glob(f'tailcat-universal-{module.version()}*.jar'))
        if not archives:
            try: archives = [item.path for item in module.current_artifacts()]
            except ValueError as failure:
                print(str(failure), file=sys.stderr)
                return 1
    failed = False
    for path in archives:
        errors = verify_archive(path)
        failed |= bool(errors)
        print(('FAIL ' if errors else 'PASS ') + str(path))
        for error in errors: print('  ' + error, file=sys.stderr)
    return int(failed)


if __name__ == '__main__':
    sys.exit(main())
