import { readFileSync, writeFileSync } from 'fs';
import { resolve, dirname } from 'path';
import { fileURLToPath } from 'url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const gradlePropsPath = resolve(__dirname, '..', 'gradle.properties');
const tauriConfigPath = resolve(__dirname, 'src-tauri', 'tauri.conf.json');
const cargoTomlPath = resolve(__dirname, 'src-tauri', 'Cargo.toml');
const packageJsonPath = resolve(__dirname, 'package.json');

function readGradleVersion() {
  const content = readFileSync(gradlePropsPath, 'utf-8');
  const versionMatch = content.match(/project\.version=(.+)/);
  const versionCodeMatch = content.match(/project\.version\.code=(.+)/);
  
  if (!versionMatch) throw new Error('project.version not found in gradle.properties');
  
  return {
    version: versionMatch[1].trim(),
    versionCode: versionCodeMatch ? versionCodeMatch[1].trim() : '1'
  };
}

function toSemver(version) {
  const match = version.match(/^(\d+)\.(\d+)\.(\d+)(.*)?$/);
  if (match) {
    const [, major, minor, patch, pre] = match;
    if (pre && pre.length > 0) {
      const cleanPre = pre.replace(/[^a-zA-Z0-9]/g, '').toLowerCase();
      return `${major}.${minor}.${patch}-${cleanPre}`;
    }
    return `${major}.${minor}.${patch}`;
  }
  return version;
}

function updateTauriConfig(version) {
  const config = JSON.parse(readFileSync(tauriConfigPath, 'utf-8'));
  config.version = version;
  writeFileSync(tauriConfigPath, JSON.stringify(config, null, 2) + '\n');
  console.log(`Updated tauri.conf.json: ${version}`);
}

function updateCargoToml(version) {
  let content = readFileSync(cargoTomlPath, 'utf-8');
  content = content.replace(/^version\s*=\s*".*"$/m, `version = "${version}"`);
  writeFileSync(cargoTomlPath, content);
  console.log(`Updated Cargo.toml: ${version}`);
}

function updatePackageJson(version) {
  const pkg = JSON.parse(readFileSync(packageJsonPath, 'utf-8'));
  pkg.version = version;
  writeFileSync(packageJsonPath, JSON.stringify(pkg, null, 2) + '\n');
  console.log(`Updated package.json: ${version}`);
}

try {
  const { version, versionCode } = readGradleVersion();
  const semver = toSemver(version);
  
  console.log(`Gradle version: ${version} (code: ${versionCode})`);
  console.log(`Semver: ${semver}`);
  
  updateTauriConfig(semver);
  updateCargoToml(semver);
  updatePackageJson(semver);
  
  console.log('Version sync completed!');
} catch (error) {
  console.error('Failed to sync version:', error.message);
  process.exit(1);
}
