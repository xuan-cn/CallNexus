#!/usr/bin/env python3
"""Interactive, first-install-only configuration; never start or delete services."""
import argparse
import getpass
import ipaddress
import os
from pathlib import Path
import re
import socket
import subprocess
import sys

import configure

PASSWORD_KEYS = ('MYSQL_ROOT_PASSWORD', 'MYSQL_PASSWORD', 'REDIS_PASSWORD', 'MINIO_ROOT_PASSWORD')
EDITABLE = set(PASSWORD_KEYS + ('COMPOSE_PROJECT_NAME', 'FS_BIND_IP', 'UNIMRCP_HOST_IP',
    'FS_SIP_ADVERTISE_IP', 'FS_RTP_ADVERTISE_IP', 'SIP_DOMAIN', 'HTTP_PORT', 'MYSQL_DATABASE',
    'MYSQL_USER', 'MYSQL_BIND_ADDRESSES', 'MINIO_ROOT_USER', 'MINIO_CONSOLE_BIND_IP', 'FS_ESL_PASSWORD'))


def ask(label, default='', check=None):
    while True:
        value = input(label + (' [' + default + ']' if default else '') + ': ').strip() or default
        try:
            if not value:
                raise ValueError('不能为空')
            if check:
                check(value)
            return value
        except ValueError as error:
            print('输入无效：' + str(error))


def ipv4(value):
    address = ipaddress.IPv4Address(value)
    if address.is_unspecified or address.is_multicast or address.is_loopback or address.is_link_local:
        raise ValueError('请输入本机网卡或实际公网 IPv4，不使用回环/组播地址')


def identifier(value):
    if not re.fullmatch(r'[a-z][a-z0-9_-]{0,47}', value):
        raise ValueError('使用小写字母开头的字母、数字、下划线或横线，最多48位')


def password_value(value):
    # Keep password syntax compatible with both strict .env parsing and Compose interpolation.
    if not re.fullmatch(r'[A-Za-z0-9!@%_+=.,:/-]{8,128}', value):
        raise ValueError('密码须8至128位，使用字母、数字或 ! @ % _ + = . , : / -；不支持空格、引号、$、#、反引号')


def choice(label, default, options):
    def check(value):
        if value not in options:
            raise ValueError('请选择 ' + ' / '.join(options))
    return ask(label, default, check)


def secret(label, default):
    while True:
        first = getpass.getpass(label + '（输入隐藏；回车保留当前/自动生成值）: ')
        if not first:
            return default
        try:
            password_value(first)
        except ValueError as error:
            print(str(error))
            continue
        if first == getpass.getpass('再次输入确认: '):
            return first
        print('两次输入不一致，请重试')


def docker_output(arguments):
    result = subprocess.run(['docker'] + arguments, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, universal_newlines=True, check=True)
    return result.stdout.splitlines()


def require_empty_project(project):
    containers = docker_output(['ps', '-aq', '--filter', 'label=com.docker.compose.project=' + project])
    volumes = docker_output(['volume', 'ls', '-q', '--filter', 'label=com.docker.compose.project=' + project])
    names = docker_output(['volume', 'ls', '--format', '{{.Name}}'])
    volumes.extend(name for name in names if name.startswith(project + '_'))
    if containers or volumes:
        raise ValueError('项目 ' + project + ' 已有容器或数据卷，拒绝首次安装改密；请走升级/密码轮换流程。不会删除数据。')


def require_local_bind(bind_ip):
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.bind((bind_ip, 0))


def save_environment(env, original):
    root = configure.ROOT
    destination = root / '.env'
    if destination.is_symlink():
        raise ValueError('拒绝覆盖符号链接 .env')
    configure.validate(env)
    if original is not None:
        previous = configure.read_env(destination)
        if any(previous.get(key) != env.get(key) for key in set(previous) | set(env) if key not in EDITABLE):
            raise ValueError('检测到不可修改的已有密钥变化，拒绝保存')
        if destination.read_bytes() != original:
            raise ValueError('.env 在操作期间发生变化，拒绝覆盖')
    pending = root / ('.env.prepare-' + os.urandom(8).hex())
    try:
        with pending.open('x', encoding='utf-8', newline='\n') as stream:
            stream.write(configure.commented_env_text(env))
        pending.chmod(0o600)
        if configure.read_env(pending) != env:
            raise ValueError('配置回读不一致，拒绝保存')
        if original is None:
            # Atomic no-clobber publication; never replace a concurrently created .env.
            os.link(str(pending), str(destination))
        else:
            backups = root / 'runtime/backups'
            backups.mkdir(parents=True, exist_ok=True)
            backups.chmod(0o700)
            backup = backups / ('.env.before-prepare-' + os.urandom(8).hex())
            with backup.open('xb') as stream:
                stream.write(original)
            backup.chmod(0o600)
            os.replace(str(pending), str(destination))
    finally:
        if pending.exists():
            pending.unlink()


def main():
    parser = argparse.ArgumentParser(description='安装前交互式准备：只写配置，不启动/删除服务，不修改数据库')
    parser.add_argument('--edit-before-install', action='store_true',
                        help='只允许修改尚无容器/数据卷的已有 .env，保留全部内部密钥并备份')
    args = parser.parse_args()
    if not sys.stdin.isatty():
        raise ValueError('需要交互式终端；请直接在 SSH 终端执行，不要通过管道传密码')
    destination = configure.ROOT / '.env'
    if destination.is_symlink():
        raise ValueError('.env 不能是符号链接')
    original = destination.read_bytes() if destination.exists() else None
    if original is not None and not args.edit_before_install:
        raise ValueError('.env 已存在。未安装环境可显式使用 --edit-before-install；已部署环境不要使用本脚本改密。')
    existing = configure.read_env(destination) if original is not None else {}
    if existing:
        require_empty_project(existing.get('COMPOSE_PROJECT_NAME', 'callnexus'))
    print('只用于首次安装。不会安装 Docker、启动服务、改防火墙、改 SSH 密码或删除数据卷。')
    project = ask('Compose 项目名', existing.get('COMPOSE_PROJECT_NAME', 'callnexus'), identifier)
    require_empty_project(project)
    mode = choice('部署方式：lan 内网 / cloud 云公网', 'lan', ('lan', 'cloud'))
    bind_ip = ask('本机网卡 IPv4（云服务器填私网 IP）', existing.get('FS_BIND_IP', ''), ipv4)
    require_local_bind(bind_ip)
    public_ip = ask('云控制台公网 IPv4', '', ipv4) if mode == 'cloud' else None
    env = dict(existing) if existing else configure.initial_environment(bind_ip, public_ip)
    env.update(COMPOSE_PROJECT_NAME=project, FS_BIND_IP=bind_ip, UNIMRCP_HOST_IP=bind_ip,
               FS_SIP_ADVERTISE_IP=public_ip or bind_ip, FS_RTP_ADVERTISE_IP=public_ip or bind_ip)
    domain = ask('SIP 认证域（须与后台节点、软电话一致）', public_ip or bind_ip)
    if not re.fullmatch(r'[A-Za-z0-9.-]+', domain):
        raise ValueError('SIP 域只填 IP/域名，不带协议、路径、端口')
    env['SIP_DOMAIN'] = domain
    def port(value):
        if not value.isdigit() or not 1 <= int(value) <= 65535:
            raise ValueError('端口须为1至65535的数字')
    env['HTTP_PORT'] = ask('Web HTTP 端口', env['HTTP_PORT'], port)
    env['MYSQL_DATABASE'] = ask('MySQL 数据库名', env['MYSQL_DATABASE'], identifier)
    env['MYSQL_USER'] = ask('MySQL 业务账号', env['MYSQL_USER'], identifier)
    env['MINIO_ROOT_USER'] = ask('MinIO 管理账号/AccessKey（3至20位）', env['MINIO_ROOT_USER'], identifier)
    if not 3 <= len(env['MINIO_ROOT_USER']) <= 20:
        raise ValueError('MinIO 账号须3至20位')
    shared = choice('四项基础服务密码是否统一？正式环境建议分别设置（y/n）', 'n', ('y', 'n'))
    if shared == 'y':
        common = secret('MySQL root/业务账号、Redis、MinIO 共用密码', env['MYSQL_ROOT_PASSWORD'])
        env.update({key: common for key in PASSWORD_KEYS})
    else:
        for key in PASSWORD_KEYS:
            env[key] = secret(key, env[key])
    env['FS_ESL_PASSWORD'] = secret('FreeSWITCH ESL 密码', env['FS_ESL_PASSWORD'])
    access = choice('管理工具访问：local 仅SSH隧道 / lan 允许私网直连（local/lan）', 'local', ('local', 'lan'))
    env['MYSQL_BIND_ADDRESSES'] = '127.0.0.1' + (',' + bind_ip if access == 'lan' else '')
    env['MINIO_CONSOLE_BIND_IP'] = bind_ip if access == 'lan' else '127.0.0.1'
    configure.validate(env)
    print('项目：{0}；绑定 IP：{1}；SIP/RTP 宣告：{2}；SIP 域：{3}'.format(project, bind_ip, public_ip or bind_ip, domain))
    print('密码与内部密钥不会显示。管理工具模式：' + access)
    print('lan 模式仍须防火墙/安全组限制来源，不会自动配置网络权限。')
    if input('输入 YES 保存并生成配置，其他输入取消: ').strip() != 'YES':
        print('已取消，未写入配置。')
        return
    require_empty_project(project)
    if existing:
        require_empty_project(existing['COMPOSE_PROJECT_NAME'])
    save_environment(env, original)
    configure.render()
    print('准备完成：已保存私有 .env 并 render。尚未启动任何服务。下一步：bash scripts/install.sh')
    print('节点编码/媒体代理令牌仍须后台创建后同步；不要将整个 .env 发到聊天。')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError, EOFError, KeyboardInterrupt) as error:
        print('安装准备未完成：' + str(error), file=sys.stderr)
        sys.exit(1)
