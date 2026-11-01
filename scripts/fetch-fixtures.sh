#!/usr/bin/env bash
# Downloads full-size CC0 RAW samples from raw.pixls.us into fixtures/external (gitignored) and
# verifies their SHA-256 checksums. Integration tests that need them skip when they are absent.
# Total download is about 164 MB.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/fixtures/external"
mkdir -p "$DEST"

# id | local name | sha256 | original name (all CC0, https://raw.pixls.us)
FILES=(
  "1803|olympus_e3.orf|ba4605bbd9fe9b77971da444bcdb1698904c0ad4ebdf9dd42dd45ab255f3f6d4|Olympus - E-3 - 16bit (4:3).ORF"
  "1582|sony_ilce7s.arw|a35ebb2fbec929daa5beb20d1ce5c15a8aac7b1a7a231455387f3df8a7442e07|Sony - ILCE-7S - 14bit 14bit compressed (3:2).ARW"
  "3668|samsung_nx500.srw|156e43118811bcecb6fcc600b5f41ee1773c4f00b6a5b2dd72e177e35f6596ab|Samsung - NX500 - 12bit 12bit normal compression (3:2).SRW"
  "2811|leica_dlux5.rwl|ceeb5e5bf2358c04706e807bc023971e085d969b6f36491a57ecff051a05e8d3|Leica - D-LUX 5 - 1:1.RWL"
  "5227|nikon_d2h.nef|155edb938f884ea7372ce98d4ff5f965c3e413b43b95bc9923da6e92082cf914|Nikon - D2H - 12bit 12bit compressed (Lossy (type 1)) (3:2).NEF"
  "1018|canon_eos20d.cr2|db78660d48589130d99e0bc621424851eb0f84df052634fb5bc89c7a02fe7033|Canon - EOS 20D - RAW (3:2).CR2"
  "7010|panasonic_lx7.rw2|18dfc3877e944020299635a8c2fad61fca2485b1c9b67645a495ee870a86e599|Panasonic - DMC-LX7 - 3:2.RW2"
  "7317|blackmagic_micro.dng|4c65b8cda205087cfb94d8931811e53e15eb4df538ad67c1b4a3e76c1185b277|Blackmagic - Micro Cinema Camera - 12bit (16:9).dng"
  "2204|canon_5d3_converted.dng|1d77dcc6cdb839b10c1948f0b4471984ab48cf15e973fb77520584cee72b603e|Canon - EOS 5D Mark III - 14bit 14bit (2.3471882640587).dng"
  "4659|canon_eos_r6.cr3|74abb0a113d075ad9887a058082f40dd2a938c4813a08474d82356f11a027778|Canon - EOS R6 - 3:2.CR3"
  "8964|canon_eos_r6m3_craw.cr3|7e1a5d84297a52853b0bbe5a5c03a5d4f5b93e00b7020271523240f9cabfe50e|Canon - EOS R6 Mark III - CRAW (3:2).CR3"
  "2726|fujifilm_s5000.raf|dabd5e74521a6980156be9fd4b88d0c37b0fe4d0e0e6f5c12db8cffff1b76297|Fujifilm - FinePix S5000 - 4:3.RAF"
  "4190|fujifilm_xs10.raf|9419d1408ebf850395e5dd563beb96309466546252caf541466034c9cb7e9724|Fujifilm - X-S10 - 14bit 14bit compressed (3:2).RAF"
  "882|leica_x2.dng|a67eca6271afb10510ffc9cf998b641d0e8a0ebfa4c779a7bc0bfed5d28b62dd|Leica - X2 - 12bit (3:2).DNG"
  "1052|dji_fc220.dng|85a4fc27cca3c1358abd22898d6426dc3448060ca5143e7bd255cb5fff64392d|DJI - FC220 - 16bit (4:3).DNG"
  "2161|nikon_d3000.nef|a07ee8a0b1ff7a1ad06085306ef8f9a197d95fec639994467317c56108eeb435|Nikon - D3000 - 12bit 12bit compressed (Lossy (type 1)) (3:2).NEF"
  "7810|nikon_z6iii.nef|a8fa2cacadcf9dc85475ebcf37fcc32614072bc155e39d01491cb9e293c1343d|Nikon - Z6_3 - 8bit compressed (3:2).NEF"
  # Mislabeled upload: a JPEG named .dng. Kept to test signature-first detection.
  "3839|mislabeled_jpeg_as.dng|6492b7e0ac8e012524c0c83606a104cc9bf3b68f30fa60c7b73ffbe4dc584ab1|Samsung - SM-G973U - 16bit 16bit (2.1132075471698).dng"
)

sha() { if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }

for entry in "${FILES[@]}"; do
  IFS='|' read -r id name sum original <<<"$entry"
  target="$DEST/$name"
  if [[ -f "$target" && "$(sha "$target")" == "$sum" ]]; then
    echo "ok        $name"
    continue
  fi
  url="https://raw.pixls.us/getfile.php/$id/nice/$(python3 -c 'import sys,urllib.parse;print(urllib.parse.quote(sys.argv[1]))' "$original")"
  echo "download  $name"
  curl -fsSL --retry 3 -o "$target.part" "$url"
  actual="$(sha "$target.part")"
  if [[ "$actual" != "$sum" ]]; then
    echo "checksum mismatch for $name: $actual" >&2
    rm -f "$target.part"
    exit 1
  fi
  mv "$target.part" "$target"
done
echo "External fixtures ready in $DEST"
