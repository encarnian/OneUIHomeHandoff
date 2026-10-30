#!/usr/bin/env python3
"""Bounded release-hygiene checks; this is not a vulnerability certification."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ANDROID = '{http://schemas.android.com/apk/res/android}'
PACKAGE = 'io.github.encarnian.oneuihomehandoff'
IGNORED = {'.git', 'build', 'lib', '__pycache__'}
DIRECTORIES = {'src', 'res', 'assets', 'tests', 'tools', 'docs', '.github'}
ROOT_FILES = {'README.md', 'LICENSE', '.gitignore', 'AndroidManifest.xml', 'build.sh',
              'SECURITY.md', 'CONTRIBUTING.md', 'CHANGELOG.md', 'THIRD_PARTY_NOTICES.md'}
BLOCKED_SUFFIXES = {'.apk', '.jar', '.dex', '.jks', '.keystore', '.log', '.pem', '.p12', '.pfx'}
PATTERNS = {
    'credential-like token': rb'(?:gh[pousr]_|github_pat_)[A-Za-z0-9_]{20,}',
    'private key material': rb'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----',
    'private development path': rb'/(?:home|Users)/[A-Za-z0-9._-]+/',
    'phone storage path': rb'/storage/emulated/[0-9]+/',
    'hardware model': rb'\b(?:Galaxy S[0-9]{2}|SM-[A-Z0-9]{4,})\b',
}


def require(condition, message):
    if not condition:
        raise SystemExit(message)


def scan_bytes(data, label):
    for description, pattern in PATTERNS.items():
        require(re.search(pattern, data) is None, f'{description} found in {label}')


def source_entry(name, data):
    path = Path(name)
    require(not path.is_absolute() and '..' not in path.parts, 'Unsafe source entry')
    require(not any(part in IGNORED for part in path.parts), 'Build/dependency/history file in source archive')
    require(path.suffix.lower() not in BLOCKED_SUFFIXES, f'Prohibited publication artifact: {name}')
    require((len(path.parts) == 1 and name in ROOT_FILES) or path.parts[0] in DIRECTORIES,
            f'Unexpected publication file: {name}')
    scan_bytes(data, name)
    if path.suffix.lower() == '.png':
        require(data.startswith(b'\x89PNG\r\n\x1a\n'), 'Invalid icon PNG')
        offset = 8
        while offset+12 <= len(data):
            length = struct.unpack_from('>I', data, offset)[0]
            kind = data[offset+4:offset+8]
            require(kind not in {b'tEXt', b'zTXt', b'iTXt', b'eXIf'}, 'PNG contains text/EXIF metadata')
            offset += length+12


def dex_definitions(data):
    def u32(offset): return struct.unpack_from('<I', data, offset)[0]
    strings = []
    for index in range(u32(56)):
        pos = u32(u32(60)+index*4)
        while data[pos] & 128:
            pos += 1
        pos += 1
        strings.append(data[pos:data.index(b'\0', pos)].decode('utf-8', 'replace'))
    types = [strings[u32(u32(68)+index*4)] for index in range(u32(64))]
    return [types[u32(u32(100)+index*32)] for index in range(u32(96))]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path)
    parser.add_argument('--source-zip', type=Path)
    args = parser.parse_args()
    count = 0
    for file in sorted(ROOT.rglob('*')):
        relative = file.relative_to(ROOT)
        if any(part in IGNORED for part in relative.parts):
            continue
        require(not file.is_symlink(), 'Source publication must not contain symlinks')
        if file.is_file():
            source_entry(relative.as_posix(), file.read_bytes())
            count += 1

    manifest = ET.parse(ROOT/'AndroidManifest.xml').getroot()
    require(manifest.attrib['package'] == PACKAGE, 'Unexpected app identity')
    require(not any(node.tag.startswith('uses-permission') for node in manifest), 'APK requests permissions')
    app = manifest.find('application')
    require(app.attrib.get(ANDROID+'allowBackup') == 'false', 'Backup must be disabled')
    require(app.attrib.get(ANDROID+'fullBackupContent') == 'false', 'Full backup must be disabled')
    require(app.attrib.get(ANDROID+'dataExtractionRules') == '@xml/data_extraction_rules', 'Missing extraction exclusions')
    exported = {(node.tag, node.attrib.get(ANDROID+'name')) for node in app
                if node.attrib.get(ANDROID+'exported') == 'true'}
    require(exported == {('activity', '.SettingsActivity'), ('provider', '.ConfigProvider')}, 'Unexpected exported component')
    provider = app.find('provider')
    require(provider.attrib.get(ANDROID+'authorities') == PACKAGE+'.config', 'Unexpected configuration authority')
    rules = ET.parse(ROOT/'res/xml/data_extraction_rules.xml').getroot()
    for kind in ('cloud-backup', 'device-transfer'):
        excluded = {(n.attrib.get('domain'), n.attrib.get('path')) for n in rules.find(kind).findall('exclude')}
        for domain in ('root', 'file', 'database', 'sharedpref', 'external', 'device_root', 'device_file', 'device_database', 'device_sharedpref'):
            require((domain, '.') in excluded, 'Incomplete backup/transfer exclusions')
    scopes = ET.parse(ROOT/'res/values/arrays.xml').getroot().find('string-array')
    require([n.text for n in scopes.findall('item')] == ['com.sec.android.app.launcher'], 'Overbroad LSPosed scope')
    code = '\n'.join(p.read_text() for p in (ROOT/'src').rglob('*.java'))
    for forbidden in ('java.net.', 'javax.net.', 'DexClassLoader', 'ProcessBuilder(', 'Runtime.getRuntime()', 'System.loadLibrary('):
        require(forbidden not in code, 'Unexpected runtime network/process/code-loading capability')
    require('error.getMessage()' not in code, 'Exception messages must not enter module logs')
    require('HOME_SOURCE package=' not in code, 'Source app package logging must be absent')
    require('Vibecoded' in (ROOT/'README.md').read_text() and 'Vibecoded' in code, 'Missing AI-assisted disclosure')
    workflow = (ROOT/'.github/workflows/build.yml').read_text()
    action_refs = re.findall(r'uses:\s*([^\s]+)', workflow)
    require(action_refs and all(re.fullmatch(r'actions/[\w-]+@[0-9a-f]{40}', ref) for ref in action_refs), 'Actions must be pinned to official commit hashes')
    require('contents: read' in workflow and 'contents: write' not in workflow, 'CI needs read-only repository permissions')
    require('SIGNING_' not in workflow, 'CI must not receive signing secrets')

    result = {'sourceFilesChecked': count, 'permissions': [], 'scope': ['com.sec.android.app.launcher'],
              'backupExcluded': True, 'aiAssistedDisclosure': True, 'securityGuarantee': False}
    if args.apk:
        sdk = os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME')
        require(bool(sdk), 'Set ANDROID_SDK_ROOT to inspect the compiled APK')
        aapt = Path(sdk)/'build-tools/36.0.0/aapt'
        dump = subprocess.check_output([str(aapt), 'dump', 'xmltree', str(args.apk), 'AndroidManifest.xml'], text=True)
        require('uses-permission' not in dump and PACKAGE+'.config' in dump, 'Compiled manifest differs from intended security surface')
        definitions = []
        with zipfile.ZipFile(args.apk) as apk:
            require(apk.read('assets/xposed_init').decode().strip() == PACKAGE+'.HandoffHook', 'Unexpected hook entry point')
            for entry in apk.namelist():
                data = apk.read(entry)
                scan_bytes(data, 'APK entry '+entry)
                require(not entry.endswith(('.jar', '.jks', '.keystore', '.log')), 'Prohibited file inside APK')
                if entry.endswith('.dex'):
                    definitions.extend(dex_definitions(data))
        prefix = 'L'+PACKAGE.replace('.', '/')+'/'
        require(definitions and all(name.startswith(prefix) for name in definitions), 'Foreign/test/library class definitions bundled in APK')
        result['definedModuleClasses'] = len(definitions)
        result['apkSha256'] = hashlib.sha256(args.apk.read_bytes()).hexdigest()

    if args.source_zip:
        with zipfile.ZipFile(args.source_zip) as archive:
            for name in archive.namelist():
                if name.endswith('/'):
                    continue
                parts = Path(name).parts
                require(parts and parts[0] == 'OneUIHomeHandoff', 'Unexpected source archive root')
                source_entry(Path(*parts[1:]).as_posix(), archive.read(name))
        result['sourceArchiveSha256'] = hashlib.sha256(args.source_zip.read_bytes()).hexdigest()
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
