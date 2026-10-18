# GlassTransfer

Move a file across a screen. The sender is one HTML file that paints a file onto the browser window
as a grid of coloured cells. The receiver is one Java file that reads those cells off the screen,
writes the bytes back to disk, and presses the Up arrow to ask for the next frame.

Nothing is shared between the two sides except pixels on a display and keystrokes going back.

```
  browser (sender.html)                        desktop (GlassReceiver.java)
  +---------------------+                      +--------------------------+
  | colour grid, frame i|  --- screen ------>  |  capture -> decode        |
  |  chunk index field  |  <-- Up arrow -----  |  append -> press Up       |
  +---------------------+                      +--------------------------+
```

The carrier is a colour raster built for this channel rather than a QR code. A screen grab is
lossless and square-on, so the defences a camera code needs are dead weight here. Each cell is one
of five colours -- black, red, green, blue, white -- two device pixels square, and the grid fills
the window. That is over a hundred thousand bytes per frame instead of a QR code's 2,932. QR is
still available as a fallback for a lossy path such as a compressed remote desktop session.

## Requirements

- A JDK 25 for the receiver. Any build works; this project was developed against Temurin 25.
  Install one with `winget install --id EclipseAdoptium.Temurin.25.JDK` if you have none.
- Any current browser for the sender. The page must be served over HTTP, not opened from disk.
- Both sides on the same machine, or a remote desktop session. The receiver reads the local screen.

## Quick start

Run `sender.bat`, then `receiver.bat`. Then:

1. Size the browser window: the code fills it, so a bigger window carries more per frame. Type a
   file name and press **Show**.
2. Drag a rectangle around the code, including its white margin. Press Esc to cancel.
3. Click into the page's chunk field. The border turns green while the code is readable.
4. After ten seconds of green the transfer starts and runs to the end on its own.

`sender.bat` serves the `web` folder on `http://127.0.0.1:8000` and opens the page. Point it
somewhere else to send files that live somewhere else; `sender.html` is copied in beside them:

```
sender.bat D:\stuff                 serve that folder
sender.bat D:\stuff\backup.zip      ... and fill the file name in already
sender.bat D:\stuff 8080            ... on another port
```

`receiver.bat` writes into `received` next to it. Anything you add goes straight to the receiver,
so `receiver.bat --once` and `receiver.bat --out-dir D:\in` both work. Both scripts look for a
JDK 25 in `GT_JAVA_HOME`, then `C:\jdk-25.0.4.1+1`, then `JAVA_HOME`, then the PATH.

The same thing by hand, if you would rather:

```
jwebserver -b 127.0.0.1 -p 8000 -d D:\Projects\GlassTransfer\web
java desktop\GlassReceiver.java --out-dir out
```

The received file is written to the output directory under the name the sender reported, once its
length, CRC32 and SHA-256 all match what the sender computed. The digests are taken by reading the
finished file back off disk, so they check what was actually written.

Chunks may arrive in any order. The receiver keeps a map of which ones it has, sweeps forward
through the file, and when the end code appears it goes back for anything still missing. A transfer
that is interrupted leaves `<fileId>.part` and `<fileId>.map` behind; start the same file again and
it picks up exactly the chunks it lacks. If verification fails at the end it fetches the whole file
once more before giving up.

Going back for a chunk is done by name, not by navigation: the receiver types that chunk's number
into the sender's chunk field and presses Enter, exactly as you would. Only the step to the next
chunk uses a key of its own, the Up arrow, because that is the one the whole transfer rides on. If
the number typed does not bring up the chunk that was asked for -- a keyboard layout with something
other than digits on the number row, say -- the receiver says so and steps with the arrow keys
instead, trying the field again every fourth seek in case it starts working.

One key is in the air at a time. How long the browser takes to answer one is measured as the
transfer runs, and the receiver waits four times that before it assumes a key went missing: a client
that needs two seconds a frame is answering, not ignoring, and sending it another key only makes it
skip a chunk and then need fetching back. The measure rises to a slow frame at once and comes down
gently, and a frame that arrived while several keys were queued is not counted, since it says
nothing about how long one key takes. When the browser is what sets the pace, the status line says
so: `browser 1.4 s a frame`.

## Sender

One self-contained file. Nayuki's QR Code generator (MIT) is vendored inside it; the pristine copy
is kept as `web/qrcodegen-v1.8.0-es6.js` for diffing.

- **Entry screen**: a file name and a **Show** button. The file is fetched from the same folder as
  the page, so `reports/data.bin` works and absolute URLs do not. A missing or unreadable file
  leaves you on the entry screen with the HTTP status.
- **Sending screen**: the code fills the window, with a **Back** button and the chunk index field on
  a strip along the bottom. `Enter` jumps to the index you typed, `ArrowUp` shows the next chunk,
  `ArrowDown` the previous one. Neither wraps. After the last chunk comes a single END code carrying
  the file's size, name and checksums.
- Encoding runs in Web Workers that stay a few chunks ahead, so a key press repaints quickly. A
  chunk that is not ready yet is encoded on the spot rather than showing a blank.
- Nothing on the page is mid-grey, because that is the colour the receiver looks for to find the
  code's rim.
- A red banner appears whenever the chunk field loses focus, because the receiver's keys then go
  somewhere else.

Advanced settings, and the equivalent URL parameters, choose the carrier and its size:
`sender.html?file=data.bin&mode=raster&px=2`. Add `&debug=1` for a Save PNG button and a
diagnostics line. The default is the colour raster at two device pixels per cell. `mode=qr` falls
back to QR codes, where `ver` and `ecc` set the version and error correction level.

Two device pixels per cell is the safe setting: it survives fractional display scaling and any
monitor colour profile. One pixel per cell roughly doubles the payload but leaves no margin for
scaling, so try it only if the receiver reports no repairs at two.

## Receiver

One source file, no dependencies, run straight from source with `java GlassReceiver.java`.

| Option | Meaning |
| --- | --- |
| `--mode raster\|qr` | Which carrier to read. Default `raster`. |
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
| `--selftest` | Offline checks of both decoders, Reed-Solomon and the tables. |
| `--corrupt N` | With `--decode-image`, damage N spots first, to exercise the repair. |
| `--drop N` | Ignore every Nth chunk once, to exercise the refetch. |
| `--vector` | Print reference values for checking another implementation. |

The border is grey with no code in view, green while one is readable, orange when the signal is
lost mid-transfer, red when the browser has stopped responding to keys, and blue when a file is
done. The status strip shows the percentage done, the chunk count and the current rate, and carries
a Quit button. The same percentage goes into the terminal title, so a minimised window still shows
progress. The sender page shows its own percentage in the label and the browser tab.

Both decoders are written from scratch for one job: an axis-aligned, unrotated, losslessly captured
screen render. There is no perspective correction, so a photograph of a screen will not decode. The
QR path finds the three finder patterns, measures the module pitch, reads the format and version
information, unmasks, de-interleaves the blocks and corrects them with Reed-Solomon.

## The colour raster

```
  quiet zone (white, 4 cells)
  +----------------------------------+
  | rim (grey, 2 cells)              |
  |  +----------------------------+  |
  |  | calibration row: 8 colours |  |
  |  | data cells, row major      |  |
  |  +----------------------------+  |
  +----------------------------------+
```

A cell is one of five colours: black, red, green, blue, white. Five is not a power of two, so two
bytes ride in seven cells as a base-5 number, 5^7 = 78,125 being enough room for 65,536. That is
2.29 bits a cell against the 2.32 the alphabet allows.

Five colours rather than eight because these are the ones nothing can get wrong. A cell is read as
whichever of the five the receiver measured it nearest to, never by matching a fixed shade. The five
references are taken from the calibration row on every frame, so a dimmed, tinted or washed out
display moves them exactly as far as it moves the cells and shade stops mattering.

Nearest to a measured colour, rather than a rule about which channel dominates, because a lossy
capture tints a black cell -- 0,20,0 and the like -- and every dominance test then calls that green.
On a real compressed screenshot that was 14 per cent of the cells, every one of them black.

Grey appears nowhere except the rim, which is how the receiver finds the grid: it projects the grey
pixels onto rows and columns, keeps the near-maximal lines, and measures the rim's thickness to
learn the cell size. Grid dimensions follow from the rim, so the receiver needs nothing told to it
in advance. Grey means half of white as this capture renders white, not a fixed 128, so a dimmed or
tinted display is still located.

More than one rectangle can pass for a rim, because anything grey elsewhere on the page -- a window
edge, a toolbar -- makes four bands of its own, and nothing in the picture says which is right. A
rim a few rows too far out reads every cell correctly and still fails, because the row count is what
decides where the parity bytes begin. So every candidate is tried, largest first, and whichever
decodes is the one that gets remembered.

The calibration row carries all five colours, so the decoder measures what the display actually
produced rather than assuming pure values.

This is what makes a reduced palette safe, which is the point of five colours. Black, red, green,
blue and white are entries in every palette that still counts as colour, so a browser or a remote
session rendering at 16, 8 or even 4 bits returns them exactly or near enough, dithered or not. The
rim is the part with no exact entry to land on -- the classic 16 colour palette has no 0x808080 at
all -- which is why grey is defined against the white in the picture rather than as a fixed value.
Below four bits nothing lies between black and white for the rim to be, and the receiver reports no
code rather than inventing a grid.

The cells hold a four-byte frame length, the frame, then Reed-Solomon parity of about eight per
cent, in blocks of at most 255 bytes. The parity is only touched when the frame's CRC fails, so a
clean capture pays nothing for it. Isolated misread cells are repaired; a torn capture fails the
CRC and the next capture succeeds.

Because seven cells carry two bytes, one misread cell spends two of a block's parity bytes, and a
block that runs out has usually just had a few borderline cells fall the wrong way. Those can be
picked out: on a compressed capture, 86 per cent of the bytes the parity had to fix belonged to the
most doubtful tenth of their block. So a block that will not correct is tried again with some of its
closest calls read the other way.

One flip rescues a block that is a single cell over its parity. A noisier one needs several flipped
together, and flipping every doubtful cell at once flips the innocent ones too, so the second look
searches combinations: every single flip among the 24 most doubtful groups, then every pair among
20, every triple among 16, every quadruple among 12, fewest flips first. A real capture that needed
three was found this way. Each attempt is one Reed-Solomon pass and Reed-Solomon checks its own
work, with the frame's CRC as the final gate. The search spends a fixed budget of passes per frame
-- about 80 ms at the very worst -- and is skipped altogether when more than six blocks fail, which
is a torn or covered frame, not noise; those are given up on in a millisecond and the next capture
is fine. A clean capture never takes a second look.

## Frame format

Every code carries a 23-byte header and a payload, big-endian throughout.

| Offset | Size | Field |
| --- | --- | --- |
| 0 | 2 | magic `GT` |
| 2 | 1 | high nibble protocol version 3, low nibble type: 0 data, 1 end |
| 3 | 4 | file id, CRC32 of the name and size |
| 7 | 4 | chunk index; for the end frame, the chunk count |
| 11 | 4 | number of data chunks |
| 15 | 4 | chunk size in bytes |
| 19 | 4 | CRC32 of the whole frame with this field zeroed |
| 23 | n | payload |

Every code carries its own chunk index and the total, which is how the receiver knows what it is
looking at and can accept chunks in any order.

The end frame's payload is the file size (8 bytes), its CRC32 (4), its SHA-256 (32), then the name
length (2) and the name. Both sides must agree on the protocol version, so an older receiver
rejects a newer sender's codes rather than misreading them.

## Speed

Each key press moves one chunk, so throughput is chunks per second times the chunk size. The cycle
is one key press, one browser repaint, one screen capture and one decode. Use `--stats` to see where
the time goes.

The chunk size is set by the window: a grid of `cols` by `rows` cells carries two bytes for every
seven cells, less about eight per cent parity and a 27-byte header. Enlarging the browser window is
the single most effective change, because it costs nothing per frame.

Measured on a 2560x1600 display at 150 per cent scaling, a 1200x900 window carried 877x590 cells,
136 KB per frame, and moved 5 MB in 2.9 seconds. Capture took 43 ms of each cycle and decoding
9 ms, so the screen grab is the limit. Java asks Windows for a scaled screenshot and throws the
scaled copy away, so a display at 100 per cent scaling captures noticeably faster than the same
pixels at 150 per cent.

Five colours carry a quarter less per frame than eight would, and cost almost no speed: the cycle
is dominated by the capture, and the smaller frame is quicker to build and to decode, so the
measured rate lands within a few per cent either way.

## Troubleshooting

- **Nothing happens when the transfer starts.** The browser does not have keyboard focus. Click the
  chunk field. The page shows a red banner whenever it is not focused.
- **The border never turns green.** The region misses part of the code's grey rim. Include the white
  margin on all four sides. Other grey things inside the region are tolerated: each rectangle that
  looks like a rim is tried until one decodes.
- **Every frame reports repairs.** The cells are being read marginally. Raise pixels per cell, or
  check that the browser is at 100 per cent zoom. Colour accuracy is not the issue: cells are read
  by which channel dominates, so any night-light, colour profile or brightness setting is fine.
- **It says the browser is not receiving keys, but it is -- just slowly.** It should not: the
  receiver paces itself to however long this client takes, and only reports keys as lost after
  several in a row go unanswered for far longer than the browser has ever taken. If you do see it on
  a browser that is merely slow, the status line's `browser N s a frame` figure says what pace it
  thinks it is keeping.
- **Keys are ignored no matter what.** A browser started as administrator will not accept synthetic
  keystrokes from a normal-privilege program. Run both at the same level.
- **"The chunk field is not taking the numbers typed into it."** The digits are not reaching the
  page. The transfer carries on by stepping with the arrow keys, which is slower but correct. It is
  usually a keyboard layout that needs a modifier for the number row; switching the layout to one
  with plain digits, or turning Num Lock on so the numeric keypad is used, fixes it.
- **The page says it must be served over HTTP.** Browsers block `fetch` on `file://` URLs. Serve the
  folder with `jwebserver`.
- **The checksum fails at the end.** The receiver restarts the file once by itself. If it fails
  again, the codes are being misread; raise pixels per module or the error correction level.

## Checking it works

`java desktop\GlassReceiver.java --selftest` runs offline: Reed-Solomon correction at and beyond its
limit, the format and version BCH codes, the block tables for all 160 QR version and level
combinations, 56 QR encode-render-decode round trips, and colour raster round trips from 64x40 up to
585x630 cells including damage the parity has to repair. It also checks the rules that pace the sender: a client taking 2.2 seconds a frame must never be
talked over nor called deaf, a client that has gone quiet over several keys must be, and a frame
that arrived with keys queued behind it must not be mistaken for a quick one.

Four cases are drawn from real captures that failed: a grey window edge below the code forming a
second, larger rim that the decoder has to work past; five cells landing just on the wrong side of a
colour boundary in one block, which is one more than its parity can carry; six, which no single flip
rescues and only a combination does; and a frame with every block wrecked, which has to be refused
at once rather than searched. It also re-renders a raster the way
a poor display would hand it back -- dimmed to 45 per cent, contrast squeezed into 40..200, channels gained
unevenly, noisy, and all of that at once -- and at 16, 8 and 4 bits of colour, dithered and not,
requiring every one to decode byte for byte. A 3 bit render has to be refused rather than misread.

`--decode-image` on a raster also reports whether the parity bytes agree with the data, which is how
an encoder in another language can be checked against this decoder. `--vector` prints reference
values for the same purpose.

No sample images are kept here; the self-test renders everything it needs. To check a capture of
your own, take one and read it back:

```
java desktop\GlassReceiver.java --region 20,20,1200,900 --capture-once shot.png
java desktop\GlassReceiver.java --decode-image shot.png
```

That prints what it found and the frame header; add `--verbose` to see how the grid was located, and
`--corrupt N` to damage the image first and watch the parity repair it. A capture that has been
through image compression -- pasted into a chat, say -- is worth keeping to hand, because no
synthetic image is that badly behaved. For a full check, send a file and compare
`Get-FileHash -Algorithm SHA256` on both sides.

## Licences

The QR encoder in `web/sender.html` and `web/qrcodegen-v1.8.0-es6.js` is Project Nayuki's QR Code
generator v1.8.0, MIT licensed, with its notice intact. Everything else in this repository is
original. QR Code is a registered trademark of Denso Wave Incorporated.
