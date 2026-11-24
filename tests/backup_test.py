"""Exercise backup orchestration without accessing Docker or a running wiki."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]


class BackupTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.repo = self.root / 'repo with spaces'
        (self.repo / 'scripts').mkdir(parents=True)
        shutil.copy2(ROOT / 'scripts/backup.sh', self.repo / 'scripts/backup.sh')
        for name in ('compose.yaml', 'compose.workflow-policy.yaml', '.env'):
            (self.repo / name).write_text('fixture\n')
        (self.repo / '.secrets').mkdir()
        (self.repo / '.secrets/db_password').write_text('test-password\n')
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        # Deliberately restricted PATH: a test can hide sha256sum even on Linux.
        for name in ('bash', 'tar', 'gzip', 'date', 'dirname', 'mkdir', 'rmdir', 'mv'):
            (self.bin / name).symlink_to(shutil.which(name))
        self.log = self.root / 'calls.jsonl'
        self.env = dict(os.environ, PATH=str(self.bin), CALL_LOG=str(self.log),
                        COMPOSE_FILE='compose.yaml:compose.workflow-policy.yaml')
        self.write_command('mktemp', f'''
import os, sys
# Enforce the BSD constraint while running on a Linux test host.
if not sys.argv[-1].endswith('XXXXXX'):
    sys.exit('mktemp template must end with XXXXXX')
os.execv({shutil.which('mktemp')!r}, ['mktemp'] + sys.argv[1:])
''')
        self.write_command('docker', '''
import io, json, os, sys, tarfile
args = sys.argv[1:]
with open(os.environ['CALL_LOG'], 'a') as log:
    log.write(json.dumps(args) + '\\n')
if args[0] == 'compose':
    args = args[3:]  # --project-directory PATH
    if args[:3] == ['ps', '--status', 'running']:
        print(args[-1] + '-id')
    elif args[:2] == ['exec', '-T']:
        if os.environ.get('FAIL_DUMP'):
            sys.exit(23)
        sys.stdout.buffer.write(b'PGDMP fixture')
elif args[0] == 'inspect':
    template = args[2]
    if '.Mounts' in template:
        print('volume fixture_xwiki_data')
    elif '.Config.Labels' in template:
        print('fixture')
    elif '.Config.Image' in template:
        print('postgres:fixture sha256:db')
        print('manu-wiki-xwiki-policy:fixture sha256:wiki')
    else:
        print('sha256:db')
elif args[0] == 'run':
    with tarfile.open(fileobj=sys.stdout.buffer, mode='w|gz') as archive:
        data = b'attachment fixture'
        info = tarfile.TarInfo('storage/attachment.bin')
        info.size = len(data)
        archive.addfile(info, io.BytesIO(data))
else:
    sys.exit('Unexpected Docker call')
''')

    def write_command(self, name, source):
        path = self.bin / name
        path.write_text('#!' + sys.executable + '\n' + source)
        path.chmod(0o700)

    def add_checksum(self, name):
        real_command = shutil.which(name)
        if not real_command:
            self.skipTest(name + ' is needed to exercise this checksum backend')
        self.write_command(name, f'''
import json, os, sys
with open(os.environ['CALL_LOG'], 'a') as log:
    log.write(json.dumps([{name!r}] + sys.argv[1:]) + '\\n')
os.execv({real_command!r}, [{name!r}] + sys.argv[1:])
''')

    def run_backup(self):
        self.result = subprocess.run(
            [str(self.bin / 'bash'), str(self.repo / 'scripts/backup.sh')],
            env=self.env, capture_output=True, text=True)
        return self.result

    def calls(self):
        return [json.loads(line) for line in self.log.read_text().splitlines()]

    def assert_backup(self, backend):
        self.assertEqual(self.run_backup().returncode, 0, self.result.stderr)
        directories = list((self.repo / 'backups').iterdir())
        self.assertEqual(len(directories), 1)
        backup = directories[0]
        self.assertNotIn('.partial.', backup.name)
        self.assertFalse((self.repo / '.runtime/backup.lock').exists())
        self.assertEqual(backup.stat().st_mode & 0o777, 0o700)
        with tarfile.open(backup / 'config.tar.gz') as archive:
            self.assertIn('compose.workflow-policy.yaml', archive.getnames())
            self.assertIn('.secrets/db_password', archive.getnames())
        for line in (backup / 'SHA256SUMS').read_text().splitlines():
            digest, name = line.split(None, 1)
            self.assertEqual(digest, hashlib.sha256((backup / name.strip()).read_bytes()).hexdigest())
        calls = self.calls()
        hashes = next(call for call in calls if call[0] == backend)
        if backend == 'shasum':
            self.assertEqual(hashes[1:3], ['-a', '256'])
        stop = next(i for i, call in enumerate(calls) if 'stop' in call)
        dump = next(i for i, call in enumerate(calls) if 'pg_dump' in call)
        archive = next(i for i, call in enumerate(calls) if call[0] == 'run')
        start = next(i for i, call in enumerate(calls) if 'start' in call)
        self.assertLess(stop, dump)
        self.assertLess(dump, archive)
        self.assertLess(archive, start)

    def test_gnu_checksum_and_policy_archive(self):
        self.add_checksum('sha256sum')
        self.assert_backup('sha256sum')

    def test_macos_checksum_fallback_and_portable_template(self):
        self.add_checksum('shasum')
        self.assert_backup('shasum')

    def test_missing_checksum_stops_before_service_changes(self):
        self.assertNotEqual(self.run_backup().returncode, 0)
        self.assertIn('SHA-256', self.result.stderr)
        self.assertFalse(self.log.exists())

    def test_dump_failure_retains_partial_and_restarts_wiki(self):
        self.add_checksum('shasum')
        self.env['FAIL_DUMP'] = '1'
        self.assertNotEqual(self.run_backup().returncode, 0)
        backup = next((self.repo / 'backups').iterdir())
        self.assertIn('.partial.', backup.name)
        self.assertFalse((backup / 'SHA256SUMS').exists())
        self.assertFalse((self.repo / '.runtime/backup.lock').exists())
        self.assertTrue(any('start' in call for call in self.calls()))
        self.assertIn('备份未完成', self.result.stderr)


if __name__ == '__main__':
    unittest.main()
