import { readdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const appDir = path.dirname(fileURLToPath(import.meta.url));
const resourcesDir = path.join(appDir, 'src-tauri', 'resources');
const outputFile = path.join(appDir, 'src', 'generated', 'third-party-licenses.html');

const escapeHtml = (value) =>
  value
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#039;');

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

const assetReport = assetLicenses.length
  ? `<section class="asset-licenses">
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
</section>`
  : '';

const cargoReport = await readFile(outputFile, 'utf8');
await writeFile(outputFile, `${assetReport}\n${cargoReport}`);
