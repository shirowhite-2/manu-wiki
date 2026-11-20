"""Configure the native authorization extension without changing unrelated settings."""
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / 'extensions/workflow-policy/configure-departments.sh'

class ConfigureDepartmentsTest(unittest.TestCase):
    def test_installs_once_and_preserves_other_properties(self):
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / 'xwiki properties'
            config.write_text('# example\nother.property=keep\nsecurity.authorization.settler=default\n')
            result = subprocess.run(['sh', str(SCRIPT), str(config)], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn('other.property=keep', config.read_text())
            self.assertEqual(config.read_text().count('security.authorization.settler='), 1)
            self.assertIn('security.authorization.settler=luxpedia-departments', config.read_text())
            result = subprocess.run(['sh', str(SCRIPT), str(config)], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(config.read_text().count('security.authorization.settler='), 1)

    def test_retains_an_existing_different_authorization_integration(self):
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / 'xwiki.properties'
            original = 'security.authorization.settler=company-auth\n'
            config.write_text(original)
            result = subprocess.run(['sh', str(SCRIPT), str(config)], capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(config.read_text(), original)


class ConfigureDepartmentEntrypointTest(unittest.TestCase):
    def test_authorization_runs_after_restore_before_tomcat_and_preserves_arguments(self):
        import os
        script = SCRIPT.with_name('configure-department-entrypoint.sh')
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            runtime = root / 'webapps/ROOT/WEB-INF'
            runtime.mkdir(parents=True)
            backup = root / 'saved.properties'
            backup.write_text('other.property=keep\nsecurity.authorization.settler=default\n')
            entrypoint = root / 'entrypoint.sh'
            entrypoint.write_text('#!/bin/sh\nset -eu\nif [ "$1" = xwiki ]; then\n  CONTEXT_PATH=ROOT\n  cp "'+str(backup)+'" "'+str(runtime / 'xwiki.properties')+'"\n  shift\n  set -- catalina.sh run "$@"\nfi\nexec "$@"\n')
            result = subprocess.run(['sh', str(script), str(entrypoint)], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            patched = entrypoint.read_text()
            self.assertLess(patched.index('cp "'), patched.index('# LUXPEDIA_DEPARTMENT_AUTHORIZATION'))
            self.assertLess(patched.index('# LUXPEDIA_DEPARTMENT_AUTHORIZATION'), patched.index('set -- catalina.sh'))
            subprocess.run(['sh', str(script), str(entrypoint)], check=True)
            self.assertEqual(entrypoint.read_text(), patched)
            # Execute the patched startup against sandbox paths and a fake Tomcat command.
            entrypoint.write_text(patched.replace('/usr/local/bin/luxpedia-configure-departments.sh', str(SCRIPT)).replace('/usr/local/tomcat/webapps/', str(root / 'webapps') + '/'))
            tomcat = root / 'catalina.sh'
            tomcat.write_text('#!/bin/sh\ngrep -q "security.authorization.settler=luxpedia-departments" "'+str(runtime / 'xwiki.properties')+'" || exit 1\nprintf "%s\\n" "$@"\n')
            tomcat.chmod(0o755)
            result = subprocess.run(['sh', str(entrypoint), 'xwiki', '--keep-argument'], env={**os.environ, 'PATH': str(root) + ':' + os.environ['PATH']}, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout.splitlines(), ['run', '--keep-argument'])
            self.assertIn('other.property=keep', (runtime / 'xwiki.properties').read_text())

    def test_unknown_startup_is_preserved(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'entrypoint.sh'
            path.write_text('#!/bin/sh\nexec "$@"\n')
            result = subprocess.run(['sh', str(SCRIPT.with_name('configure-department-entrypoint.sh')), str(path)], capture_output=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(path.read_text(), '#!/bin/sh\nexec "$@"\n')
