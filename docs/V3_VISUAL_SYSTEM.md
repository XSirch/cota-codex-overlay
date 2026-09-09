# Cota Codex — Visual System v3

## Design read

Android system utility for a technical user who checks quota repeatedly throughout the day. The interface should feel precise, quiet, native and dependable — closer to a system monitor / developer tool than to a SaaS dashboard or AI product landing page.

### Personality axes

- Density: medium-high
- Warmth: cool-neutral
- Formality: low-medium
- Energy: low
- Ornament: almost none
- Contrast: high where the number matters, quiet everywhere else

## Visual thesis

**Native technical utility.** One accent, neutral surfaces, compact hierarchy, tabular/monospace numerals for quota and reset times, system typography elsewhere. No gradients in the app UI, no glassmorphism, no decorative blobs, no marketing-style hero, no repeated feature cards.

The collapsed overlay bubble remains the product's recognizable mark because it is already working well in use. It is treated as an exception to the no-gradient rule because it is a tiny persistent locator, not decorative page chrome.

## Information architecture

### Main app

1. App bar
   - `Cota Codex`
   - Optional refresh icon only when authenticated
   - No logo lockup or subtitle

2. Usage section
   - Primary quota is the only emphasized surface
   - Remaining % is the dominant datum
   - Window and reset are secondary but visible without interaction
   - Thin progress indicator, not a radial gauge

3. Other windows
   - Plain rows separated by dividers
   - No card-per-window treatment
   - Label, remaining %, reset time

4. Overlay
   - One Android-style settings row with a Switch
   - Permission is requested only when the user turns it on
   - Status text must distinguish permission from an actually running overlay

5. Account
   - Current plan
   - Sign out as a quiet destructive text action

### Signed-out state

- One concise explanation of what the app does
- Three plain capability rows, not a marketing feature grid
- One primary action: `Entrar com ChatGPT`
- Login/security explanation stays factual and short

### Browser-login state

- No oversized illustration
- Small progress indicator
- Clear statement that authentication is happening in the browser
- No device-code language

## Overlay — collapsed

- 68dp interaction window
- Existing Codex bubble visual is preserved
- No elevation/shadow layer behind the bubble
- Transparent WindowManager root with clipping so there is no square translucent background
- Press feedback: slight opacity change
- Drag remains available and snaps to the closest horizontal edge

## Overlay — expanded

Width: 324–328dp.

Structure:

1. Header
   - `Codex`
   - plan/status as small metadata
   - refresh and collapse controls with >=48dp hit targets

2. Primary quota
   - large remaining percentage
   - window name
   - reset countdown as the second strongest datum
   - exact reset time below it
   - 4dp progress bar

3. Secondary windows
   - flat rows with dividers
   - no nested rounded cards
   - max four visible rows to keep the overlay glanceable

4. Footer
   - update status / secure DNS indicator
   - `Abrir app`

## Tokens

### Shape

- Main surfaces: 10–12dp radius
- Buttons: 8–10dp radius
- Expanded overlay: 16dp radius
- No large 20–24dp card radii
- Pills only for tiny semantic status, if needed

### Spacing

Base scale: 4dp

- 4: micro gap
- 8: inline gap
- 12: row gap / control padding
- 16: section inner padding
- 20: screen horizontal padding
- 24: section separation
- 32: major separation

### Type

Use Android system typography for all interface copy.

- Screen title: 24–28sp, semibold
- Section label: 11sp, medium, increased tracking
- Primary quota: 42–46sp monospace/bold
- Reset countdown: 18–20sp monospace/semibold
- Row title: 14sp medium
- Metadata: 11–12sp regular

Monospace is reserved for values that users compare: percentage, duration and exact reset time.

### Color

Follow system light/dark appearance with a stable custom neutral palette rather than wallpaper-derived dynamic color.

One brand/interactive accent: muted teal/green.

Semantic quota colors:
- healthy: muted green
- attention: amber
- low: restrained red

No purple/indigo AI palette. No gradients in screen surfaces.

### Elevation

- Main app: effectively flat
- Expanded overlay: outline + contrast, not large shadow
- Collapsed overlay: zero elevation to eliminate the translucent-square artifact

## Interaction

- 48dp minimum touch targets on Android
- Material ripple / pressed feedback on app controls
- Collapsed bubble gets explicit opacity feedback on press
- System back behavior remains native
- Overlay permission is requested contextually, only when enabling the feature
- Avoid continuous polling; refresh on open/tap and explicit refresh

## Accessibility

- Respect system font scaling
- Material semantics/content descriptions for icon-only actions
- AA contrast target
- Do not encode quota state by color alone; always show numeric % and text
- Do not rely on hover or gesture-only discovery

## Anti-slop rejection list for this product

Reject by default:

- gradient app headers
- glass cards
- giant centered marketing headline inside an authenticated utility
- radial quota gauge when a number + progress line communicates faster
- card-per-row architecture
- repeated icon-in-colored-square feature blocks
- 20dp+ radii everywhere
- fake `AO VIVO` badges
- ornamental animation
- copy such as `premium`, `seamless`, `powerful`, `smart`

Every UI element should answer one of three questions: **quanto resta, quando renova, ou como controlo o overlay?** If it answers none, remove it.
