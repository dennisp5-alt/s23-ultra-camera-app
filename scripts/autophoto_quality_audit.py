#!/usr/bin/env python3
"""Offline, read-only AutoPhoto JPEG comparison: does not alter photographs.

Install: python -m pip install Pillow numpy
Example: python autophoto_quality_audit.py --reference Samsung-original.jpg \
         --candidate AutoPhoto-polished.jpg --out comparison.json

Use an untouched full-resolution original for meaningful source-to-export
analysis. Different edits or ChatGPT-resized uploads can still be compared,
but they cannot establish genuine recovered detail or source resolution.
Colour comparisons convert every embedded ICC profile to sRGB first; without
this, a P3 Samsung original and an sRGB Android export are incomparable.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from io import BytesIO
from pathlib import Path
from typing import Any

import numpy as np
from PIL import Image, ImageOps, ImageCms


def inspect(path: Path) -> tuple[dict[str, Any], np.ndarray]:
    if not path.is_file():
        raise FileNotFoundError(path)
    with Image.open(path) as image:
        # EXIF orientation is respected. Do not surface EXIF content, GPS or IDs.
        oriented = ImageOps.exif_transpose(image)
        embedded_icc = image.info.get('icc_profile')
        if embedded_icc:
            try:
                src_profile = ImageCms.ImageCmsProfile(BytesIO(embedded_icc))
                profile_name = ImageCms.getProfileDescription(src_profile).strip()
                # For photographic comparisons, all pixels must use the
                # same display-referred colour space before subtraction.
                corrected = ImageCms.profileToProfile(
                    oriented, src_profile, ImageCms.createProfile('sRGB'),
                    renderingIntent=0, outputMode='RGB'
                )
            except Exception as exc:
                raise ValueError('Cannot normalize embedded ICC profile to sRGB; '
                                 'comparison would be unreliable') from exc
        else:
            # JPEGs without an ICC profile are assumed to be sRGB; report
            # that uncertainty rather than silently declaring a match.
            profile_name = 'unprofiled (assumed sRGB)'
            corrected = oriented.convert('RGB')
        rgb = np.asarray(corrected, dtype=np.uint8)
        exif_count = len(image.getexif())
        info = {
            'filename': path.name,
            'width': int(rgb.shape[1]),
            'height': int(rgb.shape[0]),
            'file_bytes': path.stat().st_size,
            'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
            'format': image.format,
            'icc_embedded': bool(embedded_icc),
            'source_icc_profile': profile_name,
            'comparison_colour_space': 'sRGB',
            'exif_fields_present': exif_count > 0,
        }
    return info, rgb


def image_metrics(rgb: np.ndarray) -> dict[str, float]:
    a = rgb.astype(np.float32)
    lum = a @ np.array([.2126, .7152, .0722], dtype=np.float32)
    highest = rgb.max(axis=2)
    all_white = np.all(rgb >= 250, axis=2)
    # A clipped channel need not imply lost detail (e.g., bright red flowers).
    return {
        'mean_luma_255': round(float(lum.mean()), 4),
        'luma_p01_255': round(float(np.percentile(lum, 1)), 4),
        'luma_p50_255': round(float(np.percentile(lum, 50)), 4),
        'luma_p99_255': round(float(np.percentile(lum, 99)), 4),
        'one_or_more_channels_ge_250_pct': round(float(np.mean(highest >= 250) * 100), 4),
        'one_or_more_channels_ge_254_pct': round(float(np.mean(highest >= 254) * 100), 4),
        'all_channels_ge_250_pct': round(float(np.mean(all_white) * 100), 4),
        'near_black_luma_le_4_pct': round(float(np.mean(lum <= 4) * 100), 4),
    }


def compare(reference: np.ndarray, candidate: np.ndarray) -> dict[str, Any]:
    if reference.shape != candidate.shape:
        raise ValueError(f'Image dimensions differ: {reference.shape} vs {candidate.shape}. '
                         'Use exact same orientation and pixel dimensions; do not auto-resize '
                         'because resampling would invalidate fine-detail comparisons.')
    a = reference.astype(np.float32)
    b = candidate.astype(np.float32)
    absrgb = np.abs(a-b)
    largest = np.max(absrgb, axis=2)
    lumaweights = np.array([.2126, .7152, .0722], dtype=np.float32)
    ya = a @ lumaweights
    yb = b @ lumaweights
    return {
        'mean_abs_rgb_channel_delta_255': round(float(absrgb.mean()), 4),
        'mean_signed_luma_delta_255': round(float(np.mean(yb-ya)), 4),
        'mean_abs_luma_delta_255': round(float(np.mean(np.abs(yb-ya))), 4),
        'pixels_with_any_channel_delta_gt_5_pct': round(float(np.mean(largest > 5) * 100), 4),
        'pixels_with_any_channel_delta_gt_10_pct': round(float(np.mean(largest > 10) * 100), 4),
        'pixels_exactly_equal_pct': round(float(np.mean(np.all(reference==candidate,axis=2))*100), 4),
    }


def audit(refpath: Path, candpath: Path) -> dict[str, Any]:
    refmeta, a = inspect(refpath)
    candmeta, b = inspect(candpath)
    warnings: list[str] = [
        'Numeric differences do not establish which photograph looks better.',
        'More edge contrast is not proof of recovered detail; it may be sharpening noise or JPEG artifacts.',
        'One saturated colour channel is not equivalent to lost highlight detail.',
    ]
    if min(max(a.shape[:2]), max(b.shape[:2])) < 4096:
        warnings.append('At least one supplied image is under 4096 px on its long side. '
                        'If resized for chat, it cannot validate native S23 Ultra source detail.')
    if not refmeta['icc_embedded'] or not candmeta['icc_embedded']:
        warnings.append('At least one file lacks an ICC profile; its RGB values '
                        'are assumed to be sRGB rather than verified.')
    if refmeta['source_icc_profile'] != candmeta['source_icc_profile']:
        warnings.append('Input and export colour profiles differ: they have been '
                        'converted to common sRGB for statistics. Conversion '
                        'from P3 to sRGB may itself affect saturated colours.')
    if not refmeta['exif_fields_present']:
        warnings.append('Reference lacks EXIF fields. Confirm it is the untouched camera original.')
    return {
        'reference': {**refmeta, 'metrics': image_metrics(a)},
        'candidate': {**candmeta, 'metrics': image_metrics(b)},
        'paired_metrics': compare(a, b),
        'warnings': warnings,
        'note': 'Read-only, ICC-normalized diagnostic. No photographs or raw metadata '
                'are uploaded. A metadata-preservation feature must exclude GPS '
                'and other identifying fields unless specifically requested.',
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--reference', required=True, type=Path,
                        help='Ideally the untouched original, otherwise a named comparison baseline.')
    parser.add_argument('--candidate', required=True, type=Path,
                        help='AutoPhoto export or another comparison.')
    parser.add_argument('--out', type=Path, help='Optional local JSON report path.')
    args = parser.parse_args()
    result = audit(args.reference, args.candidate)
    data = json.dumps(result, indent=2)
    if args.out:
        args.out.write_text(data + '\n', encoding='utf-8')
        print(f'Report saved locally: {args.out}')
    else:
        print(data)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
