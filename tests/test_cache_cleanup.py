import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('cache_pruner', Path(__file__).parents[1] / 'scripts/prune-caches.py')
pruner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pruner)


class CacheCleanupTest(unittest.TestCase):
    def test_removes_managed_caches_but_keeps_source_dist_and_tools(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ('.cache/go-build/item', '.gradle/item', '.gradle-user-home/caches/item',
                         'core/build/classes/item', 'universal/build/tmp/item', 'platforms/fabric1165/build/item',
                         'dist/release.jar', 'helper/third_party/LICENSE', '.gradle-user-home/wrapper/tool.zip'):
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b'keep-or-remove')
            pruner.prune(root)
            self.assertFalse((root / '.cache').exists())
            self.assertFalse((root / 'core/build').exists())
            self.assertFalse((root / 'platforms/fabric1165/build').exists())
            self.assertTrue((root / 'dist/release.jar').exists())
            self.assertTrue((root / 'helper/third_party/LICENSE').exists())
            self.assertTrue((root / '.gradle-user-home/wrapper/tool.zip').exists())

    def test_never_follows_cache_symlink_outside_project(self):
        with tempfile.TemporaryDirectory() as directory, tempfile.TemporaryDirectory() as external:
            root = Path(directory)
            outside = Path(external)
            sentinel = outside / 'save.dat'
            sentinel.write_text('unrelated user data')
            (root / '.cache').symlink_to(outside, target_is_directory=True)
            pruner.prune(root)
            self.assertEqual('unrelated user data', sentinel.read_text())
            self.assertFalse((root / '.cache').exists())


if __name__ == '__main__':
    unittest.main()
