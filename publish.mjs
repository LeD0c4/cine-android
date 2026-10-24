// Publie une nouvelle version de Selvania Ciné dans le dépôt de mises à jour (gradle.properties :
// updateFeed). Les applis installées la proposent ensuite toutes seules.
//
//   node publish.mjs "Ce qui change dans cette version"
//
// Avant : augmenter versionCode et versionName dans cine/build.gradle.kts.
// Se lance sur le PC qui a la clé de signature (selvania.keystore + keystore.properties) : une mise
// à jour doit être signée avec la même clé que l'appli installée. Il faut aussi `gh` connecté au
// compte GitHub du dépôt de mises à jour.
import { execFileSync, execSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const cine = path.resolve(here, '..', 'cine-web');
const webos = path.resolve(here, '..', 'cine-webos');
const run = (cmd, cwd = here) => execSync(cmd, { cwd, stdio: 'inherit' });
const fail = (msg) => {
  console.error(`\n✗ ${msg}`);
  process.exit(1);
};

const gradle = fs.readFileSync(path.join(here, 'cine', 'build.gradle.kts'), 'utf8');
const version = gradle.match(/versionName = "([^"]+)"/)?.[1];
const feed = fs.readFileSync(path.join(here, 'gradle.properties'), 'utf8').match(/^updateFeed=(.*)$/m)?.[1]?.trim();
const notes = process.argv[2] ?? `Selvania Ciné ${version}`;
if (!version) fail('versionName introuvable dans cine/build.gradle.kts');
if (!feed) fail('updateFeed vide dans gradle.properties');
for (const f of ['selvania.keystore', 'keystore.properties']) {
  if (!fs.existsSync(path.join(here, f))) fail(`${f} absent : impossible de signer la mise à jour`);
}

const tag = `v${version}`;
let exists = true;
try {
  execFileSync('gh', ['release', 'view', tag, '-R', feed], { stdio: 'ignore' });
} catch {
  exists = false;
}
if (exists) fail(`${tag} est déjà publiée dans ${feed} : augmentez versionCode et versionName.`);

console.log(`Selvania Ciné ${version} → ${feed}\n`);
run('npm run build:app', cine);
run(`"${path.join(here, process.platform === 'win32' ? 'gradlew.bat' : 'gradlew')}" :cine:assembleRelease`);
const apk = path.join(here, 'apk', 'Selvania-Cine.apk');
fs.mkdirSync(path.dirname(apk), { recursive: true });
fs.copyFileSync(path.join(here, 'cine', 'build', 'outputs', 'apk', 'release', 'cine-release.apk'), apk);

// Le paquet LG suit la même interface : il part dans la même version.
const files = [apk];
if (fs.existsSync(path.join(webos, 'build.mjs'))) {
  run('node build.mjs', webos);
  const ipk = fs.readdirSync(path.join(webos, 'dist')).find((f) => f.endsWith('.ipk'));
  if (ipk) files.push(path.join(webos, 'dist', ipk));
}

execFileSync('gh', ['release', 'create', tag, '-R', feed, '--title', `Selvania Ciné ${version}`, '--notes', notes, ...files], { stdio: 'inherit' });
console.log(`\n✓ ${tag} publiée : les applis installées proposeront la mise à jour.`);
