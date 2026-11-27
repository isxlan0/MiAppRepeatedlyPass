"""Offline regressions for range/cache integrity and selective payload reconstruction."""
from pathlib import Path
import bz2
import hashlib
import io
import lzma
import os
import struct
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from extract_ota_file import Erofs, HttpRanges, LocalReader, Partition, Payload, extract, protobuf


def varint(value):
    result = bytearray()
    while value >= 128:
        result.append((value & 127) | 128)
        value >>= 7
    result.append(value)
    return bytes(result)


def field(number, value):
    return varint(number * 8) + varint(value) if isinstance(value, int) else varint(number * 8 + 2) + varint(len(value)) + value


def ota_file(output, chunks, minor=0, compression=zipfile.ZIP_STORED):
    operations, blobs = b'', bytearray()
    for index, (kind, data) in enumerate(chunks):
        encoded = lzma.compress(data) if kind == 8 else bz2.compress(data) if kind == 1 else data
        if kind == 6:
            encoded = b''
        extent = field(1, index * len(data) // 4096) + field(2, len(data) // 4096)
        operation = field(1, kind) + field(2, len(blobs)) + field(3, len(encoded)) + field(6, extent)
        if encoded:
            operation += field(8, hashlib.sha256(encoded).digest())
        operations += field(8, operation)
        blobs += encoded
    part = field(1, b'system') + field(7, field(1, sum(len(data) for _, data in chunks))) + operations
    manifest = field(3, 4096) + field(12, minor) + field(13, part)
    payload = struct.pack('>4sQQI', b'CrAU', 2, len(manifest), 0) + manifest + blobs
    with zipfile.ZipFile(output, 'w', compression) as zip:
        zip.writestr('payload.bin', payload)


class RangeTests(unittest.TestCase):
    def fake_curl(self, command, **kwargs):
        requested = command[command.index('--range') + 1]
        start, end = map(int, requested.split('-'))
        body, headers = Path(command[command.index('--output') + 1]), Path(command[command.index('--dump-header') + 1])
        body.write_bytes(self.content[start:end + 1])
        headers.write_text(f'HTTP/1.1 {self.status} Test\r\nContent-Range: bytes {start}-{end}/{len(self.content)}\r\nETag: "{self.etag}"\r\n\r\n', encoding='ascii', newline='')
        return subprocess.CompletedProcess(command, 0, b'', b'')

    def setUp(self):
        self.folder = tempfile.TemporaryDirectory(prefix='miapp-http-')
        self.addCleanup(self.folder.cleanup)
        self.content, self.status, self.etag = b'0123456789' * 100, 206, 'one'
        patcher = patch('extract_ota_file.subprocess.run', side_effect=self.fake_curl)
        patcher.start()
        self.addCleanup(patcher.stop)

    def source(self, budget=10000):
        return HttpRanges('https://example.invalid/full.zip?token=secret', Path(self.folder.name), budget)

    def test_range_and_cache_corruption(self):
        source = self.source()
        self.assertEqual(source.read_at(10, 20), self.content[10:30])
        transferred = source.downloaded
        self.assertEqual(source.read_at(10, 20), self.content[10:30])
        self.assertEqual(source.downloaded, transferred)
        (source.cache / '10-20.bin').write_bytes(b'X' * 20)
        self.assertEqual(source.read_at(10, 20), self.content[10:30])
        self.assertEqual(source.downloaded, transferred + 20)
        self.assertNotIn('secret', source.origin)

    def test_refuses_full_response(self):
        self.status = 200
        with self.assertRaisesRegex(ValueError, 'did not honor Range'):
            self.source()

    def test_refuses_etag_change(self):
        source = self.source()
        self.etag = 'two'
        with self.assertRaisesRegex(ValueError, 'ETag changed'):
            source.read_at(10, 20)

    def test_download_budget(self):
        source = self.source(20)
        with self.assertRaisesRegex(ValueError, 'budget exceeded'):
            source.read_at(10, 20)


class PayloadTests(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory(prefix='miapp-payload-')
        self.addCleanup(self.folder.cleanup)
        self.root = Path(self.folder.name)

    def test_only_requested_operations_materialized(self):
        zip = self.root / 'ota.zip'
        ota_file(zip, [(8, b'A' * 4096), (1, b'B' * 4096), (0, b'C' * 4096), (6, bytes(4096))])
        part = Partition(Payload(LocalReader(zip)), 'system', self.root / 'partial.img')
        self.assertEqual(part.read_at(4090, 20), b'A' * 6 + b'B' * 14)
        self.assertEqual(part.loaded, {0, 1})
        self.assertEqual(part.read_at(12288, 10), bytes(10))
        self.assertNotIn(2, part.loaded)
        self.assertEqual(part.read_at(8192, 4096), b'C' * 4096)

    def test_operation_hash_must_match(self):
        zip = self.root / 'ota.zip'
        ota_file(zip, [(0, b'A' * 4096)])
        payload = Payload(LocalReader(zip))
        with zip.open('r+b') as file:
            file.seek(payload.start + payload.blobs)
            file.write(b'X')
        part = Partition(payload, 'system', self.root / 'partial.img')
        with self.assertRaisesRegex(ValueError, 'SHA-256 mismatch'):
            part.read_at(0, 10)

    def test_rejects_incremental_and_compressed_payload(self):
        for options, reason in [({'minor': 2}, 'Incremental'), ({'compression': zipfile.ZIP_DEFLATED}, 'must be stored')]:
            with self.subTest(options=options):
                zip = self.root / 'ota.zip'
                ota_file(zip, [(0, bytes(4096))], **options)
                with self.assertRaisesRegex(ValueError, reason):
                    Payload(LocalReader(zip))

    def test_unknown_operation_is_not_treated_as_zero(self):
        zip = self.root / 'ota.zip'
        ota_file(zip, [(4, bytes(4096))])
        part = Partition(Payload(LocalReader(zip)), 'system', self.root / 'partial.img')
        with self.assertRaisesRegex(ValueError, 'Unsupported operation'):
            part.read_at(0, 10)

    def test_truncated_protobuf(self):
        with self.assertRaisesRegex(ValueError, 'Truncated'):
            protobuf(b'\x0a\x09\x01')

    @unittest.skipUnless((ROOT / 'build/rom-tools/erofs/mkfs.erofs.exe').exists(), 'Local erofs tools unavailable')
    def test_erofs_file_reconstructed_from_subset_of_payload(self):
        # A real compressed EROFS fixture, packaged in a synthetic full payload.
        source = self.root / 'files'
        target = source / 'system/framework/services.jar'
        target.parent.mkdir(parents=True)
        with zipfile.ZipFile(target, 'w') as zip:
            zip.writestr('classes.dex', b'test-dex-data' * 1000)
        (source / 'unrelated.bin').write_bytes(os.urandom(4 * 1024 * 1024))
        image = self.root / 'system.img'
        tool = ROOT / 'build/rom-tools/erofs'
        result = subprocess.run([str(tool / 'mkfs.erofs.exe'), '-zlz4', str(image), str(source)], capture_output=True)
        self.assertEqual(result.returncode, 0, result.stderr.decode(errors='replace'))
        original = image.read_bytes()
        original += bytes((-len(original)) % 65536)
        ota = self.root / 'ota.zip'
        ota_file(ota, [(8, original[pos:pos + 65536]) for pos in range(0, len(original), 65536)])
        part = Partition(Payload(LocalReader(ota)), 'system', self.root / 'partial.img')
        output = self.root / 'services.jar'
        extract(part, '/system/framework/services.jar', tool / 'dump.erofs.exe', output)
        self.assertEqual(output.read_bytes(), target.read_bytes())
        self.assertLess(len(part.loaded), len(part.operations) // 2)
        with self.assertRaisesRegex(ValueError, 'without ..'):
            Erofs(part).lookup('/system/../services.jar')


if __name__ == '__main__':
    unittest.main()
