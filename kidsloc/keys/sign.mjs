// Sign an aligned APK with the existing Baan key (v1 + v2 + v3).
// usage: node sign.mjs <aligned.apk> <out.apk>
import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { createRequire } from 'node:module';

const here = dirname(fileURLToPath(import.meta.url));
const require = createRequire(import.meta.url);
const { ApkSigner, SigningKey } = require('/tmp/claude-0/sdk/signer/node_modules/apk_sign_ts');

const [,, inApk, outApk] = process.argv;
const key = readFileSync(join(here, 'key8.pem'), 'utf8');
const cert = readFileSync(join(here, 'cert.pem'), 'utf8');

const signingKey = SigningKey.fromPEM(key, cert);
const signer = new ApkSigner({ signingKey, digestAlgorithm: 'SHA-256' });

const apk = new Uint8Array(readFileSync(inApk));
const res = await signer.sign(apk);
writeFileSync(outApk, Buffer.from(res.signedApk));
console.log('signed ->', outApk, 'block', res.signatureSize, 'bytes');
