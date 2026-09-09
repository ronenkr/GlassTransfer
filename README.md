# GlassTransfer

Move a file across a screen. The sender is one HTML file that turns a file into a stream of QR
codes in the browser. The receiver is one Java file that reads those codes off the screen, writes
the bytes back to disk, and presses the Up arrow to ask for the next one.

Nothing is shared between the two sides except pixels on a display and keystrokes going back.

```
  browser (sender.html)                        desktop (GlassReceiver.java)
  +---------------------+                      +--------------------------+
  |  [##] QR of chunk i |  --- screen ------>  |  capture -> decode        |
  |  chunk index field  |  <-- Up arrow -----  |  append -> press Up       |
  +---------------------+                      +--------------------------+
```

## Requirements

- A JDK 25 for the receiver. Any build works; this project was developed against Temurin 25.
  Install one with `winget install --id EclipseAdoptium.Temurin.25.JDK` if you have none.
- Any current browser for the sender. The page must be served over HTTP, not opened from disk.
- Both sides on the same machine, or a remote desktop session. The receiver reads the local screen.

## Quick start

Put `web/sender.html` in a folder together with the files you want to send, and serve that folder.
The JDK ships a static server:

```
jwebserver -b 127.0.0.1 -p 8000 -d D:\Projects\GlassTransfer\web
```

1. Open `http://localhost:8000/sender.html`, type a file name, press **Show**.
2. Start the receiver:

```
java desktop\GlassReceiver.java --out-dir out
```

3. Drag a rectangle around the QR code, including its white margin. Press Esc to cancel.
4. Click into the page's chunk field. The border turns green while a QR is readable.
5. After ten seconds of green the transfer starts and runs to the end on its own.

The received file is written to the output directory under the name the sender reported, once its
length, CRC32 and SHA-256 all match what the sender computed. The digests are taken by reading the
finished file back off disk, so they check what was actually written.

Chunks may arrive in any order. The receiver keeps a map of which ones it has, sweeps forward
through the file, and when the end code appears it goes back for anything still missing. A transfer
that is interrupted leaves `<fileId>.part` and `<fileId>.map` behind; start the same file again and
it picks up exactly the chunks it lacks. If verification fails at the end it fetches the whole file
once more before giving up.

## Sender

One self-contained file. Nayuki's QR Code generator (MIT) is vendored inside it; the pristine copy
is kept as `web/qrcodegen-v1.8.0-es6.js` for diffing.

- **Entry screen**: a file name and a **Show** button. The file is fetched from the same folder as
  the page, so `reports/data.bin` works and absolute URLs do not. A missing or unreadable file
  leaves you on the entry screen with the HTTP status.
- **QR screen**: the code, a **Back** button, and the chunk index field. `Enter` jumps to the index
  you typed, `ArrowUp` shows the next chunk, `ArrowDown` the previous one. Neither wraps. After the
  last chunk comes a single END code carrying the file's size, name and checksum.
- Encoding runs in Web Workers that stay 64 chunks ahead, so a key press repaints in about a
  millisecond. A chunk that is not ready yet is encoded on the spot rather than showing a blank.
- A red banner appears whenever the chunk field loses focus, because the receiver's keys then go
  somewhere else.

Advanced settings, and the equivalent URL parameters, change the QR version, error correction level
and pixels per module: `sender.html?file=data.bin&px=4&ver=40&ecc=L`. Add `&debug=1` for a Save PNG
button and a diagnostics line. Defaults are version 40 at level L, 4 device pixels per module,
which is 2932 bytes of file data per code.

## Receiver

One source file, no dependencies, run straight from source with `java GlassReceiver.java`.

| Option | Meaning |
| --- | --- |
| `--region x,y,w,h` | Watch this area instead of dragging one. Logical pixels. |
| `--arm-seconds N` | Green time before the transfer starts. Default 10. |
| `--out-dir DIR` | Where received files go. Default: current directory. |
| `--once` | Exit after one file instead of waiting for the next. |
| `--no-keys` | Decode and write, never press a key. Safe dry run. |
| `--stats` | Add capture and decode timings to progress lines. |
| `--verbose` | Log every decoded frame. |
| `--dump-fail DIR` | Save up to 20 frames that failed to decode. |
| `--capture-once f.png` | Save one capture of the region and exit. |
| `--decode-image f.png` | Decode a screenshot and print the frame. Works headless. |
| `--selftest` | Offline checks of the decoder, Reed-Solomon and the tables. |

The border is grey with no code in view, green while one is readable, orange when the signal is
lost mid-transfer, red when the browser has stopped responding to keys, and blue when a file is
done. The status strip shows the percentage done, the chunk count and the current rate, and carries
a Quit button. The same percentage goes into the terminal title, so a minimised window still shows
progress. The sender page shows its own percentage in the label and the browser tab.

The QR decoder is written from scratch for this one job: an axis-aligned, unrotated, losslessly
captured screen render. It finds the three finder patterns, measures the module pitch, reads the
format and version information, unmasks, de-interleaves the blocks and corrects them with
Reed-Solomon. There is no perspective correction, so a photograph of a screen will not decode.

## Frame format

Every code carries a 21-byte header and a payload, big-endian throughout.

| Offset | Size | Field |
| --- | --- | --- |
| 0 | 2 | magic `GT` |
| 2 | 1 | high nibble protocol version 2, low nibble type: 0 data, 1 end |
| 3 | 4 | file id, CRC32 of the name and size |
| 7 | 4 | chunk index; for the end frame, the chunk count |
| 11 | 4 | number of data chunks |
| 15 | 2 | chunk size in bytes |
| 17 | 4 | CRC32 of the whole frame with this field zeroed |
| 21 | n | payload |

Every code carries its own chunk index and the total, which is how the receiver knows what it is
looking at and can accept chunks in any order.

The end frame's payload is the file size (8 bytes), its CRC32 (4), its SHA-256 (32), then the name
length (2) and the name. Both sides must agree on the protocol version, so an older receiver
rejects a newer sender's codes rather than misreading them.

## Speed

Each key press moves one chunk, so throughput is chunks per second times the chunk size. The cycle
is one key press, one browser repaint, one screen capture and one decode. A high-refresh display
helps more than anything else, and a tight capture region helps next. Use `--stats` to see where
the time goes.

To go faster: keep the browser on your fastest display, shrink the region to the code plus its
margin, and lower pixels per module to 3. Below 3 device pixels per module the module grid starts
to alias and decoding gets unreliable.

## Troubleshooting

- **Nothing happens when the transfer starts.** The browser does not have keyboard focus. Click the
  chunk field. The page shows a red banner whenever it is not focused.
- **The border never turns green.** The region misses part of the code, or the browser is zoomed so
  far out that modules are under three pixels. Include the white margin on all four sides.
- **Keys are ignored no matter what.** A browser started as administrator will not accept synthetic
  keystrokes from a normal-privilege program. Run both at the same level.
- **The page says it must be served over HTTP.** Browsers block `fetch` on `file://` URLs. Serve the
  folder with `jwebserver`.
- **The checksum fails at the end.** The receiver restarts the file once by itself. If it fails
  again, the codes are being misread; raise pixels per module or the error correction level.

## Checking it works

`java desktop\GlassReceiver.java --selftest` runs offline: Reed-Solomon correction at and beyond
its limit, the format and version BCH codes, the block tables for all 160 version and level
combinations, and 56 encode-render-decode round trips across every version, level and mask.

`testdata` holds two captures kept as fixtures. Point the decoder at either one:

```
java desktop\GlassReceiver.java --decode-image testdata\shot_v40_dpr1.png
java desktop\GlassReceiver.java --decode-image testdata\shot_v40_dpr15.png
```

Each prints the version, level, mask, how many codewords needed correcting, and the frame header.
For a full check, send a file and compare `Get-FileHash -Algorithm SHA256` on both sides.

## Licences

The QR encoder in `web/sender.html` and `web/qrcodegen-v1.8.0-es6.js` is Project Nayuki's QR Code
generator v1.8.0, MIT licensed, with its notice intact. Everything else in this repository is
original. QR Code is a registered trademark of Denso Wave Incorporated.
