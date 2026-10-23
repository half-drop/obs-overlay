import importlib.util
from pathlib import Path
import tempfile
import unittest
import json
import sys
import zipfile
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('validate', Path(__file__).with_name('validate.py'))
v = importlib.util.module_from_spec(spec)
spec.loader.exec_module(v)


class EvidenceTests(unittest.TestCase):
    def test_incomplete_and_blocked_never_pass(self):
        self.assertEqual(v.summarize({}), 'incomplete')
        results = {c: {'status': 'pass'} for c in v.CASES}
        self.assertIn('manual-acceptance-pass', v.summarize(results))
        results['isolation']['status'] = 'blocked'
        self.assertEqual(v.summarize(results), 'incomplete')
        results['isolation']['status'] = 'fail'
        self.assertEqual(v.summarize(results), 'fail')

    def test_cli_session_and_snapshot(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            jar = root / 'fixture.jar'
            with zipfile.ZipFile(jar, 'w') as z:
                z.writestr('assets/obs_overlay/native/windows-x86_64/obs_overlay_vulkan.dll', b'MZfixture')
            out = root / 'run'
            def invoke(args):
                with patch.object(sys, 'argv', ['validate.py', *map(str, args)]):
                    v.main()
            with patch.object(sys, 'platform', 'win32'), patch.object(v, 'command', return_value={'returncode': 0, 'stdout': 'fixture'}):
                invoke(['init', '--jar', jar, '--out', out, '--commit', 'a' * 40])
                data = json.loads((out / 'report.json').read_text())
                self.assertEqual(data['summary'], 'incomplete')
                self.assertEqual(data['cases'], {})
                invoke(['snapshot', '--out', out, '--game-pid', 123, '--game-dir', root, '--obs-log', root / 'missing.log'])
                data = json.loads((out / 'report.json').read_text())
                self.assertEqual(len(data['snapshots'][0]['files']), 3)
                self.assertTrue(all(f['error'] == 'missing' for f in data['snapshots'][0]['files']))
            evidence = root / 'note.txt'
            evidence.write_text('fixture only')
            invoke(['record', '--out', out, '--case', 'isolation', '--status', 'blocked', '--note', 'fixture', '--evidence', evidence])
            data = json.loads((out / 'report.json').read_text())
            self.assertEqual(data['summary'], 'incomplete')
            self.assertEqual(data['cases']['isolation']['verdict_source'], 'human')
            self.assertEqual(len(data['history']), 1)

    def test_evidence_is_copied_hashed_and_not_overwritten(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            source = root / 'input.txt'
            source.write_text('evidence', encoding='utf-8')
            out = root / 'out'
            out.mkdir()
            item = v.attach(source, out, 'sample')
            self.assertEqual(item['sha256'], v.digest(source))
            self.assertEqual((out / item['file']).read_bytes(), source.read_bytes())
            with self.assertRaises(FileExistsError):
                v.attach(source, out, 'sample')


if __name__ == '__main__':
    unittest.main()
