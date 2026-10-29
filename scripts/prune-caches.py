#!/usr/bin/env python3
"""Remove only project-managed build caches after a release is exported."""
import argparse
import json
import os
from pathlib import Path
import shutil
import stat


def prune(root):
    root = Path(root).resolve()
    targets = [root / '.cache', root / '.gradle', root / 'build', root / 'core/build', root / 'universal/build', root / 'helper/bin']
    targets += [root / '.gradle-user-home' / name for name in ('caches', 'daemon', 'native', '.tmp')]
    targets += list((root / 'platforms').glob('*/build'))
    report = []
    def writable_remove(function, path, exception):
        candidate = Path(path)
        if not candidate.parent.resolve().is_relative_to(root):
            raise exception[1]
        os.chmod(candidate.parent, stat.S_IRWXU)
        if not candidate.is_symlink(): os.chmod(candidate, stat.S_IRWXU)
        function(path)
    for path in targets:
        if path.is_symlink():
            path.unlink()
            report.append({'path': str(path.relative_to(root)), 'symlinkOnly': True})
        elif path.exists() and path.resolve().is_relative_to(root):
            size = sum(p.stat().st_size for p in path.rglob('*') if p.is_file() and not p.is_symlink()) if path.is_dir() else path.stat().st_size
            if path.is_dir(): shutil.rmtree(path, onerror=writable_remove)
            else: path.unlink()
            report.append({'path': str(path.relative_to(root)), 'removedBytes': size})
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--after-export', action='store_true', help='confirm dist is already verified/exported')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    if not args.after_export or not (root / 'dist/build-matrix.json').is_file():
        parser.error('Use scripts/package.sh; direct cleanup requires verified dist/build-matrix.json and --after-export')
    report = prune(root)
    (root / 'dist/cache-cleanup.json').write_text(json.dumps(report, indent=2) + '\n')
    print('Removed project caches:', sum(item.get('removedBytes', 0) for item in report), 'bytes')


if __name__ == '__main__':
    main()
