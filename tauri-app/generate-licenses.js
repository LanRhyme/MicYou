import { spawn } from 'node:child_process';
import { mkdir, readdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const appDir = path.dirname(fileURLToPath(import.meta.url));
const resourcesDir = path.join(appDir, 'src-tauri', 'resources');
const generatedDir = path.join(appDir, 'src', 'generated');
const outputFile = path.join(generatedDir, 'third-party-licenses.html');
const packageLockFile = path.join(appDir, 'package-lock.json');

await mkdir(generatedDir, { recursive: true });

const escapeHtml = (value) =>
  String(value)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#039;');

async function run(command, args, options = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, {
      cwd: appDir,
      stdio: options.capture ? ['ignore', 'pipe', 'pipe'] : 'inherit',
    });
    let stdout = '';
    let stderr = '';
    if (options.capture) {
      child.stdout.on('data', (chunk) => (stdout += chunk));
      child.stderr.on('data', (chunk) => (stderr += chunk));
    }
    child.once('error', reject);
    child.once('exit', (code, signal) => {
      if (code === 0) {
        resolve({ stdout, stderr });
      } else {
        const error = new Error(
          `${command} exited with ${signal ? `signal ${signal}` : `code ${code}`}${stderr ? `: ${stderr.trim()}` : ''}`,
        );
        error.exitCode = code;
        reject(error);
      }
    });
  });
}

async function cargoAboutVersion() {
  try {
    const { stdout } = await run('cargo', ['about', '--version'], { capture: true });
    return stdout.trim().match(/cargo-about\s+(\S+)/)?.[1] ?? null;
  } catch (error) {
    if (error.exitCode !== undefined || error.code === 'ENOENT') return null;
    throw error;
  }
}

async function configuredCargoAboutVersion() {
  const { stdout } = await run(
    'cargo',
    ['metadata', '--no-deps', '--format-version', '1'],
    { capture: true },
  );
  const metadata = JSON.parse(stdout);
  const version = metadata.metadata?.tools?.['cargo-about'];
  if (typeof version !== 'string' || !/^\d+\.\d+\.\d+(?:[-+][\w.-]+)?$/.test(version)) {
    throw new Error('Cargo.toml must declare workspace.metadata.tools.cargo-about');
  }
  return version;
}

async function ensureCargoAbout() {
  const expected = await configuredCargoAboutVersion();
  const installed = await cargoAboutVersion();
  if (installed === expected) {
    console.log(`[licenses] cargo-about ${installed} ready`);
    return;
  }

  console.log(
    installed
      ? `Updating cargo-about from ${installed} to ${expected}...`
      : `Installing cargo-about ${expected}...`,
  );
  await run('cargo', [
    'install',
    'cargo-about',
    '--version',
    expected,
    '--locked',
    '--features',
    'cli',
    '--force',
  ]);
  console.log(`[licenses] cargo-about ${expected} installed`);
}

async function generateRustReport() {
  console.log('[licenses] Generating Rust dependency report...');
  await ensureCargoAbout();

  await run('cargo', [
    'about',
    'generate',
    'about.hbs',
    '--workspace',
    '--all-features',
    '--locked',
    '--fail',
    '--output-file',
    path.relative(appDir, outputFile),
  ]);

  const report = await readFile(outputFile, 'utf8');
  console.log('[licenses] Rust dependency report generated');
  return report;
}

async function readLicenseFiles(packageDirectory) {
  const files = await readdir(packageDirectory).catch(() => []);
  const licenseFiles = files
    .filter((file) => /^(licen[cs]e|copying|notice)(\..*)?$/i.test(file))
    .sort((left, right) => left.localeCompare(right));

  const texts = await Promise.all(
    licenseFiles.map(async (file) => {
      const text = await readFile(path.join(packageDirectory, file), 'utf8').catch(() => '');
      return text.trim() ? `${file}\n\n${text.trim()}` : '';
    }),
  );
  return texts.filter(Boolean).join('\n\n');
}

async function generateNpmReport() {
  const lock = JSON.parse(await readFile(packageLockFile, 'utf8'));
  const seen = new Set();
  const packages = [];

  for (const [packagePath, metadata] of Object.entries(lock.packages ?? {})) {
    if (!packagePath || metadata.dev || !packagePath.includes('node_modules/')) continue;

    const marker = 'node_modules/';
    const name = metadata.name ?? packagePath.slice(packagePath.lastIndexOf(marker) + marker.length);
    const version = metadata.version ?? 'unknown';
    const key = `${name}@${version}`;
    if (seen.has(key)) continue;
    seen.add(key);

    packages.push({
      name,
      version,
      license: metadata.license ?? 'UNKNOWN',
      text: await readLicenseFiles(path.join(appDir, packagePath)),
    });
  }

  packages.sort((left, right) =>
    left.name.localeCompare(right.name) || left.version.localeCompare(right.version),
  );
  console.log(`[licenses] Frontend production dependencies: ${packages.length}`);

  const rows = packages
    .map(
      ({ name, version, license }) => `<tr>
        <td><a href="https://www.npmjs.com/package/${encodeURIComponent(name)}" target="_blank" rel="noreferrer">${escapeHtml(name)}</a></td>
        <td>${escapeHtml(version)}</td>
        <td>${escapeHtml(license)}</td>
      </tr>`,
    )
    .join('\n');
  const details = packages
    .filter(({ text }) => text)
    .map(
      ({ name, version, text }) => `<details>
      <summary>${escapeHtml(name)} ${escapeHtml(version)}</summary>
      <pre>${escapeHtml(text)}</pre>
    </details>`,
    )
    .join('\n');

  return `<section class="npm-licenses">
  <div class="license-summary">
    <h3>Frontend dependencies</h3>
    <p>Generated from production packages in package-lock.json.</p>
  </div>
  <table class="license-table">
    <thead><tr><th>Package</th><th>Version</th><th>License</th></tr></thead>
    <tbody>${rows}</tbody>
  </table>
  <div class="license-texts">${details}</div>
</section>`;
}

async function generateAssetReport() {
  const licenseFiles = (await readdir(resourcesDir))
    .filter((file) => /^LICENSE-.+\.txt$/i.test(file))
    .sort((left, right) => left.localeCompare(right));

  const assetLicenses = await Promise.all(
    licenseFiles.map(async (file) => {
      const text = await readFile(path.join(resourcesDir, file), 'utf8');
      const name = file.replace(/^LICENSE-/i, '').replace(/\.txt$/i, '');
      const license = text.split(/\r?\n/, 1)[0].trim();
      return { name, license, text };
    }),
  );

  console.log(`[licenses] Bundled asset licenses: ${assetLicenses.length}`);
  if (!assetLicenses.length) return '';
  return `<section class="asset-licenses">
  <div class="license-summary"><h3>${assetLicenses
    .map(({ name }) => escapeHtml(name))
    .join(' / ')}</h3></div>
  <div class="license-texts">
    ${assetLicenses
      .map(
        ({ name, license, text }) => `<details>
      <summary>${escapeHtml(name)} — ${escapeHtml(license)}</summary>
      <pre>${escapeHtml(text)}</pre>
    </details>`,
      )
      .join('\n    ')}
  </div>
</section>`;
}

const [rustReport, npmReport, assetReport] = await Promise.all([
  generateRustReport(),
  generateNpmReport(),
  generateAssetReport(),
]);
await writeFile(outputFile, `${assetReport}\n${npmReport}\n${rustReport}`);
console.log(`[licenses] Report written to ${path.relative(appDir, outputFile)}`);
