"""Prepare and publish signed Spokify updates. Never uploads signing keys.

Requires Python 3.10+, Android SDK build-tools, Java and (for publish) GitHub CLI.
Build the performance variant and run its checks before preparing a release.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = 'Mitanshp5/Spokify'
SIGNER = '492591831bcac6d19f2a80b6782a50a52c08c89ce971f51c9b03b30046c1c785'


def run(*args, check=True):
    result = subprocess.run([str(a) for a in args], capture_output=True, text=True, check=check)
    return result


def sha(path):
    with path.open('rb') as stream:
        digest = hashlib.sha256()
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def sdk_path(explicit):
    if explicit:
        return Path(explicit)
    for name in ('ANDROID_SDK_ROOT', 'ANDROID_HOME'):
        if os.environ.get(name):
            return Path(os.environ[name])
    local = ROOT / 'android/local.properties'
    if local.exists():
        for line in local.read_text().splitlines():
            if line.startswith('sdk.dir='):
                return Path(line.split('=', 1)[1].replace('\\:', ':').replace('\\\\', '\\'))
    raise SystemExit('Set ANDROID_SDK_ROOT or pass --sdk.')


def prepare(args):
    apk = args.apk.resolve()
    sdk = sdk_path(args.sdk)
    tools = sorted((sdk / 'build-tools').iterdir(), key=lambda p: tuple(int(n) for n in re.findall(r'\d+', p.name)))
    binary = 'aapt2.exe' if os.name == 'nt' else 'aapt2'
    tools = next(p for p in reversed(tools) if (p / binary).is_file())
    badging = run(tools / binary, 'dump', 'badging', apk).stdout
    package = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
    minimum = re.search(r"(?:minSdkVersion|sdkVersion):'(\d+)'", badging)
    assert package and minimum, 'APK has no readable version/minimum SDK.'
    app_id, code, version = package.groups()
    assert app_id == 'com.mitansh.sidea', 'Wrong application ID.'
    assert 'application-debuggable' not in badging, 'Publish the optimized performance APK, not debug.'
    assert re.fullmatch(r'\d+\.\d+\.\d+', version), 'Use a stable x.y.z version name.'
    java = args.java or (str(Path(os.environ['JAVA_HOME']) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')) if os.environ.get('JAVA_HOME') else shutil.which('java'))
    assert java, 'Set JAVA_HOME or pass --java.'
    certificate = run(java, '-jar', tools / 'lib/apksigner.jar', 'verify', '--print-certs', apk).stdout
    signers = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]+)', certificate)
    assert [s.lower() for s in signers] == [SIGNER], 'Signing key differs from the existing app; update refused.'
    digest = sha(apk)
    folder = ROOT / 'android/.work/releases' / ('v' + version)
    folder.mkdir(parents=True, exist_ok=True)
    target = folder / 'spokify.apk'
    assert not target.exists() or sha(target) == digest, 'This version already has a different APK. Increase the version.'
    if apk != target:
        shutil.copyfile(apk, target)
    manifest = dict(schemaVersion=1, applicationId=app_id, versionCode=int(code), versionName=version,
                    minSdk=int(minimum.group(1)), sizeBytes=target.stat().st_size, sha256=digest)
    (folder / 'spokify-update.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    shutil.copyfile(args.notes, folder / 'RELEASE_NOTES.md')
    print(json.dumps(dict(directory=str(folder), repository=REPOSITORY, tag='v' + version,
                         sha256=digest, signing_certificate_sha256=SIGNER, bytes=manifest['sizeBytes']), indent=2))


def publish(args):
    folder = args.directory.resolve()
    manifest = json.loads((folder / 'spokify-update.json').read_text())
    apk = folder / 'spokify.apk'
    assert sha(apk) == manifest['sha256'] and apk.stat().st_size == manifest['sizeBytes'], 'Release files changed.'
    assert manifest['applicationId'] == 'com.mitansh.sidea'
    tag = 'v' + manifest['versionName']
    assert re.fullmatch(r'v\d+\.\d+\.\d+', tag)
    repository = json.loads(run('gh', 'repo', 'view', REPOSITORY, '--json', 'isPrivate,isEmpty').stdout)
    assert not repository['isPrivate'] and not repository['isEmpty'], 'Push the clean source to the public repository first.'
    assert run('gh', 'release', 'view', tag, '--repo', REPOSITORY, check=False).returncode != 0, 'This release exists; publish a higher version instead.'
    run('gh', 'release', 'create', tag, apk, folder / 'spokify-update.json', '--repo', REPOSITORY,
        '--target', 'main', '--title', 'Spokify ' + manifest['versionName'], '--notes-file', folder / 'RELEASE_NOTES.md', '--draft')
    release = json.loads(run('gh', 'api', f'repos/{REPOSITORY}/releases/tags/{tag}').stdout)
    assets = {asset['name']: asset for asset in release['assets']}
    for name in ('spokify.apk', 'spokify-update.json'):
        local = folder / name
        assert assets[name]['size'] == local.stat().st_size and assets[name]['digest'] == 'sha256:' + sha(local), 'Uploaded asset verification failed; release remains a draft.'
    run('gh', 'release', 'edit', tag, '--repo', REPOSITORY, '--draft=false', '--latest')
    print(f'https://github.com/{REPOSITORY}/releases/tag/{tag}')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    actions = parser.add_subparsers(dest='action', required=True)
    command = actions.add_parser('prepare')
    command.add_argument('--apk', type=Path, default=ROOT / 'android/app/build/outputs/apk/performance/app-performance.apk')
    command.add_argument('--notes', type=Path, default=ROOT / 'CHANGELOG.md')
    command.add_argument('--sdk')
    command.add_argument('--java')
    command = actions.add_parser('publish')
    command.add_argument('--directory', type=Path, required=True)
    args = parser.parse_args()
    try:
        prepare(args) if args.action == 'prepare' else publish(args)
    except subprocess.CalledProcessError as failure:
        raise SystemExit(failure.stderr or failure.stdout or str(failure))
