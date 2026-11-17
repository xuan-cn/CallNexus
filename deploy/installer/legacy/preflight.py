#!/usr/bin/env python3
import os
import socket
import sys
from configure import ROOT, read_env, validate

env = read_env(ROOT / '.env')
validate(env)
if sys.platform != 'linux':
    sys.exit('Installation requires Linux; configuration rendering can be tested elsewhere.')
if os.geteuid() != 0:
    sys.exit('Run first installation as root to check privileged SIP/HTTP ports.')
for key in ('FS_BIND_IP', 'UNIMRCP_HOST_IP'):
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        try:
            sock.bind((env[key], 0))
        except OSError:
            sys.exit(key + ' is not an address on this host; use a local/private bind address, not the cloud public IP.')
fixed = {int(env['HTTP_PORT']), int(env['FS_INTERNAL_SIP_PORT']), int(env['FS_EXTERNAL_SIP_PORT']),
         3306, 33060, 6379, 9000, 9001, 6333, 6334, 8080, 8800, 9090, 17888, 28080, 8021,
         8060, 1544, 1554, 5090, 7443, 5061, 5081}
if len({int(env['HTTP_PORT']), int(env['FS_INTERNAL_SIP_PORT']), int(env['FS_EXTERNAL_SIP_PORT'])}) != 3:
    sys.exit('HTTP and SIP ports must be different.')
failed = set()
for port in sorted(fixed):
    for kind in (socket.SOCK_STREAM, socket.SOCK_DGRAM):
        with socket.socket(socket.AF_INET, kind) as sock:
            try:
                sock.bind(('0.0.0.0', port))
            except OSError:
                failed.add(port)
for prefix in ('FS_RTP', 'FS_MRCP_RTP', 'UNIMRCP_RTP'):
    for port in range(int(env[prefix + '_MIN']), int(env[prefix + '_MAX']) + 1):
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            try:
                sock.bind(('0.0.0.0', port))
            except OSError:
                failed.add(port)
if failed:
    sys.exit('Ports already in use or unavailable: ' + ','.join(map(str, sorted(failed))))
print('Single-host port preflight passed. No services were started.')
