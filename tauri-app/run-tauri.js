import { spawnSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const appDir = path.dirname(fileURLToPath(import.meta.url));
const cli = path.join(appDir, 'node_modules', '@tauri-apps', 'cli', 'tauri.js');
const env = { ...process.env };

// linuxdeploy ships an older strip binary that cannot read newer RELR ELF
// sections. Cargo already optimizes release binaries, so skipping this second
// strip pass is both safe and portable across rolling and CI distributions.
if (process.platform === 'linux' && env.NO_STRIP === undefined) env.NO_STRIP = '1';

if (process.argv.includes('dev')) {
  try {
    if (process.platform === 'linux' || process.platform === 'darwin') {
      spawnSync('fuser', ['-k', '1420/tcp'], { stdio: 'ignore' });
    }
  } catch {}
}

const args = process.argv.slice(2);

// macOS: build a universal binary by default so a single download runs on both
// Apple Silicon and Intel. An explicit --target still wins, and `dev` always
// builds for the host only.
if (process.platform === 'darwin' && args.includes('build')) {
const hasTarget = args.some(
(arg) => arg === '--target' || arg === '-t' || arg.startsWith('--target='),
);
if (!hasTarget) args.push('--target', 'universal-apple-darwin');
}

const result = spawnSync(process.execPath, [cli, ...args], {
  cwd: appDir,
  env,
  stdio: 'inherit',
});
if (result.error) throw result.error;
if (result.signal) {
  console.error('Tauri terminated by signal ' + result.signal);
  process.exit(1);
}
process.exit(result.status ?? 1);
