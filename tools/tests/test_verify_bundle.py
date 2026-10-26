import hashlib
import tempfile
import unittest
import zipfile
import struct
from pathlib import Path
from tools.verify_bundle import verify

class BundleVerificationTests(unittest.TestCase):
    def make(self, directory, altered=False, extra=False, unsafe=False):
        path = Path(directory) / 'evidence.zip'
        content = '时间,分贝\n0,60\n'.encode('utf-8')
        digest = hashlib.sha256(content).hexdigest()
        name = '../escape.csv' if unsafe else 'measurements.csv'
        with zipfile.ZipFile(path, 'w') as archive:
            archive.writestr(name, content + (b'changed' if altered else b''))
            archive.writestr('SHA256SUMS.txt', f'{digest}  {name}\n')
            if extra:
                archive.writestr('unlisted.txt', 'extra')
        return path

    def test_valid_archive(self):
        with tempfile.TemporaryDirectory() as root:
            self.assertEqual([], verify(self.make(root)))

    def test_modified_payload_is_detected(self):
        with tempfile.TemporaryDirectory() as root:
            self.assertTrue(verify(self.make(root, altered=True)))

    def test_unlisted_payload_is_detected(self):
        with tempfile.TemporaryDirectory() as root:
            self.assertTrue(verify(self.make(root, extra=True)))

    def test_traversal_path_is_rejected(self):
        with tempfile.TemporaryDirectory() as root:
            self.assertTrue(verify(self.make(root, unsafe=True)))

    def test_corrupt_compressed_payload_returns_failure(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / 'broken.zip'
            data = b'evidence' * 100
            with zipfile.ZipFile(path, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
                archive.writestr('media.dat', data)
                archive.writestr('SHA256SUMS.txt', hashlib.sha256(data).hexdigest() + '  media.dat\n')
            raw = bytearray(path.read_bytes())
            name_length, extra_length = struct.unpack_from('<HH', raw, 26)
            raw[30 + name_length + extra_length] = 7  # Reserved DEFLATE block type.
            path.write_bytes(raw)
            self.assertTrue(verify(path))

if __name__ == '__main__':
    unittest.main()
