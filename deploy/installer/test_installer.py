import gzip
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('installer', HERE / 'installer.py')
installer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(installer)
ARCHIVE = HERE.parents[2] / '部署安装/callnexus-deploy-20261028-final-r3.zip'


class InstallerTests(unittest.TestCase):
    def fixture(self, root):
        with zipfile.ZipFile(ARCHIVE) as archive:
            for entry in archive.infolist():
                relative = entry.filename.removeprefix('callnexus-deploy/')
                if relative in ('.env.example', 'compose.yaml') or relative.startswith(('templates/', 'database/reviewed/', 'minio/')):
                    if entry.is_dir(): continue
                    destination = root / relative
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    destination.write_bytes(archive.read(entry))
        (root / 'frontend/dist').mkdir(parents=True)
        script = root / 'frontend/dist/app.js'
        script.write_text('const a="__CNX_FRONTEND_RSA_PUBLIC_KEY__",b="__CNX_FRONTEND_RSA_PRIVATE_KEY__";', encoding='utf-8')
        script.with_suffix('.js.gz').write_bytes(gzip.compress(script.read_bytes()))
        (root / 'freeswitch/bin').mkdir(parents=True)
        (root / 'freeswitch/bin/curl').write_text('fixture')

    def test_missing_inventory_fails_closed(self):
        with patch.dict(os.environ, {}, clear=True):
            with self.assertRaises(ValueError): installer.require_empty_project('callnexus')

    def test_detects_existing_containers_and_volumes(self):
        cases = [
            {'CNX_INSTALLER_CONTAINER_PROJECTS': 'other\ncallnexus'},
            {'CNX_INSTALLER_VOLUMES': json.dumps({'name': 'callnexus_mysql_data', 'labels': None})},
            {'CNX_INSTALLER_VOLUMES': json.dumps({'name': 'custom', 'labels': {'com.docker.compose.project': 'callnexus'}})}
        ]
        for values in cases:
            with self.subTest(values=values), patch.dict(os.environ, {'CNX_INSTALLER_SNAPSHOT_READY': '1', **values}, clear=True):
                with self.assertRaises(ValueError): installer.require_empty_project('callnexus')

    def test_empty_inventory_and_unrelated_projects(self):
        with patch.dict(os.environ, {'CNX_INSTALLER_SNAPSHOT_READY': '1', 'CNX_INSTALLER_CONTAINER_PROJECTS': 'other'}, clear=True):
            installer.require_empty_project('callnexus')

    def test_mysql_identity_is_explicit(self):
        with patch.dict(os.environ, {}, clear=True):
            with self.assertRaises(ValueError): installer.mysql_identity()
        with patch.dict(os.environ, {'CNX_INSTALLER_MYSQL_UID': '1234', 'CNX_INSTALLER_MYSQL_GID': '4321'}, clear=True):
            self.assertEqual((1234, 4321), installer.mysql_identity())

    def test_init_render_and_repeat_preserve_keys(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {'CNX_INSTALLER_SNAPSHOT_READY': '1'}, clear=True):
            root = Path(directory); self.fixture(root)
            with patch.object(installer.configure.shutil, 'which', return_value='openssl'), patch.object(installer.configure, 'rsa_pair', return_value=('private-fixture', 'public-fixture')):
                installer.main(['init', '--bind-ip', '10.1.2.3', '--public-ip', '203.0.113.10'], root)
            original = (root / '.env').read_bytes()
            installer.main(['render'], root)
            installer.main(['sql-check'], root)
            self.assertEqual(original, (root / '.env').read_bytes())
            rendered = (root / 'runtime/frontend/app.js').read_bytes()
            self.assertNotIn(b'__CNX_FRONTEND_RSA_', rendered)
            self.assertEqual(rendered, gzip.decompress((root / 'runtime/frontend/app.js.gz').read_bytes()))
            with self.assertRaises(ValueError): installer.main(['init', '--bind-ip', '10.1.2.3'], root)
            self.assertEqual(original, (root / '.env').read_bytes())

    def test_bad_frontend_does_not_change_runtime(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); self.fixture(root)
            (root / 'frontend/dist/app.js').write_text('no placeholders')
            with self.assertRaises(ValueError): installer.main(['render'], root)
            self.assertFalse((root / 'runtime').exists())

    def test_no_docker_socket_or_autostart(self):
        compose = (HERE / 'compose.installer.yaml').read_text()
        self.assertNotIn('docker.sock', compose)
        self.assertIn('profiles: [tools]', compose)
        self.assertIn('read_only: true', compose)
        self.assertNotIn('privileged:', compose)


if __name__ == '__main__': unittest.main()
