import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('cleanup', Path(__file__).with_name('cleanup_consolidated_branches.py'))
cleanup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cleanup)


class CleanupSafetyTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        root = Path(self.tmp.name)
        self.previous = Path.cwd()
        self.addCleanup(os.chdir, self.previous)
        self.call('init', '--bare', str(root / 'origin.git'))
        self.call('clone', str(root / 'origin.git'), str(root / 'work'))
        os.chdir(root / 'work')
        self.call('config', 'user.name', 'Test fixture')
        self.call('config', 'user.email', 'fixture@example.invalid')
        self.call('switch', '-c', 'main')
        Path('file').write_text('base')
        self.call('add', 'file')
        self.call('commit', '-m', 'base')
        self.base = self.call('rev-parse', 'HEAD')
        self.call('branch', 'old-a')
        self.call('branch', 'old-b')
        Path('file').write_text('main')
        self.call('commit', '-am', 'main')
        self.main = self.call('rev-parse', 'HEAD')
        self.call('push', 'origin', 'main', 'old-a', 'old-b')

    def call(self, *args):
        return subprocess.run(['git', *args], check=True, text=True, capture_output=True).stdout.strip()

    def choose(self, expected=None, verified=None):
        return cleanup.select_candidates(cleanup.inventory(), verified or self.main,
            expected or {'old-a': self.base, 'old-b': self.base}, 'integration', self.base)

    def test_deletes_only_merged_audited_heads(self):
        candidates = self.choose()
        cleanup.delete_candidates(candidates)
        self.assertEqual({'main': self.main}, cleanup.inventory())

    def test_never_selects_main(self):
        with self.assertRaisesRegex(RuntimeError, 'never'):
            self.choose({'main': self.main})

    def test_never_deletes_main(self):
        with self.assertRaisesRegex(RuntimeError, 'never'):
            cleanup.delete_candidates({'main': self.main})
        self.assertEqual(self.main, cleanup.inventory()['main'])

    def test_changed_head_rejected_before_deletion(self):
        with self.assertRaisesRegex(RuntimeError, 'changed'):
            self.choose({'old-a': self.main})
        self.assertEqual(3, len(cleanup.inventory()))

    def test_moved_main_rejected(self):
        with self.assertRaisesRegex(RuntimeError, 'main moved'):
            self.choose(verified=self.base)

    def test_unmerged_head_rejected(self):
        self.call('switch', 'old-a')
        Path('extra').write_text('unmerged')
        self.call('add', 'extra')
        self.call('commit', '-m', 'unmerged')
        new = self.call('rev-parse', 'HEAD')
        self.call('push', 'origin', 'old-a')
        with self.assertRaisesRegex(RuntimeError, 'unmerged'):
            self.choose({'old-a': new})
        self.assertEqual(3, len(cleanup.inventory()))

    def test_atomic_lease_prevents_racy_partial_deletion(self):
        candidates = self.choose()
        self.call('switch', 'old-a')
        Path('extra').write_text('concurrent work')
        self.call('add', 'extra')
        self.call('commit', '-m', 'concurrent work')
        new = self.call('rev-parse', 'HEAD')
        self.call('push', 'origin', 'old-a')
        with self.assertRaises(subprocess.CalledProcessError):
            cleanup.delete_candidates(candidates)
        self.assertEqual({'main': self.main, 'old-a': new, 'old-b': self.base}, cleanup.inventory())

    def test_missing_old_branch_is_harmless(self):
        self.call('push', 'origin', ':old-a')
        self.assertEqual({'old-b': self.base}, self.choose())

    def test_unknown_branch_is_never_selected(self):
        self.call('push', 'origin', f'{self.base}:refs/heads/new-work')
        cleanup.delete_candidates(self.choose())
        self.assertEqual({'main': self.main, 'new-work': self.base}, cleanup.inventory())

    def test_integration_head_can_be_ancestor_of_later_verified_main(self):
        self.call('push', 'origin', f'{self.base}:refs/heads/integration')
        self.assertEqual(self.base, self.choose()['integration'])

    def test_unmerged_integration_head_is_rejected(self):
        self.call('switch', '-c', 'integration', self.base)
        Path('integration-only').write_text('not merged')
        self.call('add', 'integration-only')
        self.call('commit', '-m', 'integration-only')
        head = self.call('rev-parse', 'HEAD')
        self.call('push', 'origin', 'integration')
        with self.assertRaisesRegex(RuntimeError, 'preserved'):
            cleanup.select_candidates(
                cleanup.inventory(), self.main, {}, 'integration', self.base
            )
        self.assertEqual(head, cleanup.inventory()['integration'])


if __name__ == '__main__':
    unittest.main(verbosity=2)
