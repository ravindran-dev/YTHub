# YTHub icon

The user supplied a collage containing a glossy red folded play/download mark and requested that mark without a black background.

The built-in imagegen tool extracted a single symbol on transparent alpha. The project asset is `public/ythub-icon.png`; `tauri icon` generated the Windows ICO and size variants in `src-tauri/icons`. The same PNG is used in the sidebar, About view, and browser favicon.

Prompt used:

> Use case: background-extraction. Edit target: provided YTHub logo collage. Extract only ONE red folded rounded triangular play-and-download symbol, matching the large red symbol in the upper-right black app tile. Preserve its glossy bright red folded ribbon shape, central white right-pointing play triangle and white downward download arrow. Remove ALL black/white tile backgrounds, text, other icons, backdrop, ground shadows, reflections and collage elements. One centered isolated symbol with crisp edges on genuinely transparent alpha background, tightly framed with small safe margin, square canvas. No new text, no black square, no white square, no circular badge. This will be the actual Windows app icon.
