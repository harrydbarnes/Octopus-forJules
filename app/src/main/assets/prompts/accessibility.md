Apply comprehensive accessibility improvements to this codebase, targeting WCAG 2.2 Level AA conformance as a minimum. Go beyond the minimum where it meaningfully improves usability. Do not claim the output is "fully accessible" -- manual review will still be required.
Work through the following areas:
1. Structure and semantics
Use landmark elements (header, nav, main, footer) correctly. Ensure headings introduce new sections without skipping levels, and that there is one h1 per page representing the page topic. Set descriptive <title> elements in the format "Unique page - section - site".
2. Keyboard and focus
Ensure all interactive elements are keyboard operable with a predictable tab order that follows reading order. Focus must always be visible. Hidden content must not be focusable. Static content must not be tabbable (use tabindex="-1" only when programmatic focus is required). Provide a skip link as the first focusable element in the page.
3. Controls and labels
Every interactive element must have a visible label that does not disappear during input. The accessible name of each element must include the visible label text. Where multiple controls share the same visible label (e.g., multiple "Remove" buttons), use aria-label to add distinguishing context while preserving the visible label text.
4. Forms
Every form control must have a programmatic label, preferably via <label for="...">. Associate help text using aria-describedby. Mark required fields visually and with aria-required="true". On invalid submission, use aria-invalid="true", associate error messages via aria-describedby, and move focus to the first invalid control. Do not use disabled submit buttons as the sole mechanism to prevent submission.
5. Component library usage
If the project uses a UI component library, use its existing patterns rather than recreating them. Find existing usages in the project and follow the same approach. Verify the resulting components still have correct accessible name, role, value, keyboard behaviour, focus management, and visible labels.
6. ARIA usage
Prefer native HTML elements and attributes over ARIA. Only use ARIA when native semantics are insufficient. Do not add ARIA to native elements when their built-in semantics already work correctly.
7. Colour and contrast
Text must meet 4.5:1 contrast (3:1 for large text, defined as 24px regular or 18.66px bold). Focus indicators and key control boundaries must meet 3:1 against adjacent colours. Do not rely on colour alone to convey information -- pair it with text or icons. Use project design tokens (CSS variables) for all colours; do not introduce arbitrary hex values. Ensure all interactive states (default, hover, active, focus, visited, disabled) meet contrast requirements. Avoid alpha/opacity on text and key UI affordances.
8. Forced colours / high contrast mode
Do not override OS accessibility settings. The UI must adapt to Forced Colors mode automatically. Use currentColor for SVG fills and strokes. Only use @media (forced-colors: active) where system defaults are insufficient, and use system colour keywords (e.g., ButtonText, Canvas) within it. Do not use forced-color-adjust: none unless absolutely necessary and justified.
9. Reflow
The layout must support reading multi-line text within a 320px viewport without horizontal scrolling. Use responsive flex or grid layouts with fluid sizing. Avoid fixed widths, overflow: hidden that causes content loss, and absolute positioning that obscures content at small sizes. Apply min-width: 0 on flex/grid children where needed. Handle long strings with overflow-wrap: anywhere. All interactive elements must remain visible and operable at 320px. Components that genuinely require two-dimensional layout (tables, maps, charts) may scroll at the component level only.
10. Graphics and images
Informative images must have meaningful alternatives (alt on img; role="img" with aria-label or aria-labelledby on SVG). Decorative images must be hidden from assistive technology (alt="" on img; aria-hidden="true" on others).
11. Tables and grids
Use <table> with <th> elements for static tabular data. Use grid roles only for genuinely interactive or dynamic experiences, and ensure cells are nested in rows so header and cell relationships are determinable.
12. Navigation
Use <nav> with lists and links for site navigation. Do not use role="menu" or role="menubar" for navigation. For expandable navigation, use button elements with aria-expanded to indicate state.
Before finishing, verify the following for every modified file:

Landmarks, headings, and a single h1 for the page topic are present
All interactive elements are keyboard operable with visible, predictable focus
No keyboard traps; skip link is present and functional
Visible labels exist and are included in accessible names
Forms have labels, required indicators, and correct error handling
Contrast meets 4.5:1 / 3:1 thresholds; colour is not the only information cue
Forced Colors mode does not break the UI
Content reflows at 320px without two-dimensional scrolling or content loss
Informative graphics have alternatives; decorative graphics are hidden
Tables use <th>; grids (where used) are correctly structured
