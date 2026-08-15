# GPU Renderer Investigation — Handoff

## Objective
Verify `io.sanix` demo app (`SanixGpuDemoActivity`) renders 16 ANSI colors, bold, dim, inverse, cursor, underline, rotation/minimize correctly on device. **Currently blocked on a rendering bug**: cells whose glyph is a space (atlas cell 0,0) render as fg-colored fill with vertical banding, and glyph cells contain blended multi-atlas-row content.

## Key Facts & Commands

### Environment
- Repo: `/home/sanix/termux-snx` — inside Termux (proot) on the phone itself. `origin=fatuhsa/termux-snx`, `upstream=termux/termux-app`. **Conventional Commits enforced** (`Fixed: ...`, etc.).
- Android shell access: `printf '<cmd>\n' | sh /data/data/com.termux/files/home/rish` (Shizuku, `./rish` style; must run as `sh .../rish`).
- Model cannot see images → all screenshot analysis via inline Python (PNG 900x2030, colortype 6 RGBA, bpp=4, un-filter zlib).
- CI: `.github/workflows/debug_build.yml` triggers on push to `master`/`github-releases/**` branches AND `pull_request` to master. Push to a feature branch alone does NOT trigger it — must open a PR (or push to master).
- APK pipeline: `gh run download <runId> -R fatuhsa/termux-snx -D dir` → `cp ... /data/data/com.termux/files/home/storage/downloads/x.apk` → rish: `cp /sdcard/Download/x.apk /data/local/tmp/x.apk && pm install -r /data/local/tmp/x.apk` → launch:
  `am start -W -n io.sanix/com.termux.sanix.renderer.SanixGpuDemoActivity` (short form FAILS: "app died")
  → `screencap -p /sdcard/Download/x.png` → `cp .../storage/downloads/x.png /tmp/opencode/x.png`.
- Screenshot from rish pipe: `printf 'screencap -p /sdcard/Download/x.png\n' | sh rish` — output file lands in Termux home; copy back via `cp /data/data/com.termux/files/home/storage/downloads/x.png /tmp/opencode/`.

### Renderer (branch `debug-atlas`, PR #1 open)
- Files: `sanix-renderer/src/main/java/com/termux/sanix/renderer/SanixGpuRenderer.java`, `SanixGpuDemoActivity.java`, `TextStyle.java` (terminal-emulator).
- Grid: 40 cols x 14 rows; cell = 22.5x42px screen; font metrics: baseline 35.2, top 8.2 → glyph band 8-35/42 local.
- Atlas: `ATLAS_COLS=16`, `ATLAS_ROWS=6`, `ATLAS_PAGES=2` (12 rows), cell 22x44px → texture 352x528. `GL_ALPHA` internalformat (deprecated in GLES3 but valid). `uAtlasCells = vec2(16,12)`.
- Vertex shader: `vUv = (aCell.zw + aPos) / uAtlasCells;` where aCell = (col, row, atlasCol, atlasRow), aPos = quad 0..1. Instance stride = 13 floats (4 + 4 fg + 4 bg + 1 flags). Attribs loc1 aCell, loc2 aFg, loc3 aBg, loc4 aFlags, divisor=1.
- `TextStyle.encode`: `effect | (fg<<40) | (bg<<16)`; `decodeEffect = style & 0b11111111111`; `decodeForeColor=(style>>>40)&0b111111111`.
- PALETTE: [1..8] = CD0000, 00CD00, CDCD00, 0000EE, CD00CD, 00CDCD, E5E5E5, 7F7F7F; [9..15] = FF0000, 00FF00, FFFF00, 5C5CFF, FF00FF, 00FFFF, FFFFFFFF.
- Driver: Mali `sprd_gles` (Spreadtrum/Unisoc). No GL errors logged (no crash).

## Verified Facts
- Installed APK == source HEAD (`0b121ee5` on master, git clean).
- `demo3.png` == `ul-check.png` (md5 `71b070dc7f3e96409d14929f3dcf17af`); `sanix3.png` == `sanix-check.png` (md5 `38f9d94658f42dc4aa76460b2ffb64d0`).
- `sanix3.png` vs `demo3.png` differ ONLY in y1191-1232 (row10) → underline fix moved top→bottom correctly.
- Space cells (atlas 0,0): uniform horizontally, banded vertically (profiles differ per screen row!). Empty rows (1,6,9,11,13) render pure black. Row8 cells show hue variation within one cell — impossible with constant vFg.
- Row8 horizontal scan: 8 color blocks of 5 cols each, boundaries x13/114/227/339/451/563/675/790; fill colors NOT palette multiples: (173,0,0),(173,91,0),(212,130,0),(212,130,31),(137,130,212),(137,247,73),(210,64,146),(54,164,246). Digit '1' = (252,0,0) ≈ FF0000 (bold red) though decodeEffect=0.
- Geometry: row bands at x=450 are inconsistent: (777,802),(862,886),(901,936),(946,971),(988,1013),(1072,1097),(1115,1139),(1191,1232),(1283,1307) → glyphs drift within cells.
- **CORRECTIONS to earlier notes**: (1) 'H' cell row2 col0 is NOT clean — it shows vertical bars + full horizontal line at y873 (that IS the H crossbar — it is actually correct 'H'); (2) cursor row3 col12 is NOT a solid white block — only thin white bands (x270-290: 865-866, 874, 878-879, 896-900, 904-905, 917, 928-930, 932, 934-937, 947, 949, 954-962, 964-969, 991, 995-999).
- Atlas dump screenshot (`/tmp/opencode/atlas.png`): cell (col1,row1) shows TWO glyphs stacked: a 'V'-like shape (two diagonals, y169-209) + a ring '0' (y289-337). Cell (col0,row0) (' ') shows content too → **texture itself does not look like a clean atlas** OR vUv maps differently than expected. 'H' from normal render looks like a real 'H'.

## Current Debug Build (IN PROGRESS)
Branch `debug-atlas`, PR #1 (`gh pr view 1 -R fatuhsa/termux-snx`). Commit `0098eb41` pushed adds:
- `drawAtlasDump()` renders texture at exact 352x528 viewport then `glReadPixels` and compares against the source bitmap alpha, logging: mismatch count, zero-bit mismatches, nonZeroTex, first mismatch x/y, and sample pixels tex vs bit at (0,0), (176,88)='H' region, (10,10).
- `dumpAtlasPng()` exports the source bitmap to `getFilesDir()/atlas_bitmap.png` (retrievable via rish: `/data/data/io.sanix/files/atlas_bitmap.png`).
- Logs in `init()`: cellWpx/cellHpx, atlas dims; `glTexImage2D` error code.
- NOTE: logcat via rish does NOT show `io.sanix` logs (buffer drops them); rely on readback logs... they also go to logcat. Alternative: write logs into a file under getFilesDir too if logcat stays silent.

## To Continue
1. Download build run for PR #1 (last run of workflow "Build" on `debug-atlas`), install, launch, screenshot, pull `atlas_bitmap.png` from `/data/data/io.sanix/files/`.
2. Analyze: if READBACK mismatch≈0 → texture == bitmap → bug is in vUv/instancing (check instance data, attrib pointers); if mismatch large → texture upload wrong (GL_ALPHA internalformat? stride?).
3. Fix root cause, commit `Fixed: ...`, verify rows 0/7/8/10/12 + cursor, then run remaining test cases (bold, dim, inverse, cursor, rotation/minimize).

## Milestones
- [x] Fix crash on launch (commits `17cd34af`, `9f81638`)
- [x] Verify launch command + screenshot pipeline works
- [x] Underline drawn at bottom instead of top (commit `0b121ee5`, verified via diff sanix3 vs demo3)
- [x] TextStyle encode/decode verified consistent; source == installed build
- [x] Built atlas-dump debug mode (PR #1) — first screenshot analyzed, shows multi-glyph stacking in single cells
- [ ] Readback comparison run → root cause identification
- [ ] Fix root cause + full verification (bold, dim, inverse, cursor, rotation/minimize)