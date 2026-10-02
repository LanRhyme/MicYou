import { copyFileSync, mkdirSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const appDir = path.dirname(fileURLToPath(import.meta.url));
const debug = process.argv.includes('--debug');

// A universal macOS build is requested as `universal-apple-darwin`. The bundler only
// lipos the main binary, so the sidecars have to be built for both architectures and
// merged here, named after the universal triple.
const universalTriple = 'universal-apple-darwin';
const universalArchTriples = ['aarch64-apple-darwin', 'x86_64-apple-darwin'];

function hostTargetTriple() {
  const details = execFileSync('rustc', ['-vV'], { encoding: 'utf8' });
  const target = details.match(/^host:\s*(\S+)$/m)?.[1];
  if (!target) throw new Error('Unable to determine the Rust host target triple');
  return target;
}

function cargoTargetDirectory() {
  const metadata = execFileSync('cargo', ['metadata', '--no-deps', '--format-version', '1'], {
    cwd: appDir,
    encoding: 'utf8',
  });
  try {
    return JSON.parse(metadata).target_directory;
  } catch (error) {
    throw new Error('Unable to read the Cargo target directory: ' + error.message);
  }
}

const requested = process.env.TAURI_ENV_TARGET_TRIPLE || hostTargetTriple();
const universal = requested === universalTriple;
const targets = universal ? universalArchTriples : [requested];
const profile = debug ? 'debug' : 'release';
const extension = requested.includes('windows') ? '.exe' : '';
const outputDir = path.join(appDir, 'src-tauri', 'binaries');
const sidecars = ['micyou-cli', 'micyou-tui'];
mkdirSync(outputDir, { recursive: true });

const targetDir = cargoTargetDirectory();

for (const target of targets) {
  const cargoArgs = ['build', '--locked', '--target', target, '-p', 'micyou-cli', '-p', 'micyou-tui'];
  if (!debug) cargoArgs.push('--release');

  console.log('[sidecars] Building CLI and TUI for ' + target + ' (' + profile + ')');
  execFileSync('cargo', cargoArgs, {
    cwd: appDir,
    stdio: 'inherit',
    env: {
      ...process.env,
      // Both terminal crates depend on the desktop core library. Disable sidecar
      // validation only for this bootstrap build; the following Tauri build sees
      // the prepared binaries and validates/bundles them normally.
      TAURI_CONFIG: JSON.stringify({ bundle: { externalBin: [] } }),
    },
  });

  for (const binary of sidecars) {
    const source = path.join(targetDir, target, profile, binary + extension);
    const destination = path.join(outputDir, binary + '-' + target + extension);
    copyFileSync(source, destination);
    console.log('[sidecars] Prepared ' + path.relative(appDir, destination));
  }
}

if (universal) {
  for (const binary of sidecars) {
    const merged = path.join(outputDir, binary + '-' + universalTriple + extension);
    const slices = universalArchTriples.map((target) =>
      path.join(outputDir, binary + '-' + target + extension),
    );
    execFileSync('lipo', ['-create', '-output', merged, ...slices], { stdio: 'inherit' });

    const archs = execFileSync('lipo', ['-archs', merged], { encoding: 'utf8' }).trim();
    for (const arch of ['arm64', 'x86_64']) {
      if (!archs.split(/\s+/).includes(arch)) {
        throw new Error('Merged sidecar ' + merged + ' is missing the ' + arch + ' slice');
      }
    }
    console.log(
      '[sidecars] Merged ' + path.relative(appDir, merged) + ' (' + archs + ')',
    );
  }
}

// Copy the platform-specific ONNX Runtime shared library into resources/
// so Tauri bundles it without needing per-target config in tauri.conf.json.
const ortFilename =
  requested.includes('windows') ? 'onnxruntime.dll' :
  requested.includes('linux') ? 'libonnxruntime.so' : 'libonnxruntime.dylib';
const ortSrc = path.join(appDir, 'src-tauri', 'libs', ortFilename);
const ortDst = path.join(appDir, 'src-tauri', 'resources', ortFilename);
copyFileSync(ortSrc, ortDst);
console.log('[sidecars] Copied ONNX Runtime library: ' + ortFilename);

if (universal) {
  const archs = execFileSync('lipo', ['-archs', ortDst], { encoding: 'utf8' }).trim();
  for (const arch of ['arm64', 'x86_64']) {
    if (!archs.split(/\s+/).includes(arch)) {
      throw new Error(
        'ONNX Runtime ' + ortFilename + ' is missing the ' + arch + ' slice (' + archs + '),'
        + ' a universal build needs ' + 'a universal2 library',
      );
    }
  }
  console.log('[sidecars] ONNX Runtime is universal (' + archs + ')');
}
