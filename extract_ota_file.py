"""Download one file from a full payload.bin OTA ZIP using verified HTTP ranges.

Requires Python 3.10+, curl, and erofs-utils dump.erofs (Windows: dump.erofs.exe).
Only ZIP metadata, payload manifest, and operations covering EROFS metadata/file
extents are fetched. The working image is incomplete and MUST NOT be flashed.
Incremental payloads, compressed payload ZIP entries, TGZ and non-EROFS images
are deliberately rejected. A .tgz is one compressed stream and cannot use this
random-access approach. No Android device or root access is used.

Format references:
https://android.googlesource.com/platform/system/update_engine/+/HEAD/update_metadata.proto
https://github.com/torvalds/linux/blob/master/fs/erofs/erofs_fs.h
"""
from __future__ import annotations

import argparse
import bz2
import hashlib
import io
import json
import lzma
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import struct
import subprocess
import tempfile
import urllib.parse
import zipfile


def require(ok, reason):
    if not ok:
        raise ValueError(reason)


def sha(data):
    return hashlib.sha256(data).hexdigest()


class HttpRanges:
    """Strict 206 reader; never falls back to downloading the entire ZIP."""
    def __init__(self, url, cache, limit, curl='curl', resolve=None):
        parsed = urllib.parse.urlsplit(url)
        require(parsed.scheme == 'https' and parsed.hostname and not parsed.username,
                'An HTTPS OTA ZIP URL is required')
        self.url, self.cache, self.limit, self.curl = url, cache, limit, curl
        self.resolve = resolve
        self.origin = urllib.parse.urlunsplit(parsed._replace(query='', fragment=''))
        self.downloaded = 0
        self.used_ranges = {(0, 1)}
        self.size = None
        self.etag = None
        cache.mkdir(parents=True, exist_ok=True)
        self._fetch(0, 1)
        identity = sha((self.origin + '\n' + str(self.size) + '\n' + str(self.etag)).encode())
        self.cache = cache / identity
        self.cache.mkdir(exist_ok=True)

    def _fetch(self, offset, length):
        require(length > 0 and self.downloaded + length <= self.limit,
                'Download budget exceeded; increase --max-download-mib explicitly')
        with tempfile.TemporaryDirectory(prefix='range-', dir=self.cache) as directory:
            header, body = Path(directory) / 'headers', Path(directory) / 'body'
            command = [self.curl, '--fail', '--silent', '--show-error', '--location',
                       '--proto', '=https', '--proto-redir', '=https', '--connect-timeout', '15',
                       '--max-time', '120', '--range', f'{offset}-{offset + length - 1}',
                       '--max-filesize', str(length), '--header', 'Accept-Encoding: identity',
                       '--dump-header', str(header), '--output', str(body)]
            if self.etag:
                command += ['--header', 'If-Range: ' + self.etag]
            if self.resolve:
                command += ['--resolve', self.resolve]
            result = subprocess.run(command + [self.url], capture_output=True)
            require(result.returncode == 0, 'Range download failed: ' + result.stderr.decode(errors='replace').strip())
            responses = re.split(r'\r?\n\r?\n', header.read_text(encoding='iso-8859-1'))
            final = [block for block in responses if block.startswith('HTTP/')][-1]
            lines = final.splitlines()
            require(lines[0].split()[1] == '206', 'Server did not honor Range; refusing a full download')
            headers = {key.strip().lower(): value.strip() for line in lines[1:] if ':' in line
                       for key, value in [line.split(':', 1)]}
            match = re.fullmatch(r'bytes (\d+)-(\d+)/(\d+)', headers.get('content-range', ''))
            require(match is not None, 'Missing/invalid Content-Range')
            start, end, total = map(int, match.groups())
            require((start, end) == (offset, offset + length - 1), 'Wrong HTTP byte range')
            require(self.size is None or self.size == total, 'Remote ZIP size changed')
            require(self.etag is None or self.etag == headers.get('etag'), 'Remote ZIP ETag changed')
            require(headers.get('content-encoding', 'identity') == 'identity', 'Encoded HTTP response is unsupported')
            self.size, self.etag = total, headers.get('etag')
            data = body.read_bytes()
            require(len(data) == length, 'Truncated HTTP range')
            self.downloaded += length
            return data

    def read_at(self, offset, length):
        require(0 <= offset <= self.size and 0 <= length <= self.size - offset, 'Range outside ZIP')
        if not length:
            return b''
        self.used_ranges.add((offset, length))
        file = self.cache / f'{offset}-{length}.bin'
        digest = file.with_suffix('.sha256')
        # Without an ETag a fresh request is required to avoid mixing generations.
        if self.etag and file.exists() and digest.exists():
            data = file.read_bytes()
            if len(data) == length and sha(data) == digest.read_text(encoding='ascii'):
                return data
        data = self._fetch(offset, length)
        temp = file.with_suffix('.tmp')
        temp.write_bytes(data)
        temp.replace(file)
        digest.write_text(sha(data), encoding='ascii')
        return data


class LocalReader:
    def __init__(self, path):
        self.path, self.size = Path(path), Path(path).stat().st_size

    def read_at(self, offset, length):
        require(0 <= offset <= self.size and 0 <= length <= self.size - offset, 'Read outside file')
        with self.path.open('rb') as file:
            file.seek(offset)
            data = file.read(length)
        require(len(data) == length, 'Truncated local file')
        return data


class ZipReader(io.RawIOBase):
    def __init__(self, source):
        self.source, self.position = source, 0

    def seekable(self):
        return True

    def readable(self):
        return True

    def tell(self):
        return self.position

    def seek(self, offset, whence=0):
        self.position = offset + (self.position if whence == 1 else self.source.size if whence == 2 else 0)
        require(0 <= self.position <= self.source.size, 'ZIP seek outside source')
        return self.position

    def read(self, size=-1):
        size = self.source.size - self.position if size < 0 else min(size, self.source.size - self.position)
        require(size <= 64 * 1024 * 1024, 'ZIP metadata read exceeds 64 MiB')
        data = self.source.read_at(self.position, size)
        self.position += size
        return data


def protobuf(data):
    """Minimal bounded protobuf wire reader; only documented payload fields used."""
    fields, position = {}, 0

    def varint():
        nonlocal position
        result = 0
        for shift in range(0, 70, 7):
            require(position < len(data), 'Truncated protobuf varint')
            value = data[position]
            position += 1
            result |= (value & 127) << shift
            if value < 128:
                return result
        raise ValueError('Oversized protobuf varint')

    while position < len(data):
        tag = varint()
        field, wire = tag >> 3, tag & 7
        require(field > 0, 'Invalid protobuf tag')
        if wire == 0:
            value = varint()
        else:
            require(wire in (1, 2, 5), 'Unsupported protobuf wire type')
            size = varint() if wire == 2 else 8 if wire == 1 else 4
            require(size <= len(data) - position, 'Truncated protobuf field')
            value = data[position:position + size]
            position += size
        fields.setdefault(field, []).append(value)
    return fields


def one(fields, key, default=None):
    values = fields.get(key, [default])
    require(len(values) == 1, f'Duplicate scalar protobuf field {key}')
    return values[0]


class Payload:
    def __init__(self, source):
        self.source = source
        with zipfile.ZipFile(ZipReader(source)) as zip:
            matches = [item for item in zip.infolist() if item.filename == 'payload.bin']
            require(len(matches) == 1, 'ZIP must contain one payload.bin (TGZ is unsupported)')
            entry = matches[0]
            require(entry.compress_type == zipfile.ZIP_STORED and not entry.flag_bits & 1,
                    'payload.bin must be stored, unencrypted, for selective extraction')
            header = source.read_at(entry.header_offset, 30)
            require(header[:4] == b'PK\x03\x04', 'Invalid ZIP local header')
            name_size, extra_size = struct.unpack_from('<HH', header, 26)
            self.start = entry.header_offset + 30 + name_size + extra_size
            self.size = entry.file_size
        header = self.read(0, 24)
        magic, major, manifest_size, signature_size = struct.unpack('>4sQQI', header)
        require(magic == b'CrAU' and major == 2, 'Only payload format version 2 is supported')
        require(manifest_size <= 32 * 1024 * 1024 and signature_size <= 1024 * 1024, 'Oversized payload metadata')
        manifest = self.read(24, manifest_size)
        self.manifest_hash = sha(manifest)
        fields = protobuf(manifest)
        require(one(fields, 12, 0) == 0, 'Incremental OTA requires the base ROM; use a full OTA')
        self.block_size = one(fields, 3, 4096)
        require(self.block_size in (4096, 16384), 'Unsupported payload block size')
        self.blobs = 24 + manifest_size + signature_size
        self.partitions = {}
        for data in fields.get(13, []):
            part = protobuf(data)
            name = one(part, 1).decode('utf-8')
            require(name not in self.partitions, 'Duplicate payload partition')
            self.partitions[name] = part

    def read(self, offset, length):
        require(0 <= offset <= self.size and 0 <= length <= self.size - offset, 'Read outside payload')
        return self.source.read_at(self.start + offset, length)


class Partition:
    def __init__(self, payload, name, image):
        require(name in payload.partitions, 'Partition not found: ' + name)
        self.payload, self.image = payload, image
        part = payload.partitions[name]
        self.size = one(protobuf(one(part, 7)), 1)
        require(isinstance(self.size, int) and 0 < self.size <= 64 * 1024**3, 'Invalid partition size')
        self.operations = [protobuf(item) for item in part.get(8, [])]
        self.extents, self.loaded = [], set()
        for index, op in enumerate(self.operations):
            logical = 0
            for data in op.get(6, []):
                extent = protobuf(data)
                start, size = one(extent, 1) * payload.block_size, one(extent, 2) * payload.block_size
                require(size > 0 and 0 <= start <= self.size - size, 'Invalid destination extent')
                self.extents.append((start, start + size, index, logical))
                logical += size
            require(0 < logical <= 64 * 1024 * 1024, 'Oversized/empty payload operation')
            op['_decoded_size'] = logical
        self.extents.sort()
        for prior, current in zip(self.extents, self.extents[1:]):
            require(prior[1] <= current[0], 'Overlapping payload operations')
        image.parent.mkdir(parents=True, exist_ok=True)
        with image.open('wb') as output:
            if os.name == 'nt':
                # Sparse file reserves logical length, not an entire partition on disk.
                result = subprocess.run(['fsutil', 'sparse', 'setflag', str(image)], capture_output=True)
                require(result.returncode == 0, 'Cannot create sparse working image on this filesystem')
            output.truncate(self.size)

    def load(self, index):
        if index in self.loaded:
            return
        op = self.operations[index]
        kind, size = one(op, 1), op['_decoded_size']
        require(kind in (0, 1, 6, 8), f'Unsupported operation {kind}; only full REPLACE/BZ/XZ/ZERO are supported')
        if kind == 6:
            data = bytes(size)
        else:
            offset, length = one(op, 2, 0), one(op, 3, 0)
            require(0 < length <= 64 * 1024 * 1024, 'Invalid operation data length')
            encoded = self.payload.read(self.payload.blobs + offset, length)
            expected = one(op, 8)
            require(isinstance(expected, bytes) and len(expected) == 32, 'Missing operation SHA-256')
            require(hashlib.sha256(encoded).digest() == expected, 'Payload operation SHA-256 mismatch')
            if kind == 0:
                data = encoded
            else:
                decoder = bz2.BZ2Decompressor() if kind == 1 else lzma.LZMADecompressor(memlimit=256 * 1024 * 1024)
                data = decoder.decompress(encoded, max_length=size + 1)
                require(decoder.eof and not decoder.unused_data, 'Invalid/compressed operation exceeds size limit')
        require(len(data) == size, 'Operation decompressed length mismatch')
        with self.image.open('r+b') as output:
            for start, end, op_index, logical in self.extents:
                if op_index == index:
                    output.seek(start)
                    output.write(data[logical:logical + end - start])
        self.loaded.add(index)
        if len(self.loaded) % 8 == 0:
            print(f'Loaded {len(self.loaded)} payload operations', flush=True)

    def ensure(self, offset, length):
        require(0 <= offset <= self.size and 0 <= length <= self.size - offset, 'Read outside partition')
        finish, position = offset + length, offset
        for start, end, index, _ in self.extents:
            if end <= position:
                continue
            if start >= finish:
                break
            require(start <= position, 'Requested bytes have no payload operation')
            self.load(index)
            position = min(end, finish)
        require(position == finish, 'Requested bytes not covered by payload')

    def read_at(self, offset, length):
        self.ensure(offset, length)
        return LocalReader(self.image).read_at(offset, length)


class Erofs:
    def __init__(self, reader):
        self.reader = reader
        sb = reader.read_at(1024, 128)
        require(struct.unpack_from('<I', sb)[0] == 0xE0F5E1E2, 'Partition is not EROFS')
        self.block = 1 << sb[12]
        require(self.block in (4096, 16384) and sb[90] == 0, 'Unsupported EROFS directory block size')
        features = struct.unpack_from('<I', sb, 80)[0]
        require(not features & (0x80 | 0x100), '48-bit/metabox EROFS is not supported')
        require(struct.unpack_from('<H', sb, 86)[0] == 0, 'Multi-device EROFS is not supported')
        self.root = struct.unpack_from('<H', sb, 14)[0]
        self.meta = struct.unpack_from('<I', sb, 40)[0] * self.block

    def inode(self, nid):
        offset = self.meta + nid * 32
        data = self.reader.read_at(offset, 64)
        fmt, xattrs, mode = struct.unpack_from('<HHH', data)
        extended = fmt & 1
        return {'nid': nid, 'offset': offset, 'mode': mode, 'layout': (fmt >> 1) & 7,
                'size': struct.unpack_from('<Q' if extended else '<I', data, 8)[0],
                'raw': struct.unpack_from('<I', data, 16)[0] * self.block,
                'body': offset + (64 if extended else 32) + (12 + 4 * (xattrs - 1) if xattrs else 0)}

    def directory(self, inode):
        require(stat.S_ISDIR(inode['mode']), 'Path component is not a directory')
        require(inode['layout'] in (0, 2) and inode['size'] <= 16 * 1024 * 1024,
                'Unsupported/oversized EROFS directory')
        entries = {}
        for offset in range(0, inode['size'], self.block):
            count = min(self.block, inode['size'] - offset)
            physical = inode['body'] if inode['layout'] == 2 and count < self.block else inode['raw'] + offset
            data = self.reader.read_at(physical, count)
            require(len(data) >= 12, 'Truncated EROFS directory')
            first_name = struct.unpack_from('<H', data, 8)[0]
            require(first_name >= 12 and first_name % 12 == 0 and first_name <= len(data), 'Invalid directory index')
            for pos in range(0, first_name, 12):
                nid, start = struct.unpack_from('<QH', data, pos)
                end = struct.unpack_from('<H', data, pos + 20)[0] if pos + 12 < first_name else len(data)
                require(first_name <= start < end <= len(data), 'Invalid EROFS name offsets')
                name = data[start:end].split(b'\0', 1)[0].decode('utf-8')
                require(name not in entries, 'Duplicate directory entry')
                entries[name] = nid
        return entries

    def lookup(self, path):
        parts = PurePosixPath(path).parts
        require(path.startswith('/') and '..' not in parts, 'Use an absolute path inside the partition without ..')
        current = self.inode(self.root)
        for name in parts[1:]:
            entries = self.directory(current)
            require(name in entries, f'EROFS path component missing: {name}')
            current = self.inode(entries[name])
        require(stat.S_ISREG(current['mode']), 'Target must be a regular file, not a directory or symlink')
        return current


def extract(partition, path, dump, output):
    erofs = Erofs(partition)
    inode = erofs.lookup(path)
    require(inode['size'] <= 512 * 1024 * 1024, 'Target file exceeds 512 MiB')
    # Both full and compact compressed indexes are <= 8 bytes per logical block.
    # Fetch an upper bound plus alignment and the last metadata block.
    index_size = 64 + ((inode['size'] + erofs.block - 1) // erofs.block + 2) * 8
    require(inode['layout'] in (0, 1, 2, 3), 'Unsupported EROFS target layout')
    partition.ensure(inode['offset'], min(index_size + inode['body'] - inode['offset'], partition.size - inode['offset']))
    result = subprocess.run([str(dump), '-e', '--nid=' + str(inode['nid']), str(partition.image)], capture_output=True)
    require(result.returncode == 0, 'dump.erofs failed: ' + result.stderr.decode(errors='replace'))
    listing = result.stdout.decode('utf-8', errors='replace')
    ranges = re.findall(r'^\s*\d+:\s*(\d+)\.\.\s*(\d+)\s*\|\s*(\d+)\s*:\s*(\d+)\.\.\s*(\d+)\s*\|\s*(\d+)', listing, re.M)
    logical = 0
    for start, end, length, physical, physical_end, physical_length in ranges:
        start, end, length, physical, physical_end, physical_length = map(int, (start, end, length, physical, physical_end, physical_length))
        require(start == logical and end - start == length and physical_end - physical == physical_length,
                'Unexpected EROFS extent mapping')
        partition.ensure(physical, physical_length)
        logical = end
    require(logical >= inode['size'], 'EROFS extents do not cover the file (packed fragments unsupported)')
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_name(output.name + '.partial')
    try:
        with temporary.open('wb') as file:
            result = subprocess.run([str(dump), '--cat', '--nid=' + str(inode['nid']), str(partition.image)], stdout=file, stderr=subprocess.PIPE)
        require(result.returncode == 0, 'EROFS decompression failed: ' + result.stderr.decode(errors='replace'))
        require(temporary.stat().st_size == inode['size'], 'Extracted file size mismatch')
        if output.suffix.lower() in ('.jar', '.apk', '.zip'):
            with zipfile.ZipFile(temporary) as zip:
                require(zip.testzip() is None, 'Extracted ZIP/JAR CRC check failed')
        temporary.replace(output)
    finally:
        temporary.unlink(missing_ok=True)
    return {'file_size': inode['size'], 'sha256': sha(output.read_bytes()), 'inode': inode['nid']}


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('url', help='Full OTA ZIP HTTPS URL, or a local full OTA ZIP')
    parser.add_argument('--partition', default='system')
    parser.add_argument('--path', default='/system/framework/services.jar')
    parser.add_argument('--output', type=Path, default=Path('build/ota-extracted/services.jar'))
    parser.add_argument('--work', type=Path, default=Path('build/ota-extracted/work'))
    parser.add_argument('--dump-erofs', type=Path, default=Path('build/rom-tools/erofs/dump.erofs.exe') if os.name == 'nt' else Path('dump.erofs'))
    parser.add_argument('--curl', default='curl.exe' if os.name == 'nt' else 'curl')
    parser.add_argument('--resolve', help='Optional curl DNS override HOST:443:IP; TLS hostname verification remains enabled')
    parser.add_argument('--max-download-mib', type=int, default=512)
    args = parser.parse_args()
    require(re.fullmatch(r'[a-zA-Z0-9_]+', args.partition), 'Invalid partition name')
    require(args.max_download_mib > 0, 'Download budget must be positive')
    dump = shutil.which(str(args.dump_erofs)) or (str(args.dump_erofs.resolve()) if args.dump_erofs.is_file() else None)
    require(dump is not None, 'dump.erofs is required; install erofs-utils or provide --dump-erofs')
    args.work.mkdir(parents=True, exist_ok=True)
    source = HttpRanges(args.url, args.work / 'http', args.max_download_mib * 1024**2, args.curl, args.resolve) if args.url.startswith('https://') else LocalReader(args.url)
    payload = Payload(source)
    print(f'OTA ZIP: {source.size:,} bytes; partitions: {", ".join(payload.partitions)}', flush=True)
    partition = Partition(payload, args.partition, args.work / (args.partition + '.INCOMPLETE.img'))
    report = extract(partition, args.path, dump, args.output)
    report.update({'source': source.origin if isinstance(source, HttpRanges) else str(source.path),
                   'source_size': source.size, 'etag': getattr(source, 'etag', None),
                   'partition': args.partition, 'path': args.path, 'manifest_sha256': payload.manifest_hash,
                   'operations_fetched': len(partition.loaded), 'partition_operations': len(partition.operations),
                   'downloaded_bytes_this_run': getattr(source, 'downloaded', 0),
                   'selected_range_bytes': sum(length for _, length in getattr(source, 'used_ranges', set())),
                   'verification': 'Fetched operation SHA-256 and extracted file CRC; full OTA/vendor signature not verified'})
    args.output.with_suffix(args.output.suffix + '.json').write_text(json.dumps(report, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
    print(json.dumps(report, indent=2, ensure_ascii=False), flush=True)
    print('Extracted: ' + str(args.output.resolve()), flush=True)


if __name__ == '__main__':
    main()
