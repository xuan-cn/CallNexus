#!/usr/bin/env python3
"""Prepare reviewed first-install SQL; never connect to or modify a database."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

from configure import ROOT, read_env


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def mysql_identity():
    if not sys.platform.startswith('linux') or os.geteuid() != 0:
        raise ValueError('Run SQL preparation as root on the Linux Docker host')
    identity = []
    for option in ('-u', '-g'):
        result = subprocess.run(['docker', 'run', '--rm', '--network=none', '--entrypoint', 'id',
                                 'callnexus-mysql-service:v8.0', option, 'mysql'],
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True,
                                universal_newlines=True)
        identity.append(int(result.stdout.strip()))
    if identity[0] <= 0 or identity[1] < 0:
        raise ValueError('Expected a non-root mysql user in the release image')
    return tuple(identity)


def secure_sql_files(directory, uid, gid):
    # The entrypoint reads SQL after switching to mysql, not as container root.
    os.chown(str(directory), uid, gid)
    directory.chmod(0o700)
    for path in directory.iterdir():
        os.chown(str(path), uid, gid)
        path.chmod(0o400)


def prepare(check_only=False):
    reviewed = ROOT / 'database/reviewed'
    review = json.loads((reviewed / 'review.json').read_text(encoding='utf-8'))
    source = ROOT / 'database/init/001-schema.sql.sql'
    template_path = reviewed / '001-schema.sql.template'
    migration_path = reviewed / '010-fix-retry-rule.sql'
    inputs = [(template_path, review['template_sha256']), (migration_path, review['migration_sha256'])]
    # The private source dump is retained locally, but is not distributed.
    # If supplied for review, it must still match the reviewed source exactly.
    if source.exists():
        inputs.append((source, review['source_sql_sha256']))
    if not source.exists() and any((ROOT / 'database/init').glob('*.sql')):
        raise ValueError('Custom initialization SQL requires a new review before preparation')
    for path, expected in inputs:
        if sha(path) != expected:
            raise ValueError('SQL input changed after review: ' + str(path.relative_to(ROOT)))
    env = read_env(ROOT / '.env')
    token = env.get('CNX_SNAIL_TOKEN', '')
    if not re.fullmatch(r'[A-Za-z0-9_-]{16,64}', token):
        raise ValueError('CNX_SNAIL_TOKEN must be 16-64 letters, digits, underscores or dashes')
    template = template_path.read_text(encoding='utf-8')
    if template.count('__CNX_SNAIL_TOKEN__') != review['scheduler_tokens_parameterized']:
        raise ValueError('Scheduler token placeholder count changed')
    if re.search(r'(?im)^\s*INSERT\s+INTO\s+`?(sys_logininfor|sys_oss_config)`?\b', template):
        raise ValueError('Old log or OSS seed was not removed')
    if check_only:
        print('Reviewed SQL inputs and token format verified. No files or database were changed.')
        return
    destination = ROOT / 'runtime/database/init'
    if destination.exists() and any(p.name not in ('001-schema.sql', '010-fix-retry-rule.sql') for p in destination.iterdir()):
        raise ValueError('Unexpected file in generated SQL directory; review it before continuing')
    uid, gid = mysql_identity()
    os.umask(0o077)
    destination.mkdir(parents=True, exist_ok=True)
    with (destination / '001-schema.sql').open('w', encoding='utf-8', newline='\n') as stream:
        stream.write(template.replace('__CNX_SNAIL_TOKEN__', token))
    (destination / '010-fix-retry-rule.sql').write_bytes(migration_path.read_bytes())
    secure_sql_files(destination, uid, gid)
    print('Prepared 2 SQL files in runtime/database/init; original SQL unchanged. No database was started or modified.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--check-only', action='store_true')
    args = parser.parse_args()
    try:
        prepare(args.check_only)
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        print('Database preparation failed: ' + str(error), file=sys.stderr)
        sys.exit(1)
