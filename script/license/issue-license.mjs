import { createHash, createPrivateKey, createPublicKey, generateKeyPairSync, randomUUID, sign, verify } from 'node:crypto';
import { readFileSync, statSync, writeFileSync } from 'node:fs';

const maxBytes = 32 * 1024;
const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const timestampPattern = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,3})?Z$/;
const codePattern = /^[a-z][a-z0-9_]{0,63}$/;

function isFutureUtc(value, issuedAt) {
  if (typeof value !== 'string' || !timestampPattern.test(value) || Date.parse(value) <= Date.parse(issuedAt)
    || !Number.isFinite(Date.parse(value))) return false;
  const canonical = value.replace(/(?:\.(\d{1,3}))?Z$/, (_, fraction = '') => `.${fraction.padEnd(3, '0')}Z`);
  return new Date(value).toISOString() === canonical;
}

function readJson(path) {
  if (statSync(path).size > maxBytes) throw new Error('JSON file exceeds 32 KiB');
  return JSON.parse(readFileSync(path, 'utf8'));
}

function issuerPrivateKey(path) {
  if (process.platform !== 'win32' && (statSync(path).mode & 0o077) !== 0) {
    throw new Error('Issuer private key must not be accessible to group or other users');
  }
  const key = createPrivateKey(readFileSync(path));
  if (key.asymmetricKeyType !== 'ed25519') throw new Error('Expected an Ed25519 issuer key');
  return key;
}

function keyText(publicKey) {
  return publicKey.export({ type: 'spki', format: 'der' }).toString('base64');
}

function readRequest(path) {
  const { payload, signature } = readJson(path);
  if (typeof payload !== 'string' || typeof signature !== 'string') throw new Error('Invalid request envelope');
  const request = JSON.parse(payload);
  if (request.version !== 1 || request.product !== 'CALLNEXUS' || request.algorithm !== 'Ed25519'
    || typeof request.deploymentId !== 'string' || !uuidPattern.test(request.deploymentId) || typeof request.publicKey !== 'string') {
    throw new Error('Invalid request identity');
  }
  const der = Buffer.from(request.publicKey, 'base64');
  const key = createPublicKey({ key: der, type: 'spki', format: 'der' });
  const fingerprint = createHash('sha256').update(der).digest('hex');
  const signed = Buffer.from(signature, 'base64');
  if (key.asymmetricKeyType !== 'ed25519' || keyText(key) !== request.publicKey || fingerprint !== request.publicKeyFingerprint
    || signed.length !== 64 || !verify(null, Buffer.from(payload, 'utf8'), key, signed)) {
    throw new Error('Invalid request signature or public key fingerprint');
  }
  return request;
}

function validateEntries(entries, validValue, name) {
  if (!entries || typeof entries !== 'object' || Array.isArray(entries)) throw new Error(`Invalid ${name}`);
  const items = Object.entries(entries);
  if (items.length > 64 || items.some(([key, value]) => !codePattern.test(key) || !validValue(value))) {
    throw new Error(`Invalid ${name} entry`);
  }
  return entries;
}

function issue(requestPath, termsPath, privatePath, outputPath) {
  const request = readRequest(requestPath);
  const terms = readJson(termsPath);
  const issuedAt = new Date().toISOString();
  const expiresAt = terms.expiresAt;
  if (expiresAt !== null && !isFutureUtc(expiresAt, issuedAt)) {
    throw new Error('expiresAt must be a future UTC timestamp or null for permanent authorization');
  }
  const features = validateEntries(terms.features, value => typeof value === 'boolean', 'features');
  const limits = validateEntries(terms.limits, value => Number.isInteger(value) && value >= -1 && value <= 2147483647, 'limits');
  const licenseId = randomUUID();
  const payload = JSON.stringify({ version: 1, product: 'CALLNEXUS', deploymentId: request.deploymentId,
    publicKeyFingerprint: request.publicKeyFingerprint, licenseId, issuedAt, expiresAt, features, limits });
  const signature = sign(null, Buffer.from(payload, 'utf8'), issuerPrivateKey(privatePath)).toString('base64');
  const result = `${JSON.stringify({ payload, signature }, null, 2)}\n`;
  if (Buffer.byteLength(result) > maxBytes) throw new Error('Generated license exceeds 32 KiB');
  writeFileSync(outputPath, result, { flag: 'wx', mode: 0o600 });
  console.log(`Issued ${licenseId} for ${request.deploymentId}`);
}

try {
  const [command, ...args] = process.argv.slice(2);
  if (command === 'keygen' && args.length === 1) {
    const { privateKey, publicKey } = generateKeyPairSync('ed25519');
    writeFileSync(args[0], privateKey.export({ type: 'pkcs8', format: 'pem' }), { flag: 'wx', mode: 0o600 });
    console.log(keyText(publicKey));
  } else if (command === 'public-key' && args.length === 1) {
    console.log(keyText(createPublicKey(issuerPrivateKey(args[0]))));
  } else if (command === 'issue' && args.length === 4) {
    issue(...args);
  } else {
    throw new Error('Usage: issue-license.mjs keygen <issuer.pem> | public-key <issuer.pem> | issue <request.json> <terms.json> <issuer.pem> <out.lic>');
  }
} catch (error) {
  console.error(error instanceof SyntaxError ? 'Invalid JSON' : error.message);
  process.exitCode = 1;
}
