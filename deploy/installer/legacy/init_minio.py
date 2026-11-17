#!/usr/bin/env python3
"""Create missing private buckets using S3 SigV4 and the Python standard library."""
import argparse
import datetime
import hashlib
import hmac
import http.client
import re
import ssl
import sys
import time
from urllib.parse import quote, urlsplit
import xml.etree.ElementTree as ET

sys.dont_write_bytecode = True
from configure import ROOT, read_env


class S3Error(Exception):
    def __init__(self, status, code):
        self.status, self.code = status, code
        super().__init__('S3 HTTP {0}: {1}'.format(status, code))


def error_code(body):
    try:
        code = next((node.text for node in ET.fromstring(body).iter()
                     if node.tag.rsplit('}', 1)[-1] == 'Code'), None)
        if code and re.fullmatch(r'[A-Za-z0-9_-]{1,100}', code):
            return code
    except ET.ParseError:
        pass
    return 'UnspecifiedError'


class S3Client:
    def __init__(self, endpoint, access_key, secret_key, region='us-east-1', timeout=10):
        url = urlsplit(endpoint)
        if (url.scheme not in ('http', 'https') or not url.hostname or url.username or url.password
                or url.path not in ('', '/') or url.query or url.fragment):
            raise ValueError('MINIO_INTERNAL_ENDPOINT must contain only http(s) scheme, host and optional port')
        if not access_key or not secret_key:
            raise ValueError('MinIO credentials are missing from .env')
        if not re.fullmatch(r'[a-z0-9-]{1,64}', region):
            raise ValueError('Invalid MINIO_REGION')
        self.scheme, self.host, self.port = url.scheme, url.hostname, url.port
        host = '[' + self.host + ']' if ':' in self.host else self.host
        self.authority = host + (':' + str(url.port) if url.port else '')
        self.access_key, self.secret_key, self.region = access_key, secret_key, region
        self.timeout = timeout

    def connection(self):
        if self.scheme == 'https':
            return http.client.HTTPSConnection(self.host, self.port, timeout=self.timeout,
                                               context=ssl.create_default_context())
        return http.client.HTTPConnection(self.host, self.port, timeout=self.timeout)

    def request(self, method, path, body=b'', query='', signed=True):
        encoded_path = quote(path, safe='/-_.~')
        headers = {'Host': self.authority}
        if signed:
            date = datetime.datetime.utcnow().strftime('%Y%m%dT%H%M%SZ')
            day = date[:8]
            payload_hash = hashlib.sha256(body).hexdigest()
            headers.update({'X-Amz-Date': date, 'X-Amz-Content-Sha256': payload_hash})
            canonical_headers = 'host:{0}\nx-amz-content-sha256:{1}\nx-amz-date:{2}\n'.format(
                self.authority, payload_hash, date)
            signed_headers = 'host;x-amz-content-sha256;x-amz-date'
            canonical = '\n'.join((method, encoded_path, query, canonical_headers, signed_headers, payload_hash))
            scope = day + '/' + self.region + '/s3/aws4_request'
            to_sign = '\n'.join(('AWS4-HMAC-SHA256', date, scope,
                                 hashlib.sha256(canonical.encode('utf-8')).hexdigest()))
            key = ('AWS4' + self.secret_key).encode('utf-8')
            for part in (day, self.region, 's3', 'aws4_request'):
                key = hmac.new(key, part.encode('utf-8'), hashlib.sha256).digest()
            signature = hmac.new(key, to_sign.encode('utf-8'), hashlib.sha256).hexdigest()
            headers['Authorization'] = ('AWS4-HMAC-SHA256 Credential=' + self.access_key + '/' + scope
                                        + ', SignedHeaders=' + signed_headers + ', Signature=' + signature)
        connection = self.connection()
        try:
            target = encoded_path + ('?' + query if query else '')
            connection.request(method, target, body=body, headers=headers)
            response = connection.getresponse()
            return response.status, response.read(8192)
        finally:
            connection.close()


def wait_ready(client, seconds):
    deadline = time.monotonic() + seconds
    while True:
        try:
            status, _ = client.request('GET', '/minio/health/ready', signed=False)
            if status == 200:
                return
        except (OSError, http.client.HTTPException):
            pass
        if time.monotonic() >= deadline:
            raise ValueError('MinIO is not ready; check its service, endpoint and TLS certificate')
        time.sleep(min(1, max(0, deadline - time.monotonic())))


def load_buckets(path):
    buckets = [line.strip() for line in path.read_text(encoding='utf-8').splitlines()
               if line.strip() and not line.lstrip().startswith('#')]
    if not buckets or len(set(buckets)) != len(buckets):
        raise ValueError('Bucket list is empty or contains duplicates')
    for bucket in buckets:
        if (not re.fullmatch(r'[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]', bucket) or '..' in bucket
                or re.fullmatch(r'[0-9]+(?:\.[0-9]+){3}', bucket)):
            raise ValueError('Invalid bucket name: ' + bucket)
    return buckets


def ensure_buckets(client, buckets):
    created = 0
    for bucket in buckets:
        status, body = client.request('HEAD', '/' + bucket)
        if status == 200:
            print('Exists: ' + bucket)
            continue
        if status != 404:
            raise S3Error(status, error_code(body))
        body = b''
        if client.region != 'us-east-1':
            body = ('<CreateBucketConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">'
                    '<LocationConstraint>' + client.region + '</LocationConstraint></CreateBucketConfiguration>').encode('utf-8')
        status, response = client.request('PUT', '/' + bucket, body)
        if status not in (200, 201, 204):
            if status != 409 or error_code(response) != 'BucketAlreadyOwnedByYou':
                raise S3Error(status, error_code(response))
        check, response = client.request('HEAD', '/' + bucket)
        if check != 200:
            raise S3Error(check, error_code(response))
        created += 1
        print('Ready: ' + bucket)
    print('MinIO buckets ready: {0}; created or concurrently created: {1}. Existing objects and policies unchanged.'.format(len(buckets), created))
    return created


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--wait-seconds', type=int, default=60)
    args = parser.parse_args()
    if args.wait_seconds < 0 or args.wait_seconds > 600:
        raise ValueError('--wait-seconds must be between 0 and 600')
    env = read_env(ROOT / '.env')
    client = S3Client(env.get('MINIO_INTERNAL_ENDPOINT', 'http://127.0.0.1:9000'),
                      env.get('MINIO_ROOT_USER', ''), env.get('MINIO_ROOT_PASSWORD', ''),
                      env.get('MINIO_REGION', 'us-east-1'))
    buckets = load_buckets(ROOT / 'minio/buckets.txt')
    wait_ready(client, args.wait_seconds)
    ensure_buckets(client, buckets)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, http.client.HTTPException, S3Error) as error:
        print('MinIO bucket initialization failed: ' + str(error), file=sys.stderr)
        sys.exit(1)
