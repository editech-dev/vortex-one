# Implementation Plan - Vortex TV Rebrand

## Goal Description
Transform the generic "Media Service" application into **Vortex TV**, a premium virtual app manager for Android TV. This involves a complete rebranding (Name, Logo) and a visual overhaul to match modern Android TV standards (Dark theme, Focus states, Smooth aesthetics).

## Analysis of Current State
- **Current Name**: "Media Service" (Misleading, sounds like a background process).
- **Current Identity**: "OpenContainer-TV" (Descriptive but dry).
- **Current UI**: Basic logic present, but needs visual polish for TV experience.
- **Goal**: Create a consumer-facing, polished product identity.

## Proposed Changes

### 1. Identity & Naming
- **New Name**: **Vortex TV** (Implies the container/virtualization aspect in a dynamic way).
- **Rationale**: "Vortex" suggests a centralized hub where things spin up. It's short, memorable, and fits the "BlackBox" underlying technology.

### 2. Logo Design
- **Concept**: A stylized 'V' or Box combined with a screen element.
- **Style**: Cyberpunk/Neon, Dark gradients.
- **Deliverables**: Launcher Icon, TV Banner.

### 3. UI/UX Improvements

#### Colors & Theme
- **Primary**: Deep Navy/slate (`#0F172A`)
- **Accent**: Electric Blue / Neon Purple (`#6366F1`)
- **Text**: High contrast white/grey.

#### Components
**App Card (`item_virtual_app.xml`)**:
- Switch to `MaterialCardView`.
- Add `stateListAnimator` for focus scaling (zoom on selection).
- Add border/stroke that glows when focused.

**Dashboard (`activity_main.xml`)**:
- Add the new Logo in the header.
- Use the new gradient background.
- Adjust grid capability.

### 4. File Changes

#### [MODIFY] [strings.xml](file:///home/edison/AndroidStudioProjects/MediaService/app/src/main/res/values/strings.xml)
- Change `app_name` to "Vortex TV".

#### [MODIFY] [settings.gradle.kts](file:///home/edison/AndroidStudioProjects/MediaService/settings.gradle.kts)
- Change `rootProject.name` to "VortexTV".

#### [MODIFY] [colors.xml](file:///home/edison/AndroidStudioProjects/MediaService/app/src/main/res/values/colors.xml)
- Define new palette.

#### [MODIFY] [themes.xml](file:///home/edison/AndroidStudioProjects/MediaService/app/src/main/res/values/themes.xml)
- Apply new colors to app theme.

## Verification Plan
### Automated Tests
- Run `./gradlew assembleDebug` to ensure resources are linked correctly.

### Manual Verification
- Review code changes to ensure all "Media Service" user-facing strings are gone.
- Check resource headers.
