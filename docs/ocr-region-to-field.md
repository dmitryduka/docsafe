# Research: select a region on a photo → OCR a number → add as a key/value field

Goal: open a photo (in the existing full-screen viewer), let the user drag a box over a
number (e.g. a passport/ID number), OCR **on-device**, then reuse the existing key/value
dialog to store it. Purely local — no network, suitable for sensitive data.

## Recommended approach

**On-device OCR engine: ML Kit Text Recognition v2** — the standard, fully on-device option.
- Latin script (covers passport numbers, codes, most IDs incl. the OCR-B MRZ font):
  `com.google.mlkit:text-recognition:16.0.1` — **bundled** model (~4 MB added to the APK),
  no Google Play Services dependency, works offline immediately, no first-run download.
- Or `com.google.android.gms:play-services-mlkit-text-recognition` — **unbundled**: smaller
  APK, model delivered via Play Services on first use (needs Play Services, downloads once).
  Consistent with our document-scanner dependency.
- Non-Latin scripts (Chinese/Japanese/Korean/Devanagari) are separate model artifacts; add
  them only if needed. For "passport number" the Latin recognizer is sufficient.
- Everything runs locally; no image or text leaves the device.

Tesseract (tess-two/tesseract4android) is the alternative but is larger, slower, needs
trained-data files, and has worse accuracy on this task — not recommended over ML Kit.

## UX flow (fits the existing app)

1. In `ImageViewerScreen` add an "OCR / grab text" toggle in the top bar.
2. In OCR mode, overlay a draggable/resizable selection rectangle on the image (disable the
   pinch-zoom gesture while selecting).
3. On confirm, crop the **full-resolution** bitmap (we already decode it via
   `VaultViewModel.fullImage`) to the selected region, mapping the on-screen rectangle to
   bitmap pixels (account for `ContentScale.Fit` letterboxing + any zoom/pan transform).
4. Run the recognizer on the crop:
   `TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(InputImage.fromBitmap(crop, 0))`.
5. From `Text.text` (and `textBlocks`/`lines`), produce candidate values:
   - the raw recognized text, and
   - regex-extracted candidates, e.g. digit runs `\d{4,}` or alphanumerics `\b[A-Z0-9]{5,}\b`
     (passport numbers are often 1 letter + 6–8 digits), with whitespace stripped.
6. Show the **existing** `AddFieldDialog` pre-filled with the chosen candidate as the value;
   the key dropdown already defaults to "Number" (or Date/Code/custom).
7. On confirm, call the existing `VaultViewModel.addField(documentId, key, value)` — the viewer
   already knows `documentId`, so this is a direct reuse.

## Preferred UX: auto-detect field-like numbers as tappable boxes

The ideal flow you described — enter OCR mode and **every** number/code is already outlined
with a tappable box, tap one to copy it and open the key/value dialog — **is feasible** with
the same ML Kit recognizer, no extra ML. It works because Text Recognition v2 returns a full
geometric hierarchy, not just a flat string:

```
Text
 └─ TextBlock     (paragraph)   .boundingBox, .cornerPoints
     └─ Line       (line)        .boundingBox, .cornerPoints, .text
         └─ Element (word/token) .boundingBox, .cornerPoints, .text
```

So the implementation is:

1. On entering OCR mode, run the recognizer **once on the whole (full-resolution) image** —
   not on a crop.
2. Walk `textBlocks → lines → elements` and keep only the **field-like** tokens by regex on
   each element/line `.text`, e.g.:
   - pure digit runs `\b\d{3,}\b` (account numbers, amounts, dates without separators),
   - dates `\b\d{1,4}[./-]\d{1,2}[./-]\d{1,4}\b`,
   - alphanumeric codes `\b[A-Z0-9]{4,}\b` (IDs, serials, IBAN/passport-style),
   - optionally merge adjacent elements on the same line (e.g. grouped digit blocks).
   This filtering is what makes it select "a separate code or number" and **not** the whole
   body text — paragraphs of prose simply don't match, so they get no box.
3. Map each matched token's `.boundingBox` from bitmap pixels → on-screen coordinates (the same
   `ContentScale.Fit` + zoom/pan transform as the manual-box approach, just inverted) and draw a
   tappable highlight rectangle over each.
4. Tapping a box: the recognized text for that token is **already known** (no second OCR pass) →
   copy to clipboard **and** open the existing `AddFieldDialog` pre-filled with that value; the
   key dropdown still defaults to Number/Date/Code/custom.

This is strictly an extension of the manual flow below: same engine, same dialog, same
`addField`. The only added work vs. a single-crop OCR is the regex classifier + drawing N
overlay boxes and hit-testing taps.

**Practical caveats (why the manual box stays as a fallback):**
- The regex classifier is heuristic — it can miss an unusually-formatted field or box a number
  that isn't a "field" (e.g. a page number). Tuning the patterns is the main iteration cost.
- Dense documents can yield many boxes; we'd want to cap/scroll or only box high-confidence
  tokens to avoid clutter.
- Skew/glare can fragment a number across elements; line-level grouping mitigates but isn't
  perfect.

So ship **both**: auto-detected tappable boxes as the primary experience, and the
**drag-a-box / draw-a-region** selection (below) as the fallback for anything the auto-detector
misses — the user draws over the area and we OCR just that crop.

## Effort & risks

- **Effort:** ~1 focused implementation pass. The OCR call itself is trivial; the real work
  is the selection-rectangle overlay and the view→bitmap coordinate mapping (the part most
  likely to need iteration), plus a candidate-picker if a crop yields multiple strings.
- **Accuracy:** strong for clean printed digits; degrades with glare, skew, low contrast, or
  handwriting. Mitigations: let the user re-crop, edit the value before saving, and optionally
  upscale/grayscale the crop before OCR.
- **Rotation:** pass the correct rotation to `InputImage`; we already bake EXIF orientation
  into the decoded bitmap, so rotation = 0 is correct for our crops.
- **Dependency size:** bundled Latin model adds ~4 MB; unbundled adds ~0 but needs Play
  Services. Given we already use the Play-Services document scanner, the unbundled recognizer
  is the consistent choice.
- **MRZ note:** full passport MRZ parsing (the two/three machine-readable lines with check
  digits) is a deeper, separate feature; generic region OCR matches the stated ask and is far
  simpler.

## Status: implemented

This design is now shipped:
- **Dependency:** `com.google.android.gms:play-services-mlkit-text-recognition` (unbundled
  Latin recognizer), with `<meta-data android:name="com.google.mlkit.vision.DEPENDENCIES"
  android:value="ocr" />` so the model is fetched at install time. R8 keep/dontwarn rules added.
- **`app/.../ocr/OcrEngine.kt`** — `detectFields(bitmap)` walks block→line→element, classifies
  each token (Number / Date / Code via regex) and returns `OcrCandidate(text, box, kind)`;
  `recognizeRegion(bitmap, rect)` OCRs a crop for the manual fallback. Pure on-device.
- **Viewer (`ImageViewerScreen`)** — a "Extract text" toggle enters OCR mode (image shown
  fitted, no zoom). Auto-detected fields are outlined and tappable → tap copies the value and
  opens the prefilled add-field dialog. A second "Select an area" toggle switches to drag-a-box
  region OCR for anything auto-detect misses.
- **Batch mode (`BatchExtractScreen`)** — reachable from the folder overflow menu ("Extract
  data (batch)"). Steps through every image-bearing document under the folder subtree, OCRs each,
  and shows detected candidates with an inline key chip + Add button, plus Previous/Next paging.
- **Shared dialog** — `AddFieldDialog` was made reusable (prefillable key/value) so the viewer,
  batch mode, and document detail all funnel into the same `VaultViewModel.addField`.

## Verdict

Feasible and well-aligned with the app: on-device, private, and it plugs straight into the
existing full-screen viewer + key/value field flow. Recommend the **unbundled** ML Kit Latin
text recognizer, a selection-rectangle overlay in the viewer, and reuse of `AddFieldDialog` +
`addField`. Not implemented yet per request — this is the design.
