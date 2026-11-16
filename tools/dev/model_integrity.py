"""Local model transactions and streaming integrity records; no model framework imports."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import uuid


def promote_tree(staging: Path, destination: Path):
    """Publish a validated sibling-filesystem tree, restoring old data on rename failure.

    A process/power interruption may leave .backup-* for explicit recovery; never prune
    those automatically, since they may be the only good copy.
    """
    backup = destination.with_name(destination.name + '.backup-' + uuid.uuid4().hex)
    existed = destination.exists()
    if existed:
        os.replace(destination, backup)
    try:
        os.replace(staging, destination)
    except BaseException:
        if existed:
            os.replace(backup, destination)
        raise
    if existed:
        shutil.rmtree(backup)


def sha256_file(path: Path):
    digest = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def payload_file(path: Path, root: Path):
    rel = path.relative_to(root)
    return (path.is_file() and not any(p.startswith('.') for p in rel.parts)
            and rel.as_posix() not in ('integrity.sha256', 'version.txt'))


def integrity_text(files, cache=None):
    lines = []
    for path, rel in sorted(files, key=lambda pair: pair[1]):
        if '\n' in rel or '\r' in rel or Path(rel).is_absolute() or '..' in Path(rel).parts:
            raise ValueError('Unsafe integrity path: ' + rel)
        digest = cache.digest(path) if cache else sha256_file(path)
        lines.append(f'{digest} {path.stat().st_size} {rel}\n')
    return ''.join(lines)


class DigestCache:
    """Opt-in metadata cache. Default full hashing is unchanged; not an integrity proof.

    A caller must explicitly accept metadata trust. Includes inode/device/ctime_ns,
    not merely size/mtime, to detect normal same-size replacements. --verify-full
    bypasses it. Records outside model directories cannot alter model payloads.
    """
    def __init__(self, path: Path, enabled=False):
        self.path, self.enabled = path, enabled
        try:
            self.records = json.loads(path.read_text()) if enabled else {}
        except (OSError, ValueError):
            self.records = {}
        if not isinstance(self.records, dict):
            self.records = {}

    def digest(self, path: Path):
        stat = path.stat()
        signature = [stat.st_dev, stat.st_ino, stat.st_size, stat.st_mtime_ns, stat.st_ctime_ns]
        key = str(path.resolve())
        record = self.records.get(key, {})
        if not isinstance(record, dict):
            record = {}
        # Even strict mode may reuse a digest within this process; no persisted
        # records are loaded unless the caller explicitly trusts metadata.
        if record.get('signature') == signature:
            digest = record.get('sha256', '')
            if len(digest) == 64 and all(c in '0123456789abcdef' for c in digest):
                return digest
        digest = sha256_file(path)
        self.records[key] = {'signature': signature, 'sha256': digest}
        return digest

    def save(self):
        if not self.enabled:
            return
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.path.with_name(self.path.name + '.' + uuid.uuid4().hex)
        temporary.write_text(json.dumps(self.records))
        os.replace(temporary, self.path)
