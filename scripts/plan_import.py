#!/usr/bin/env python3
"""Inventory an Office/media export and write bounded Batch Import CSV manifests. Never writes to XWiki."""
import argparse
import csv
import hashlib
import json
from collections import Counter
from pathlib import Path

MIB = 1024 * 1024
OFFICE = {'.doc', '.docx', '.odt', '.rtf', '.xls', '.xlsx', '.ods', '.ppt', '.pptx', '.odp'}
VIDEO = {'.mp4', '.webm', '.mov', '.mkv', '.avi', '.m4v'}
IMAGE = {'.png', '.jpg', '.jpeg', '.gif', '.webp', '.svg'}


def sha256_file(path):
    digest = hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(MIB), b''):
            digest.update(chunk)
    return digest.hexdigest()


def wiki_title(value):
    value = value.replace('\r', ' ').replace('\n', ' ').replace('~', '~~')
    for character in '{}[]<>|':
        value = value.replace(character, '~' + character)
    if value.startswith(('=', '+', '-', '@')):
        value = '~' + value
    return value


def scan(root, checksum=False):
    rows = []
    skipped = []
    for path in sorted(root.rglob('*')):
        if path.is_symlink():
            skipped.append(path.relative_to(root).as_posix())
            continue
        if not path.is_file() or path.name in {'.DS_Store', 'Thumbs.db'} or path.name.startswith('._'):
            continue
        relative = path.relative_to(root).as_posix()
        extension = path.suffix.lower()
        kind = 'office' if extension in OFFICE else 'video' if extension in VIDEO else 'image' if extension in IMAGE else 'other'
        stat = path.stat()
        rows.append({'doc.name': 'import-' + hashlib.sha256(relative.encode()).hexdigest()[:20],
                     'doc.title': wiki_title(path.stem), 'doc.file': './' + relative,
                     'primaryCategory': '' if path.parent == root else path.parent.relative_to(root).as_posix(),
                     'kind': kind, 'size_bytes': stat.st_size,
                     'sha256': sha256_file(path) if checksum else ''})
        if checksum and (path.stat().st_size != stat.st_size or path.stat().st_mtime_ns != stat.st_mtime_ns):
            raise RuntimeError(f'Source changed while hashing: {relative}')
    return rows, skipped


def batches(rows, max_bytes):
    result = []
    for kind in ('office', 'video', 'image', 'other'):
        current, size = [], 0
        for row in (row for row in rows if row['kind'] == kind):
            if current and size + row['size_bytes'] > max_bytes:
                result.append((kind, current)); current, size = [], 0
            current.append(row); size += row['size_bytes']
        if current:
            result.append((kind, current))
    return result


def plan(source, output, batch_mib=1024, checksum=False, max_file_mib=None):
    root = Path(source).expanduser().resolve(strict=True)
    output = Path(output).expanduser().resolve()
    if not root.is_dir():
        raise ValueError('Source must be an exported file directory.')
    if output == root or root in output.parents:
        raise ValueError('Place the report outside the source directory so it cannot import itself.')
    if batch_mib <= 0 or (max_file_mib is not None and max_file_mib <= 0):
        raise ValueError('Size limits must be positive.')
    if output.exists():
        raise ValueError('Report directory already exists; choose a new output directory. Source files are never modified.')
    rows, skipped = scan(root, checksum)
    output.mkdir(parents=True)
    counts = Counter()
    csv_files = []
    for kind, group in batches(rows, batch_mib * MIB):
        counts[kind] += 1
        name = f'{kind}-{counts[kind]:03d}.csv'
        with (output / name).open('w', encoding='utf-8', newline='') as target:
            writer = csv.DictWriter(target, fieldnames=list(group[0]))
            writer.writeheader(); writer.writerows(group)
        csv_files.append({'file': name, 'kind': kind, 'files': len(group), 'bytes': sum(row['size_bytes'] for row in group)})
    summary = {'source': str(root), 'files': len(rows), 'total_bytes': sum(row['size_bytes'] for row in rows),
               'by_kind': dict(Counter(row['kind'] for row in rows)),
               'largest_files': sorted(rows, key=lambda row: row['size_bytes'], reverse=True)[:20],
               'oversized_files': [row['doc.file'] for row in rows
                                   if max_file_mib is not None and row['size_bytes'] > max_file_mib * MIB],
               'skipped_symlinks': skipped, 'checksums_computed': checksum, 'batches': csv_files,
               'notes': ['Read-only inventory; no upload, conversion or XWiki changes were performed.',
                         'CSV sizes group source bytes, not CSV file sizes; a large single file is not split.',
                         'Office conversion needs a working Office Importer/office server.',
                         'Batch Import data directory must be mounted on the XWiki server.']}
    (output / 'inventory.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n')
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', help='Exported Word/media directory')
    parser.add_argument('--output', required=True, help='New report directory outside the source')
    parser.add_argument('--batch-mib', type=int, default=1024)
    parser.add_argument('--max-file-mib', type=int, help='Flag files exceeding the actual server attachment limit')
    parser.add_argument('--sha256', action='store_true', help='Read source files in 1 MiB blocks to compute checksums')
    args = parser.parse_args()
    try:
        summary = plan(args.source, args.output, args.batch_mib, args.sha256, args.max_file_mib)
    except (OSError, ValueError, RuntimeError) as error:
        parser.exit(1, f'ERROR: {error}\n')
    print(f"Files: {summary['files']}; total: {summary['total_bytes'] / (1024 ** 3):.2f} GiB; batches: {len(summary['batches'])}")
    print(f"Report: {Path(args.output).resolve() / 'inventory.json'}")
    print('No files were uploaded or converted; source files are unchanged.')


if __name__ == '__main__':
    main()
