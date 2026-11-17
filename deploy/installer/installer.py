"""One-shot operations using image-owned scripts; no Docker client or socket."""
import argparse
import importlib
import json
import os
from pathlib import Path
import runpy
import sys

LEGACY = Path(__file__).resolve().parent / 'legacy'
sys.path.insert(0, str(LEGACY))
import configure


def require_empty_project(project):
    if project == 'callnexus-install-tools':
        raise ValueError('该项目名保留给初始化工具，请选择其他业务项目名')
    if os.environ.get('CNX_INSTALLER_SNAPSHOT_READY') != '1':
        raise ValueError('缺少宿主机检查结果；请通过 installer.sh 执行，不能跳过已有容器/卷检查')
    projects = os.environ.get('CNX_INSTALLER_CONTAINER_PROJECTS', '').splitlines()
    volumes = [json.loads(line) for line in os.environ.get('CNX_INSTALLER_VOLUMES', '').splitlines() if line.strip()]
    if project in projects or any((item.get('labels') or {}).get('com.docker.compose.project') == project
                                 or item['name'].startswith(project + '_') for item in volumes):
        raise ValueError('项目已有容器或数据卷，拒绝首次初始化；不会删除数据，请走升级流程')


def mysql_identity():
    uid = int(os.environ.get('CNX_INSTALLER_MYSQL_UID', '0'))
    gid = int(os.environ.get('CNX_INSTALLER_MYSQL_GID', '-1'))
    if uid <= 0 or gid < 0:
        raise ValueError('缺少 MySQL 镜像实际 UID/GID，请通过 installer.sh sql-prepare 执行')
    return uid, gid


def frontend_check(root):
    counts = {key: 0 for key in ('CNX_FRONTEND_RSA_PUBLIC_KEY', 'CNX_FRONTEND_RSA_PRIVATE_KEY')}
    for source in (root / 'frontend/dist').rglob('*.js'):
        for key in counts:
            counts[key] += source.read_text(encoding='utf-8').count('__' + key + '__')
    if list(counts.values()) != [1, 1]:
        raise ValueError('前端必须包含公钥/私钥占位符各一处，请用 npm run build:deploy 构建；未修改运行配置')


def render():
    frontend_check(configure.ROOT)
    configure.render()


def main(argv=None, workspace=Path('/workspace')):
    parser = argparse.ArgumentParser(description='一次性安装工具；不启动/删除服务、不导入数据库、不生成商业授权')
    parser.add_argument('action', choices=['help', 'prepare', 'init', 'render', 'comment-env', 'check', 'sql-check', 'sql-prepare', 'minio-init'])
    parser.add_argument('--bind-ip')
    parser.add_argument('--public-ip')
    args = parser.parse_args(argv)
    if args.action == 'help':
        parser.print_help(); return
    root = workspace.resolve(strict=True)
    if not (root / 'compose.yaml').is_file() or not (root / '.env.example').is_file():
        raise ValueError('请挂载完整部署目录，缺少 compose.yaml 或 .env.example')
    configure.ROOT = root
    if args.action == 'init':
        if not args.bind_ip: raise ValueError('init 需要 --bind-ip，云部署可另填 --public-ip')
        project = configure.read_env(root / '.env.example').get('COMPOSE_PROJECT_NAME', 'callnexus')
        require_empty_project(project)
        frontend_check(root)
        configure.initialize(args.bind_ip, args.public_ip)
        render()
    elif args.action == 'prepare':
        frontend_check(root)
        prepare = importlib.import_module('prepare_install')
        prepare.require_empty_project = require_empty_project
        prepare.configure.render = render_original_guard
        sys.argv = ['prepare_install.py']
        prepare.main()
    elif args.action == 'render': render()
    elif args.action == 'comment-env': configure.comment_existing_env()
    elif args.action == 'check':
        configure.validate(configure.read_env(root / '.env'))
        frontend_check(root)
        sys.argv = ['preflight.py']
        runpy.run_path(str(LEGACY / 'preflight.py'), run_name='__main__')
    elif args.action in ('sql-check', 'sql-prepare'):
        database = importlib.import_module('prepare_database')
        database.ROOT = root
        database.mysql_identity = mysql_identity
        if args.action == 'sql-prepare':
            require_empty_project(configure.read_env(root / '.env')['COMPOSE_PROJECT_NAME'])
        database.prepare(check_only=args.action == 'sql-check')
    else:
        sys.argv = ['init_minio.py']
        runpy.run_path(str(LEGACY / 'init_minio.py'), run_name='__main__')


original_render = configure.render

def render_original_guard():
    frontend_check(configure.ROOT)
    original_render()


if __name__ == '__main__':
    os.umask(0o077)
    try:
        main()
    except (Exception, KeyboardInterrupt) as error:
        # Do not dump environment, credentials or remote response bodies.
        print('初始化操作未完成：' + (str(error) if isinstance(error, ValueError) else type(error).__name__), file=sys.stderr)
        sys.exit(1)
