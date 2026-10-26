"""Verify a DecibelEvi ZIP without extracting it. Python 3.9+, standard library only."""
import argparse
import hashlib
import re
import sys
import zipfile
import zlib
from pathlib import Path, PurePosixPath


def verify(path):
    """Return human-readable failures. Empty list means all listed payloads match."""
    errors = []
    try:
        with zipfile.ZipFile(path) as archive:
            files = [item for item in archive.infolist() if not item.is_dir()]
            names = [item.filename for item in files]
            if len(names) != len(set(names)):
                return ['ZIP contains duplicate filenames']
            for name in names:
                if PurePosixPath(name).is_absolute() or '..' in name.split('/') or '\\' in name or ':' in name:
                    errors.append(f'Unsafe relative path: {name}')
            if errors:
                return errors
            if 'SHA256SUMS.txt' not in names:
                return ['SHA256SUMS.txt is missing']
            if archive.getinfo('SHA256SUMS.txt').file_size > 2 * 1024 * 1024:
                return ['Checksum manifest exceeds 2MB']
            manifest = archive.read('SHA256SUMS.txt').decode('utf-8')
            expected = {}
            for line in manifest.splitlines():
                if not line.strip():
                    continue
                match = re.fullmatch(r'([0-9a-fA-F]{64})  (.+)', line)
                if not match:
                    return [f'Invalid checksum entry: {line[:100]}']
                digest, name = match.groups()
                if name == 'SHA256SUMS.txt' or name in expected:
                    return [f'Duplicate or self-referencing checksum: {name}']
                expected[name] = digest.lower()
            actual = set(names) - {'SHA256SUMS.txt'}
            for name in sorted(actual - set(expected)):
                errors.append(f'Unlisted file: {name}')
            for name in sorted(set(expected) - actual):
                errors.append(f'Missing file: {name}')
            for name in sorted(actual & set(expected)):
                digest = hashlib.sha256()
                with archive.open(name) as stream:
                    for block in iter(lambda: stream.read(65536), b''):
                        digest.update(block)
                if digest.hexdigest() != expected[name]:
                    errors.append(f'SHA-256 mismatch: {name}')
    except (OSError, ValueError, zipfile.BadZipFile, UnicodeError, RuntimeError, zlib.error, EOFError) as error:
        errors.append(f'Cannot verify archive: {error}')
    return errors


def main():
    parser = argparse.ArgumentParser(description='Verify all file hashes in a DecibelEvi evidence ZIP.')
    parser.add_argument('zip_file', type=Path)
    args = parser.parse_args()
    failures = verify(args.zip_file)
    if failures:
        for error in failures:
            print('FAIL: ' + error)
        return 1
    print('PASS: every payload is listed and matches its SHA-256 checksum.')
    print('Checksums verify content consistency, not capture time, location or identity.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
