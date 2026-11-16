#!/usr/bin/env python3
"""Resume/read AI Hub graph profiles; --submit explicitly creates missing profile jobs."""
from pathlib import Path
import argparse
import collections
import json
import shutil
import statistics

from prepare_whisper import ROOT, verify_bundle


def save_json(path, value):
    temporary = path.with_name(path.name + '.part')
    try:
        temporary.write_text(json.dumps(value, indent=2))
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--jobs', type=Path, required=True, help='Persistent JSON ledger outside source Git')
    p.add_argument('--bundle-dir', type=Path)
    p.add_argument('--manifest', type=Path, default=ROOT / 'config/models/qnn-whisper-small-sm8650.json')
    p.add_argument('--device', default='Samsung Galaxy S24 Ultra')
    p.add_argument('--submit', action='store_true', help='Upload frozen graphs and submit missing profile jobs')
    a = p.parse_args()
    import qai_hub as hub
    rows = json.loads(a.jobs.read_text()) if a.jobs.exists() else []
    if len({r['part'] for r in rows}) != len(rows) or any(r['part'] not in ('encoder', 'decoder') for r in rows):
        p.error('Invalid/duplicate job ledger parts')
    if a.submit:
        if a.bundle_dir is None:
            p.error('--bundle-dir is required with --submit')
        manifest = json.loads(a.manifest.read_text())
        verify_bundle(a.bundle_dir, manifest)
        devices = [d for d in hub.get_devices(name=a.device)
            if 'chipset:' + manifest['chipset'] in d.attributes]
        if len(devices) != 1:
            p.error('Device must resolve to exactly one matching chipset')
        a.jobs.parent.mkdir(parents=True, exist_ok=True)
        for part in ('encoder', 'decoder'):
            if any(r['part'] == part for r in rows):
                continue
            needed = (a.bundle_dir / (part + '.bin')).stat().st_size * 2 + 256 * 1024 * 1024
            if shutil.disk_usage(a.jobs.parent).free < needed:
                p.error(f'Need at least {needed} free bytes for safe upload staging')
            folder = a.jobs.parent / ('upload-' + part)
            folder.mkdir(exist_ok=True)
            for suffix in ('.onnx', '.bin'):
                path = folder / (part + suffix)
                source = (a.bundle_dir / (part + suffix)).resolve()
                if path.exists() and path.resolve() != source:
                    p.error('Upload staging points at another model')
                if not path.exists():
                    path.symlink_to(source)
            model = hub.upload_model(folder, name='VoxBridge-WhisperSmall-W8A16-' + part)
            job = hub.submit_profile_job(model, device=devices[0], options='--compute_unit npu',
                name='VoxBridge-' + part + '-SM8650-W8A16', retry=False)
            rows.append(dict(part=part, job_id=job.job_id, job_url=job.url,
                device=devices[0].name, options='--compute_unit npu'))
            save_json(a.jobs, rows)
    if not rows:
        p.error('No existing jobs. Add --submit only when ready to create jobs')
    result = []
    for row in rows:
        job = hub.get_job(row['job_id'])
        status = job.get_status()
        item = dict(part=row['part'], job_id=row['job_id'], url=row.get('job_url'), status=str(status.code))
        if status.success:
            profile_path = a.jobs.parent / (row['part'] + '-profile.json')
            if profile_path.exists():
                # A completed run is a frozen snapshot. SDK tuples become JSON lists;
                # re-downloading and comparing Python containers is both wasteful and misleading.
                profile = json.loads(profile_path.read_text())
            else:
                profile = job.download_profile()
                save_json(profile_path, profile)
            summary = profile['execution_summary']
            values = summary['all_inference_times']
            item.update(n=len(values), estimated_ms=summary['estimated_inference_time'] / 1000,
                median_ms=statistics.median(values) / 1000,
                estimated_peak_mib=summary['estimated_inference_peak_memory'] / 1048576,
                compute_unit_nodes=dict(collections.Counter(x['compute_unit'] for x in profile['execution_detail'])))
        result.append(item)
    output = dict(scope='Hosted graph profiles, synthetic inputs; decoder one step; not app E2E or quality',
        time_units='microseconds in raw profile, milliseconds in summary', jobs=result)
    save_json(a.jobs.parent / 'profile-summary.json', output)
    print(json.dumps(output, indent=2))


if __name__ == '__main__':
    main()
