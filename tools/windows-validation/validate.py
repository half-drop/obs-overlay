"""Evidence collector for Windows OBS acceptance; Python 3.10+, standard library only."""
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import subprocess
import sys
import zipfile

CASES = {
    'isolation': 'Record 30s: test checkmark visible to player, absent in every recorded frame; public hotbar and moving world visible in both. Include icon OFF/ON/OFF.',
    'resize': 'Repeat 1280x720 -> 960x600 -> native size 10 times while recording. Within 5s each: live world, correct HUD position/scale, no private pixels in recording, no stale image/crash.',
    'fullscreen': 'F11 window/fullscreen 10 round trips; minimize for 5s and restore 5 times. Within 5s after restore: live capture and private isolation, no corruption/crash.',
    'reconnect': 'Deactivate/reactivate Game Capture 5 times, then restart OBS twice with game running; restart game twice with OBS running. Within 10s each: moving capture and private isolation.',
    'normal-exit': 'Quit game normally 5 times, including immediately after resize/fullscreen. Exact game PID disappears within 15s, no new crash/validation errors; relaunch and verify isolation.',
    'abnormal-exit': 'In a disposable local world, use Task Manager to end ONLY the identified game PID, 3 times including after resize. PID disappears within 15s; OBS remains usable; relaunch restores capture/isolation within 10s of gameplay. Record new PID each time.',
    'soak': 'Record 10min, then 20 resize/fullscreen cycles at a fixed final size. Compare settled process/GPU memory at same scene after warmup and after cycles. Investigate sustained growth; record numbers, driver resets and validation errors.',
}


def now():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def command(args):
    try:
        p = subprocess.run(args, capture_output=True, text=True, timeout=30)
        return {'returncode': p.returncode, 'stdout': p.stdout, 'stderr': p.stderr}
    except (OSError, subprocess.TimeoutExpired) as e:
        return {'error': str(e)}


def digest(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for block in iter(lambda: f.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def attach(path, out, label):
    path = Path(path)
    target = out / (label + path.suffix)
    # Never overwrite an earlier evidence file.
    with path.open('rb') as source, target.open('xb') as dest:
        import shutil
        shutil.copyfileobj(source, dest)
    return {'file': target.name, 'sha256': digest(target), 'source': str(path.resolve())}


def summarize(results):
    statuses = [r['status'] for r in results.values()]
    if 'fail' in statuses:
        return 'fail'
    if len(statuses) == len(CASES) and all(s == 'pass' for s in statuses):
        return 'manual-acceptance-pass; presentation-retirement-guarantee-still-unresolved'
    return 'incomplete'


def main():
    p = argparse.ArgumentParser(description=__doc__)
    sub = p.add_subparsers(dest='action', required=True)
    init = sub.add_parser('init')
    init.add_argument('--jar', type=Path, required=True)
    init.add_argument('--out', type=Path, required=True)
    init.add_argument('--commit', required=True, help='Exact build source SHA; not inferred from local checkout')
    record = sub.add_parser('record')
    record.add_argument('--out', type=Path, required=True)
    record.add_argument('--case', choices=CASES, required=True)
    record.add_argument('--status', choices=['pass', 'fail', 'blocked'], required=True)
    record.add_argument('--note', required=True)
    record.add_argument('--evidence', type=Path, nargs='+', required=True)
    snap = sub.add_parser('snapshot')
    snap.add_argument('--out', type=Path, required=True)
    snap.add_argument('--game-pid', type=int, required=True)
    snap.add_argument('--game-dir', type=Path, required=True)
    snap.add_argument('--obs-log', type=Path, required=True, help='Exact current OBS log file')
    a = p.parse_args()
    if a.action == 'init':
        if sys.platform != 'win32':
            p.error('init must run on the Windows target; no compatibility result was generated')
        if not re.fullmatch(r'[0-9a-fA-F]{40}', a.commit):
            p.error('--commit requires a full 40-character SHA')
        with zipfile.ZipFile(a.jar) as z:
            dll = z.read('assets/obs_overlay/native/windows-x86_64/obs_overlay_vulkan.dll')
            if not dll.startswith(b'MZ'):
                p.error('packaged Windows native library has no PE signature')
        a.out.mkdir(parents=True, exist_ok=False)
        data = {'created': now(), 'commit': a.commit, 'jar': str(a.jar.resolve()),
                'jar_sha256': digest(a.jar), 'dll_sha256': hashlib.sha256(dll).hexdigest(),
                'platform': platform.platform(), 'cases': {}, 'summary': 'incomplete',
                'criteria': CASES, 'automated_compatibility_verdict': 'not-performed',
                'environment': command(['powershell.exe', '-NoProfile', '-Command',
                    'Get-CimInstance Win32_OperatingSystem | Select Caption,Version,BuildNumber | ConvertTo-Json; Get-CimInstance Win32_VideoController | Select Name,DriverVersion | ConvertTo-Json']),
                'nvidia': command(['nvidia-smi', '--query-gpu=name,driver_version,memory.used', '--format=csv'])}
        (a.out / 'report.json').write_text(json.dumps(data, indent=2), encoding='utf-8')
    else:
        report = a.out / 'report.json'
        data = json.loads(report.read_text(encoding='utf-8'))
        stamp = dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
        if a.action == 'record':
            files = [attach(f, a.out, f'{stamp}-{i}') for i, f in enumerate(a.evidence)]
            entry = {'time': now(), 'status': a.status, 'note': a.note, 'evidence': files,
                     'verdict_source': 'human'}
            data.setdefault('history', []).append({'case': a.case, **entry})
            data['cases'][a.case] = entry
            data['summary'] = summarize(data['cases'])
        else:
            if sys.platform != 'win32' or a.game_pid <= 0:
                p.error('snapshot requires Windows and a positive exact game PID')
            # No command lines (tokens), process termination, registry or settings changes.
            ps = (f'Get-Process -Id {a.game_pid} -ErrorAction SilentlyContinue | '
                  'Select Id,ProcessName,StartTime,Responding,Handles,WorkingSet64,PrivateMemorySize64 | ConvertTo-Json')
            snapshot = {'time': now(), 'game_pid': a.game_pid,
                        'process': command(['powershell.exe', '-NoProfile', '-Command', ps]),
                        'gpu': command(['nvidia-smi', '--query-gpu=name,memory.used', '--format=csv']),
                        'files': []}
            for i, f in enumerate([a.game_dir / 'logs/latest.log', a.game_dir / 'config/obs_overlay.json', a.obs_log]):
                if f.is_file():
                    snapshot['files'].append(attach(f, a.out, f'{stamp}-log-{i}'))
                else:
                    snapshot['files'].append({'source': str(f), 'error': 'missing'})
            snapshot['crash_files'] = [str(f) for pattern in ('hs_err_pid*.log', 'crash-reports/*')
                                       for f in a.game_dir.glob(pattern)]
            data.setdefault('snapshots', []).append(snapshot)
        temp = report.with_suffix('.tmp')
        temp.write_text(json.dumps(data, indent=2), encoding='utf-8')
        os.replace(temp, report)
    print(a.out / 'report.json')


if __name__ == '__main__':
    main()
