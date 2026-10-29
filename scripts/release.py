#!/usr/bin/env python3
"""Build the complete loader matrix and export verified offline universal JARs."""
import argparse
import hashlib
import importlib.util
import json
import os
import re
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
HOME = ROOT / '.gradle-user-home'
GRADLE8_SHA256 = '31c55713e40233a8303827ceb42ca48a47267a0ad4bab9177123121e71524c26'


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'scripts' / filename)
    result = importlib.util.module_from_spec(spec)
    sys.modules[name] = result
    spec.loader.exec_module(result)
    return result


def jdk(major):
    values = [os.environ.get('TC_JAVA%d_HOME' % major), os.environ.get('JAVA_HOME_%d_X64' % major)]
    if major == 25: values.append(os.environ.get('JAVA_HOME'))
    values += [str(p / 'Contents/Home') for p in Path('/Library/Java/JavaVirtualMachines').glob('*%d*.jdk' % major)]
    for value in values:
        if value and (Path(value) / 'bin/java').is_file():
            release = Path(value) / 'release'
            if not release.is_file(): continue
            match = re.search(r'^JAVA_VERSION="(.*?)"', release.read_text(), re.M)
            if not match: continue
            number = match[1].split('.')
            actual = int(number[1] if number[0] == '1' else number[0].split('-')[0])
            if actual == major: return value
    raise RuntimeError('Set TC_JAVA%d_HOME to a JDK %d installation' % (major, major))


def gradle8(download=True):
    explicit = os.environ.get('TC_GRADLE8')
    if explicit: return explicit
    for parent in (HOME / 'wrapper/dists/gradle-8.10.2-bin', HOME / 'distributions'):
        matches = list(parent.glob('**/gradle-8.10.2/bin/gradle'))
        if matches: return str(matches[0])
    if not download: return None
    destination = HOME / 'distributions'
    destination.mkdir(parents=True, exist_ok=True)
    archive = destination / 'gradle-8.10.2-bin.zip'
    urllib.request.urlretrieve('https://services.gradle.org/distributions/gradle-8.10.2-bin.zip', archive)
    if hashlib.sha256(archive.read_bytes()).hexdigest() != GRADLE8_SHA256:
        archive.unlink()
        raise RuntimeError('Gradle 8 distribution checksum mismatch')
    with zipfile.ZipFile(archive) as package: package.extractall(destination)
    executable = destination / 'gradle-8.10.2/bin/gradle'
    executable.chmod(0o755)
    archive.unlink()
    return str(executable)


def run(command, log, env):
    print('Running:', ' '.join(map(str, command)), flush=True)
    with log.open('w') as output:
        result = subprocess.run(list(map(str, command)), cwd=ROOT, env=env, stdout=output, stderr=subprocess.STDOUT)
    if result.returncode:
        print(log.read_text(errors='replace')[-10000:], file=sys.stderr)
        raise RuntimeError('Build failed; log: ' + str(log))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--no-build', action='store_true', help='export already compiled current-version adapters')
    parser.add_argument('--keep-cache', action='store_true', help='retain project caches for development')
    parser.add_argument('--deep', action='store_true', help='compatibility alias; full project cleanup is already default')
    args = parser.parse_args()
    pack = module('tailcat_release_pack', 'package-universal.py')
    verify = module('tailcat_release_verify', 'verify-artifacts.py')
    env = dict(os.environ, GRADLE_USER_HOME=str(HOME))
    dist = ROOT / 'dist'
    reports = dist / 'reports'
    reports.mkdir(parents=True, exist_ok=True)
    legacy_gradle = gradle8(download=False)
    if not args.no_build:
        env['JAVA_HOME'] = jdk(25)
        installations = ','.join(jdk(v) for v in (17, 21, 25))
        root_ids = [v['id'] for v in pack.catalog() if v['buildSystem'] == 'root']
        run([ROOT / 'gradlew', '-PtcTargets=' + ','.join(root_ids), '-Porg.gradle.java.installations.paths=' + installations,
             'build', '--no-daemon', '--console=plain'], reports / 'gradle-main.log', env)
        legacy_ids = [v['id'] for v in pack.catalog() if v['buildSystem'] == 'forge6']
        if legacy_ids:
            legacy_gradle = gradle8()
            legacy_env = dict(env, JAVA_HOME=jdk(21))
            command = [legacy_gradle, '--no-daemon', '--settings-file', 'platforms/forge-legacy-build/settings.gradle',
                       '-PtcTargets=' + ','.join(legacy_ids), '--console=plain']
            # Forge 1.16's MCP tools need an actual JDK 8 as well as javac --release 8.
            command += ['-Porg.gradle.java.installations.paths=' + jdk(8) + ',' + installations]
            command += [':' + name + ':build' for name in legacy_ids]
            run(command, reports / 'gradle-forge6.log', legacy_env)
    run([sys.executable, '-m', 'unittest', 'discover', '-s', 'tests', '-v'], reports / 'python-tests.log', env)
    for project in ('core', 'universal'):
        source = ROOT / project / 'build/test-results/test'
        if source.exists(): shutil.copytree(source, reports / project, dirs_exist_ok=True)
    # Publish only after all seven candidates have passed checksum/metadata checks.
    with tempfile.TemporaryDirectory(prefix='.release-', dir=dist) as staging:
        stage = Path(staging)
        run([sys.executable, ROOT / 'scripts/package-universal.py', '--output', stage], reports / 'packaging.log', env)
        matrix = json.loads((stage / 'build-matrix.json').read_text())
        for item in matrix['artifacts']:
            failures = verify.verify_archive(stage / item['file'])
            if failures: raise RuntimeError(item['file'] + ': ' + '; '.join(failures))
        for source in stage.iterdir(): os.replace(source, dist / source.name)
    (reports / 'artifact-verification.json').write_text(json.dumps(matrix, indent=2) + '\n')
    if not args.keep_cache:
        for executable, java in [(str(ROOT / 'gradlew'), jdk(25)), (legacy_gradle, None)]:
            if executable:
                subprocess.run([executable, '--stop'], cwd=ROOT, env=dict(env, JAVA_HOME=java or jdk(21)),
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
        cleanup = module('tailcat_release_cleanup', 'prune-caches.py')
        removed = cleanup.prune(ROOT)
        (dist / 'cache-cleanup.json').write_text(json.dumps(removed, indent=2) + '\n')
        print('Removed project cache bytes:', sum(item.get('removedBytes', 0) for item in removed))
    for item in matrix['artifacts']: print(item['file'], item['bytes'], 'bytes')
    return 0


if __name__ == '__main__':
    try: sys.exit(main())
    except (RuntimeError, OSError, ValueError) as failure:
        print(str(failure), file=sys.stderr)
        sys.exit(1)
