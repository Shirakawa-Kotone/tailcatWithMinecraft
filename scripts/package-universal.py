#!/usr/bin/env python3
"""Assemble exact-version universal and platform-specific offline mod JARs."""
import argparse
from dataclasses import dataclass
import hashlib
import io
import json
import lzma
from pathlib import Path
import re
import sys
import tarfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
# Derive ownership from the core sources so a new package-private utility cannot
# accidentally be placed in a child adapter, invisible to its parent core.
CORE = {path.stem for path in (ROOT / 'core/src/main/java/com/tailscale/mclink').glob('*.java')}
NATIVE = 'assets/mclink/native/'
INDEX = 'META-INF/tailcat/adapters.json'
TARGETS = ('darwin-amd64', 'darwin-arm64', 'linux-amd64', 'linux-arm64', 'windows-amd64', 'windows-arm64')
ENTRYPOINTS = {'fabric': 'TailcatFabric', 'forge': 'TailcatForge', 'neoforge': 'TailcatNeoForge'}
MAX_UNIVERSAL = 50_000_000

@dataclass(frozen=True)
class Artifact:
    id: str
    loader: str
    minecraft: str
    java: int
    path: Path


def version():
    return re.search(r'^mod_version=(.+)$', (ROOT / 'gradle.properties').read_text(), re.M).group(1).strip()


def catalog():
    result = []
    for script in sorted((ROOT / 'platforms').glob('*/build.gradle')):
        text = script.read_text()
        def field(name):
            match = re.search(r'ext\.' + name + r'\s*=\s*[\'\"]?([^\'\"\s;]+)', text)
            return match.group(1) if match else None
        loader, mc, java = field('loaderName'), field('minecraftVersion'), field('javaVersion')
        if loader in ENTRYPOINTS and mc and java:
            system = (script.parent / '.standalone-build')
            result.append({'id': script.parent.name, 'loader': loader, 'minecraft': mc, 'java': int(java),
                           'buildSystem': system.read_text().strip() if system.exists() else 'root'})
    return result


def current_artifacts(targets=None):
    result = []
    for item in catalog():
        if targets and item['id'] not in targets:
            continue
        path = ROOT / 'platforms' / item['id'] / 'build/libs' / f"tailcat-{item['minecraft']}-{item['loader']}-{version()}.jar"
        if not path.is_file():
            raise ValueError('Missing verified current-version artifact: ' + str(path))
        result.append(Artifact(item['id'], item['loader'], item['minecraft'], item['java'], path))
    if not result:
        raise ValueError('No adapter artifacts selected')
    if targets and set(targets) != {item.id for item in result}:
        raise ValueError('Unknown target in selection')
    return result


def is_core(name):
    return name.startswith('com/tailscale/mclink/') and '/' not in name[len('com/tailscale/mclink/'):] \
        and name.endswith('.class') and name.rsplit('/', 1)[1].split('$')[0].split('.')[0] in CORE


def shared_entry(name):
    return is_core(name) or name.startswith(('org/tukaani/xz/', 'assets/mclink/native/',
                                            'assets/mclink/lang/', 'META-INF/licenses/')) \
        or name in ('META-INF/THIRD-PARTY-NOTICES.txt', 'LICENSE')


def jar_bytes(entries):
    output = io.BytesIO()
    with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as jar:
        for name, data in sorted(entries.items()):
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_STORED if name.endswith('.xz') else zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            jar.writestr(info, data)
    return output.getvalue()


def class_major(data):
    if len(data) < 8 or data[:4] != b'\xca\xfe\xba\xbe':
        raise ValueError('Invalid compiled class')
    return int.from_bytes(data[6:8], 'big')


def loader_metadata(loader, adapters, mod_version):
    versions = list(dict.fromkeys(value for item in adapters if item['loader'] == loader for value in item['minecraft']))
    ranges = ','.join('[' + value + ']' for value in versions)
    if loader == 'fabric':
        return json.dumps({'schemaVersion': 1, 'id': 'tailcat', 'version': mod_version,
                           'name': 'Tailcat Minecraft', 'description': 'Offline bundled Tailcat world sharing.',
                           'environment': 'client', 'license': 'BSD-3-Clause',
                           'entrypoints': {'client': ['com.tailscale.mclink.bootstrap.FabricBootstrap']},
                           'depends': {'fabricloader': '>=0.14.0', 'minecraft': versions, 'java': '>=8',
                                       'fabric-api-base': '*', 'fabric-lifecycle-events-v1': '*',
                                       'fabric-networking-api-v1': '*', 'fabric-screen-api-v1': '*'}},
                          ensure_ascii=False, indent=2).encode()
    # Metadata lists exact verified versions; bootstrap selection is exact too.
    # Loader-major language versions differ across MC releases, so allow all
    # historical language-provider versions and let the game/loader enforce itself.
    dependency = 'forge' if loader == 'forge' else 'neoforge'
    return (f'modLoader="javafml"\nloaderVersion="[1,)"\nlicense="BSD-3-Clause"\n'
            f'[[mods]]\nmodId="tailcat"\nversion="{mod_version}"\ndisplayName="Tailcat Minecraft"\n'
            'displayTest="IGNORE_ALL_VERSION"\ndescription="Bundled Tailcat world sharing"\n'
            f'[[dependencies.tailcat]]\nmodId="minecraft"\nmandatory=true\ntype="required"\n'
            f'versionRange="{ranges}"\nordering="NONE"\nside="CLIENT"\n').encode()


def build_universal(artifacts, bootstrap, output, mod_version, core_jar=None):
    shared, adapters, seen = {}, [], set()
    canonical = {}
    if core_jar is not None:
        with zipfile.ZipFile(core_jar) as jar:
            canonical = {name: jar.read(name) for name in jar.namelist() if is_core(name)}
        if not canonical:
            raise ValueError('Canonical core archive is empty')
        for name, data in canonical.items():
            if class_major(data) > 52:
                raise ValueError('Shared core must run on Java 8: ' + name)
        shared.update(canonical)
    for artifact in artifacts:
        key = (artifact.loader, artifact.minecraft)
        if key in seen:
            raise ValueError('Duplicate loader/version adapter: ' + str(key))
        seen.add(key)
        adapter_classes = {}
        with zipfile.ZipFile(artifact.path) as jar:
            for item in jar.infolist():
                name = item.filename
                if item.is_dir():
                    continue
                if shared_entry(name):
                    data = jar.read(name)
                    if is_core(name) and class_major(data) > 52:
                        raise ValueError('Shared core must run on Java 8: ' + name)
                    if is_core(name) and canonical:
                        if name not in canonical:
                            raise ValueError('Class missing from canonical core: ' + name)
                        continue
                    # Loom and Forge remappers reorder constant pools even in
                    # dependency classes with no Minecraft references. Keep one
                    # complete copy of the pinned pure-Java decoder.
                    if name.startswith('org/tukaani/xz/') and name in shared:
                        continue
                    if name in shared and shared[name] != data:
                        raise ValueError('Mismatched shared entry: ' + name)
                    shared[name] = data
                elif name.startswith('com/tailscale/mclink/') and name.endswith('.class'):
                    adapter_classes[name] = jar.read(name)
        entrypoint = 'com.tailscale.mclink.' + ENTRYPOINTS[artifact.loader]
        if entrypoint.replace('.', '/') + '.class' not in adapter_classes:
            raise ValueError('Missing adapter entrypoint: ' + artifact.id)
        nested = jar_bytes(adapter_classes)
        resource = 'META-INF/tailcat/adapters/' + artifact.id + '.jar'
        shared[resource] = nested
        adapters.append({'id': artifact.id, 'loader': artifact.loader, 'minecraft': [artifact.minecraft],
                         'entrypoint': entrypoint, 'resource': resource,
                         'sha256': hashlib.sha256(nested).hexdigest(), 'java': artifact.java})
    if not adapters:
        raise ValueError('No adapters supplied')
    with zipfile.ZipFile(bootstrap) as jar:
        for name in jar.namelist():
            if name.startswith('com/tailscale/mclink/bootstrap/') and name.endswith('.class'):
                data = jar.read(name)
                if class_major(data) > 52:
                    raise ValueError('Universal bootstrap must run on Java 8: ' + name)
                shared[name] = data
    for cls in ('FabricBootstrap', 'ForgeBootstrap', 'NeoForgeBootstrap', 'BootstrapRuntime'):
        if 'com/tailscale/mclink/bootstrap/' + cls + '.class' not in shared:
            raise ValueError('Missing universal bootstrap class: ' + cls)
    for loader, path in (('fabric', 'fabric.mod.json'), ('forge', 'META-INF/mods.toml'), ('neoforge', 'META-INF/neoforge.mods.toml')):
        shared[path] = loader_metadata(loader, adapters, mod_version)
    shared[INDEX] = json.dumps({'format': 1, 'modVersion': mod_version, 'adapters': adapters}, indent=2).encode()
    shared['tailcat-build.properties'] = f'version={mod_version}\nloader=universal\n'.encode()
    shared['META-INF/MANIFEST.MF'] = f'Manifest-Version: 1.0\nFMLModType: MOD\nImplementation-Version: {mod_version}\n\n'.encode()
    shared['META-INF/tailcat/native-profile.json'] = json.dumps({'targets': list(TARGETS), 'offline': True}).encode()
    data = jar_bytes(shared)
    if len(data) > MAX_UNIVERSAL:
        raise ValueError(f'Universal package exceeds 50MB: {len(data)} bytes')
    output = Path(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(data)
    return output


def build_slim_profiles(universal, directory):
    with zipfile.ZipFile(universal) as jar:
        entries = {name: jar.read(name) for name in jar.namelist() if not name.endswith('/')}
    checksums = json.loads(entries[NATIVE + 'checksums.json'])
    paths = []
    with tarfile.open(fileobj=io.BytesIO(entries[NATIVE + 'helpers.tar.xz']), mode='r:xz') as source:
        for member in source:
            if not member.isfile():
                continue
            target = member.name.split('/')[0]
            if target not in TARGETS:
                raise ValueError('Unexpected native target: ' + target)
            data = source.extractfile(member).read()
            if hashlib.sha256(data).hexdigest() != checksums.get(member.name):
                raise ValueError('Native source checksum mismatch')
            raw = io.BytesIO()
            with tarfile.open(fileobj=raw, mode='w', format=tarfile.USTAR_FORMAT) as single:
                info = tarfile.TarInfo(member.name)
                info.size, info.mode, info.mtime = len(data), 0o755, 0
                single.addfile(info, io.BytesIO(data))
            profile = dict(entries)
            profile[NATIVE + 'helpers.tar.xz'] = lzma.compress(raw.getvalue(), preset=9)
            profile[NATIVE + 'checksums.json'] = json.dumps({member.name: checksums[member.name]}, indent=2).encode()
            profile['META-INF/tailcat/native-profile.json'] = json.dumps({'targets': [target], 'offline': True}).encode()
            output = Path(directory) / (Path(universal).stem + '-' + target + '.jar')
            output.write_bytes(jar_bytes(profile))
            if output.stat().st_size >= 10_000_000:
                raise ValueError(f'Slim profile exceeds 10MB preference: {output}')
            paths.append(output)
    if len(paths) != len(TARGETS):
        raise ValueError('Incomplete platform profiles')
    return paths


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--targets', help='comma-separated exact target ids; default all configured variants')
    parser.add_argument('--output', type=Path, default=ROOT / 'dist')
    parser.add_argument('--no-slim', action='store_true')
    parser.add_argument('--catalog', action='store_true')
    args = parser.parse_args()
    if args.catalog:
        print(json.dumps(catalog(), indent=2))
        return 0
    bootstrap = ROOT / 'universal/build/libs' / f'tailcat-universal-bootstrap-{version()}.jar'
    output = args.output / f'tailcat-universal-{version()}.jar'
    artifacts = current_artifacts(args.targets.split(',') if args.targets else None)
    core = ROOT / 'core/build/libs' / f'core-{version()}.jar'
    build_universal(artifacts, bootstrap, output, version(), core_jar=core)
    generated = [output] + ([] if args.no_slim else build_slim_profiles(output, args.output))
    report = {'version': version(), 'adapters': [{'id': a.id, 'minecraft': a.minecraft, 'loader': a.loader, 'java': a.java} for a in artifacts],
              'artifacts': [{'file': p.name, 'bytes': p.stat().st_size, 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()} for p in generated]}
    (args.output / 'build-matrix.json').write_text(json.dumps(report, indent=2) + '\n')
    for item in report['artifacts']:
        print(item['file'], item['bytes'], 'bytes')
    return 0


if __name__ == '__main__':
    sys.exit(main())
