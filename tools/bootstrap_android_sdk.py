#!/usr/bin/env python3
"""Install pinned Google Android build tools into an ephemeral Linux CI directory."""
import hashlib
import os
from pathlib import Path
import stat
import subprocess
import tempfile
import urllib.request
import zipfile

URL = 'https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip'
SHA256 = '7ec965280a073311c339e571cd5de778b9975026cfcbe79f2b1cdcb1e15317ee'


def main():
    sdk = (Path(os.environ.get('RUNNER_TEMP', tempfile.gettempdir()))/'oneui-handoff-sdk').resolve()
    if any(c in str(sdk) for c in '\r\n'):
        raise SystemExit('Invalid SDK directory')
    tools = sdk/'cmdline-tools/19.0'
    manager = tools/'bin/sdkmanager'
    if not manager.is_file():
        with tempfile.TemporaryDirectory(prefix='android-cli-') as temporary:
            archive = Path(temporary)/'tools.zip'
            digest = hashlib.sha256()
            with urllib.request.urlopen(URL, timeout=90) as response, archive.open('wb') as output:
                if not response.geturl().startswith('https://dl.google.com/'):
                    raise SystemExit('Unexpected Google download redirect')
                while True:
                    chunk = response.read(1024*1024)
                    if not chunk:
                        break
                    digest.update(chunk)
                    output.write(chunk)
            if digest.hexdigest() != SHA256:
                raise SystemExit('Google command-line tools checksum mismatch')
            with zipfile.ZipFile(archive) as package:
                for entry in package.infolist():
                    path = Path(entry.filename)
                    if path.is_absolute() or '..' in path.parts or not path.parts or path.parts[0] != 'cmdline-tools':
                        raise SystemExit('Unsafe SDK archive entry')
                    if stat.S_ISLNK(entry.external_attr >> 16):
                        raise SystemExit('SDK archive must not contain symlinks')
                    target = tools.joinpath(*path.parts[1:])
                    if entry.is_dir():
                        target.mkdir(parents=True, exist_ok=True)
                    else:
                        target.parent.mkdir(parents=True, exist_ok=True)
                        target.write_bytes(package.read(entry))
                        target.chmod(0o755 if 'bin' in path.parts else 0o644)
    subprocess.run([str(manager), '--sdk_root='+str(sdk), '--licenses'], input='y\n'*100,
                   text=True, stdout=subprocess.DEVNULL, check=True)
    subprocess.run([str(manager), '--sdk_root='+str(sdk), 'platforms;android-36', 'build-tools;36.0.0'], check=True)
    if 'GITHUB_ENV' in os.environ:
        with Path(os.environ['GITHUB_ENV']).open('a') as output:
            output.write('ANDROID_SDK_ROOT='+str(sdk)+'\n')
    if 'GITHUB_PATH' in os.environ:
        with Path(os.environ['GITHUB_PATH']).open('a') as output:
            output.write(str(tools/'bin')+'\n')
    print('Installed pinned Android SDK components in the CI workspace.')


if __name__ == '__main__':
    main()
