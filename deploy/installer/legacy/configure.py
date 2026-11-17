#!/usr/bin/env python3
"""Configure this single-host release without evaluating .env as shell code."""
import argparse
import base64
import gzip
import ipaddress
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from xml.sax.saxutils import escape

ROOT = Path(__file__).resolve().parent.parent
os.umask(0o077)


def read_env(path):
    result = {}
    for line in path.read_text(encoding='utf-8').splitlines():
        if not line.strip() or line.lstrip().startswith('#'):
            continue
        key, sep, value = line.partition('=')
        if not sep or not re.fullmatch(r'[A-Z][A-Z0-9_]*', key):
            raise ValueError('Invalid .env key; use plain KEY=value lines')
        if key in result or any(c in value for c in ('\x00', '\r', '\n', '"', "'", '$', '`')):
            raise ValueError('Duplicate key or unsupported .env quoting/interpolation: ' + key)
        result[key] = value
    return result


def openssl(*args, data=None):
    return subprocess.run(['openssl'] + list(args), input=data, stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE, check=True).stdout


def rsa_pair():
    pem = openssl('genpkey', '-algorithm', 'RSA', '-pkeyopt', 'rsa_keygen_bits:2048')
    private = openssl('pkcs8', '-topk8', '-nocrypt', '-outform', 'DER', data=pem)
    public = openssl('pkey', '-pubout', '-outform', 'DER', data=pem)
    return base64.b64encode(private).decode('ascii'), base64.b64encode(public).decode('ascii')


def commented_env_text(env):
    lines, included = [], set()
    for line in (ROOT / '.env.example').read_text(encoding='utf-8').splitlines():
        if not line.strip() or line.lstrip().startswith('#'):
            lines.append(line)
            continue
        key = line.partition('=')[0]
        if key in env:
            lines.append(key + '=' + env[key])
            included.add(key)
    extra = [key for key in env if key not in included]
    if extra:
        lines.extend(['', '# 自定义扩展配置：原值保留'])
        lines.extend(key + '=' + env[key] for key in extra)
    return '\n'.join(lines) + '\n'


def comment_existing_env():
    path = ROOT / '.env'
    original = path.read_bytes()
    env = read_env(path)
    validate(env)
    suffix = os.urandom(8).hex()
    pending = ROOT / ('.env.comments-' + suffix)
    try:
        with pending.open('x', encoding='utf-8', newline='\n') as output:
            output.write(commented_env_text(env))
        pending.chmod(0o600)
        if read_env(pending) != env:
            raise ValueError('Comment update would change environment values; refusing')
        backup_dir = ROOT / 'runtime/backups'
        backup_dir.mkdir(parents=True, exist_ok=True)
        backup_dir.chmod(0o700)
        backup = backup_dir / ('.env.before-comments-' + suffix)
        with backup.open('xb') as output:
            output.write(original)
        backup.chmod(0o600)
        os.replace(str(pending), str(path))
    finally:
        if pending.exists(): pending.unlink()
    print('Updated .env with Chinese comments; all values unchanged. Private backup saved in runtime/backups. No services started.')


def initial_environment(bind_ip, public_ip):
    ipaddress.IPv4Address(bind_ip)
    if public_ip:
        ipaddress.IPv4Address(public_ip)
    if not shutil.which('openssl'):
        raise ValueError('OpenSSL is required to generate fresh RSA key pairs')
    env = read_env(ROOT / '.env.example')
    for key in ('FS_BIND_IP', 'UNIMRCP_HOST_IP'):
        env[key] = bind_ip
    env['MINIO_CONSOLE_BIND_IP'] = bind_ip
    for key in ('FS_SIP_ADVERTISE_IP', 'FS_RTP_ADVERTISE_IP', 'SIP_DOMAIN'):
        env[key] = public_ip or bind_ip
    for key, value in list(env.items()):
        if value == 'GENERATE':
            env[key] = os.urandom(16 if key == 'CALLNEXUS_DATA_ENCRYPT_KEY' else 24).hex()
    request_private, request_public = rsa_pair()
    response_private, response_public = rsa_pair()
    env.update(CNX_API_REQUEST_PRIVATE_KEY=request_private, CNX_FRONTEND_RSA_PUBLIC_KEY=request_public,
               CNX_API_RESPONSE_PUBLIC_KEY=response_public, CNX_FRONTEND_RSA_PRIVATE_KEY=response_private)
    return env


def initialize(bind_ip, public_ip):
    path = ROOT / '.env'
    if path.exists():
        raise ValueError('.env already exists; refusing to rotate existing credentials')
    env = initial_environment(bind_ip, public_ip)
    with path.open('x', encoding='utf-8', newline='\n') as output:
        output.write(commented_env_text(env))
    path.chmod(0o600)
    print('Created .env with fresh credentials. No services were started.')


def validate(env):
    required = read_env(ROOT / '.env.example')
    for optional in ('MYSQL_BIND_ADDRESSES', 'MINIO_INTERNAL_ENDPOINT', 'MINIO_REGION', 'MINIO_CONSOLE_BIND_IP'):
        required.pop(optional, None)
    for key in required:
        if not env.get(key) or env[key] in ('GENERATE', 'CHANGE_ME'):
            raise ValueError('Set a valid value for ' + key)
    for key in ('FS_BIND_IP', 'UNIMRCP_HOST_IP', 'FS_SIP_ADVERTISE_IP', 'FS_RTP_ADVERTISE_IP'):
        ipaddress.IPv4Address(env[key])
    ipaddress.IPv4Address(env.get('MINIO_CONSOLE_BIND_IP', env['FS_BIND_IP']))
    mysql_addresses = env.get('MYSQL_BIND_ADDRESSES', '127.0.0.1').split(',')
    if len(set(mysql_addresses)) != len(mysql_addresses):
        raise ValueError('MYSQL_BIND_ADDRESSES contains duplicate addresses')
    for address in mysql_addresses:
        parsed = ipaddress.ip_address(address)
        if len(mysql_addresses) > 1 and parsed.is_unspecified:
            raise ValueError('MySQL cannot combine wildcard and specific bind addresses')
    for key in ('FS_LAN_CIDR', 'FS_ICE_CANDIDATE_CIDR'):
        ipaddress.ip_network(env[key], strict=False)
    for key in ('COMPOSE_PROJECT_NAME', 'MYSQL_DATABASE', 'MYSQL_USER'):
        if not re.fullmatch(r'[a-z][a-z0-9_-]*', env[key]):
            raise ValueError('Use lowercase letters, digits, underscore or dash for ' + key)
    ranges = []
    for prefix in ('FS_RTP', 'FS_MRCP_RTP', 'UNIMRCP_RTP'):
        low, high = int(env[prefix + '_MIN']), int(env[prefix + '_MAX'])
        if not 1024 <= low < high <= 65535:
            raise ValueError('Invalid RTP range: ' + prefix)
        ranges.append((low, high))
    if any(max(a[0], b[0]) <= min(a[1], b[1]) for i, a in enumerate(ranges) for b in ranges[i + 1:]):
        raise ValueError('RTP port ranges must not overlap')
    for key in ('HTTP_PORT', 'FS_INTERNAL_SIP_PORT', 'FS_EXTERNAL_SIP_PORT'):
        if not 1 <= int(env[key]) <= 65535:
            raise ValueError('Invalid port: ' + key)
    configurable = [int(env[key]) for key in ('HTTP_PORT', 'FS_INTERNAL_SIP_PORT', 'FS_EXTERNAL_SIP_PORT')]
    reserved = {3306, 33060, 6379, 9000, 9001, 6333, 6334, 8080, 8800, 9090, 17888, 28080, 8021,
                8060, 1544, 1554, 5090, 7443, 5061, 5081}
    if len(set(configurable)) != 3 or reserved.intersection(configurable):
        raise ValueError('HTTP/SIP ports overlap each other or a fixed service port')
    if any(low <= port <= high for low, high in ranges for port in configurable[1:] + [5090, 8060]):
        raise ValueError('RTP range overlaps a service signaling port')
    if len(env['CALLNEXUS_DATA_ENCRYPT_KEY'].encode('utf-8')) not in (16, 24, 32):
        raise ValueError('Data encryption key must be 16, 24 or 32 bytes')


def render():
    env = read_env(ROOT / '.env')
    validate(env)
    for source in (ROOT / 'templates').rglob('*'):
        if not source.is_file():
            continue
        text = source.read_text(encoding='utf-8')
        def replace(match):
            key = match.group(1)
            if key not in env:
                raise ValueError('Missing template variable: ' + key)
            return escape(env[key], {'"': '&quot;', "'": '&apos;'}) if source.suffix == '.xml' else env[key]
        text = re.sub(r'__([A-Z][A-Z0-9_]+)__', replace, text)
        if source.suffix == '.xml':
            ET.fromstring(text)
        target = ROOT / 'runtime/config' / source.relative_to(ROOT / 'templates')
        target.parent.mkdir(parents=True, exist_ok=True)
        with target.open('w', encoding='utf-8', newline='\n') as output:
            output.write(text)
        target.chmod(0o600)
    hits = 0
    for source in (ROOT / 'frontend/dist').rglob('*'):
        if not source.is_file():
            continue
        # Rebuild gzip from rendered JS; never copy pre-injection compressed code.
        if source.name.endswith(('.js.gz', '.js.br')):
            continue
        target = ROOT / 'runtime/frontend' / source.relative_to(ROOT / 'frontend/dist')
        target.parent.mkdir(parents=True, exist_ok=True)
        if source.suffix == '.js':
            text = source.read_text(encoding='utf-8')
            for key in ('CNX_FRONTEND_RSA_PUBLIC_KEY', 'CNX_FRONTEND_RSA_PRIVATE_KEY'):
                hits += text.count('__' + key + '__')
                text = text.replace('__' + key + '__', env[key])
            with target.open('w', encoding='utf-8', newline='\n') as output:
                output.write(text)
            with gzip.open(str(target) + '.gz', 'wb') as compressed:
                compressed.write(text.encode('utf-8'))
        else:
            shutil.copyfile(source, target)
        target.chmod(0o644)
    if hits != 2:
        raise ValueError('Expected exactly two frontend RSA placeholders, got ' + str(hits))
    # Nginx workers need to traverse frontend directories, not the private config tree.
    for directory in [ROOT / 'runtime/frontend'] + list((ROOT / 'runtime/frontend').rglob('*')):
        if directory.is_dir(): directory.chmod(0o755)
    for name in ('server', 'snailjob', 'monitor', 'freeswitch', 'unimrcp', 'nginx'):
        (ROOT / 'runtime/logs' / name).mkdir(parents=True, exist_ok=True)
    (ROOT / 'runtime/temp').mkdir(parents=True, exist_ok=True)
    for script in list((ROOT / 'freeswitch').glob('*.sh')) + [ROOT / 'freeswitch/bin/curl']:
        script.chmod(0o755)
    print('Rendered and parsed all XML templates; synchronized frontend/backend keys. No services started.')


def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest='action')
    init = sub.add_parser('init')
    init.add_argument('--bind-ip', required=True)
    init.add_argument('--public-ip')
    sub.add_parser('render')
    sub.add_parser('comment-env')
    args = parser.parse_args()
    if args.action is None:
        parser.error('choose an action: init, render or comment-env')
    if args.action == 'init': initialize(args.bind_ip, args.public_ip)
    elif args.action == 'comment-env': comment_existing_env()
    else: render()


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        print('Configuration failed: ' + str(error), file=sys.stderr)
        sys.exit(1)
