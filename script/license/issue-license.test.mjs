import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createHash, createPublicKey, generateKeyPairSync, randomUUID, sign, verify } from 'node:crypto';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';

const script = fileURLToPath(new URL('./issue-license.mjs', import.meta.url));

test('signs for the request identity and rejects altered requests and existing output files', () => {
  const root = mkdtempSync(join(tmpdir(), 'callnexus-license-'));
  try {
    const paths = ['issuer.pem', 'request.json', 'terms.json', 'out.lic'].map(file => join(root, file));
    const [issuerPath, requestPath, termsPath, outputPath] = paths;
    const call = (...args) => execFileSync(process.execPath, [script, ...args], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
    const issuer = Buffer.from(call('keygen', issuerPath).trim(), 'base64');
    assert.equal(call('public-key', issuerPath).trim(), issuer.toString('base64'));
    const { privateKey, publicKey } = generateKeyPairSync('ed25519');
    const der = publicKey.export({ type: 'spki', format: 'der' });
    const deploymentId = randomUUID();
    const fingerprint = createHash('sha256').update(der).digest('hex');
    const payload = JSON.stringify({ version: 1, product: 'CALLNEXUS', algorithm: 'Ed25519', deploymentId,
      publicKey: der.toString('base64'), publicKeyFingerprint: fingerprint });
    const signature = sign(null, Buffer.from(payload), privateKey).toString('base64');
    writeFileSync(requestPath, JSON.stringify({ payload, signature }));
    writeFileSync(termsPath, JSON.stringify({ expiresAt: '2099-01-01T00:00:00Z', features: { ai_voice: true }, limits: { seats: -1 } }));
    call('issue', requestPath, termsPath, issuerPath, outputPath);
    const raw = readFileSync(outputPath, 'utf8');
    const result = JSON.parse(raw);
    assert.ok(verify(null, Buffer.from(result.payload), createPublicKey({ key: issuer, type: 'spki', format: 'der' }), Buffer.from(result.signature, 'base64')));
    const issued = JSON.parse(result.payload);
    assert.equal(issued.deploymentId, deploymentId);
    assert.equal(issued.publicKeyFingerprint, fingerprint);
    assert.equal(issued.limits.seats, -1);
    assert.throws(() => call('issue', requestPath, termsPath, issuerPath, outputPath), /EEXIST/);
    assert.equal(readFileSync(outputPath, 'utf8'), raw);
    writeFileSync(requestPath, JSON.stringify({ payload: payload.replace(deploymentId, randomUUID()), signature }));
    assert.throws(() => call('issue', requestPath, termsPath, issuerPath, outputPath), /Invalid request signature/);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
