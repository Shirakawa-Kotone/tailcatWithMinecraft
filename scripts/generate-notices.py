#!/usr/bin/env python3
"""Collect license/notice files for the Go modules actually linked into the helper."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def json_stream(text):
    decoder = json.JSONDecoder()
    while text.strip():
        text = text.lstrip()
        item, end = decoder.raw_decode(text)
        yield item
        text = text[end:]


def main():
    go = os.environ.get('TC_GO', 'go')
    modules = {}
    for target in ('windows/amd64', 'windows/arm64', 'darwin/amd64', 'darwin/arm64', 'linux/amd64', 'linux/arm64'):
        target_os, target_arch = target.split('/')
        environment = dict(os.environ, GOOS=target_os, GOARCH=target_arch, CGO_ENABLED='0')
        output = subprocess.check_output([go, 'list', '-deps', '-json', '-tags=ts_omit_ssh', './cmd/mclink-helper'],
                                         cwd=ROOT / 'helper', text=True, env=environment)
        for package in json_stream(output):
            if (module := package.get('Module')) and not module.get('Main'):
                modules[module['Path']] = module
    destination = ROOT / 'native/META-INF/licenses/go-dependencies'
    destination.mkdir(parents=True, exist_ok=True)
    index = []
    missing = []
    for name, module in sorted(modules.items()):
        module_root = Path(module.get('Replace', {}).get('Dir') or module['Dir'])
        files = [p for p in module_root.iterdir() if p.is_file() and
                 (p.name.upper().startswith(('LICENSE', 'LICENCE', 'COPYING', 'NOTICE', 'COPYRIGHT', 'PATENTS')))]
        if not files:
            missing.append(name)
            continue
        relative = name.replace('/', '__')
        out = destination / relative
        out.mkdir(parents=True, exist_ok=True)
        for source in files:
            shutil.copyfile(source, out / source.name)
        index.append({'module': name, 'version': module.get('Version', 'local'),
                      'files': [relative + '/' + p.name for p in sorted(files)]})
    goroot = Path(subprocess.check_output([go, 'env', 'GOROOT'], text=True).strip())
    shutil.copyfile(goroot / 'LICENSE', destination / 'GO-LICENSE')
    (destination / 'INDEX.json').write_text(json.dumps(index, indent=2) + '\n')
    notices = ROOT / 'native/META-INF/THIRD-PARTY-NOTICES.txt'
    notices.write_text('Tailcat Minecraft includes software from tailscale/tailcat-for-minecraft (BSD-3-Clause), '
                       'Tailcat, Tailscale, the Go standard library, and the Go modules listed in '
                       'licenses/go-dependencies/INDEX.json. Original license and notice texts are included '
                       'under META-INF/licenses/. Minecraft and loader APIs are provided by the player runtime.\n')
    if missing:
        print('Modules with no root license file; resolve before distribution: ' + ', '.join(missing), file=sys.stderr)
        return 1
    print(f'Collected license and notice files for {len(index)} Go modules')
    return 0


if __name__ == '__main__':
    sys.exit(main())
