import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('telegram_release', Path(__file__).parents[1] / 'telegram-release.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def release():
    return {'tag_name': 'v2.4.5', 'name': '2.4.5', 'body': '## Fixed\nConnect and local files.',
            'html_url': 'https://github.com/Lelonio/Square/releases/tag/v2.4.5',
            'draft': False, 'published_at': '2026-10-07T10:42:26Z',
            'assets': [{'name': f'square-2.4.5-{abi}.apk', 'state': 'uploaded', 'size': 20,
                        'browser_download_url': f'https://github.com/Lelonio/Square/releases/download/v2.4.5/square-2.4.5-{abi}.apk'} for abi in module.ABIS]}


class ReleaseTests(unittest.TestCase):
    def test_notes_keep_every_character_and_respect_emoji_limit(self):
        value = release()
        value['body'] = '🧪 test\n' * 3000
        chunks = module.messages(value)
        self.assertTrue(all(len(c.encode('utf-16-le')) // 2 <= 3500 for c in chunks))
        self.assertEqual(''.join(chunks), f"Square 2.4.5\n\n{value['html_url']}\n\n{value['body']}")

    def test_all_three_apks_required(self):
        value = release()
        value['assets'].pop()
        with self.assertRaisesRegex(ValueError, 'universal'):
            module.apks(value)

    def test_unexpected_download_source_rejected(self):
        value = release()
        value['assets'][0]['browser_download_url'] = 'https://example.com/other.apk'
        with self.assertRaisesRegex(ValueError, 'Unexpected download source'):
            module.apks(value)

    def test_dry_run_never_downloads_or_posts(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'release.json'
            path.write_text(json.dumps(release()))
            with patch.dict(os.environ, {'DRY_RUN': 'true', 'TELEGRAM_THREAD_ID': '12'}, clear=True), \
                 patch('sys.argv', ['telegram-release.py', str(path)]), \
                 patch.object(module.request, 'urlopen') as network, patch('builtins.print') as output:
                module.main()
                network.assert_not_called()
                preview = json.loads(output.call_args.args[0])
                self.assertEqual(preview['destinations'][1]['message_thread_id'], 12)
                self.assertNotIn('message_thread_id', preview['destinations'][0])
                self.assertEqual(len(preview['apks']), 3)

    def test_draft_never_posts(self):
        value = release()
        value['draft'] = True
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'release.json'
            path.write_text(json.dumps(value))
            with patch('sys.argv', ['telegram-release.py', str(path)]), patch.object(module.request, 'urlopen') as network:
                with self.assertRaisesRegex(ValueError, 'Only published'):
                    module.main()
                network.assert_not_called()


if __name__ == '__main__':
    unittest.main()
