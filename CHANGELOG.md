# Spokify 0.3.4

- Disable R8 identifier obfuscation (`-dontobfuscate`) in optimized builds while retaining code and resource shrinking so static analyzers can inspect readable Kotlin class and method names.
- Rename vendored `third_party/LevyraExtractor` directory to `third_party/extractor` and update Gradle composite build and notice references.

# Spokify 0.3.3

- Rename top filter pill on Home screen from "Podcasts" to "Downloads" to directly reflect its navigation to the Offline Backup screen.

# Spokify 0.3.2

- Refine Mini Player swipe gestures: swiping right reliably advances to the next track in the queue (handling Repeat-One and end-of-queue wrapping without repeating the same song).
- Swiping left on the Mini Player navigates to the previous track directly without resetting to 0:00.
- Clean pointer input handling so tapping anywhere on the Mini Player opens the full player without gesture conflicts.
- Edge-to-edge flush progress indicator on Mini Player matching official Spotify.

# Spokify 0.3.1

- Fix homepage shortcuts grid layout: combine playlists and recent songs into full 2-column pairs to eliminate missing/blank shortcut tiles.
- Fix duplicate "Now Playing" playback indicators: when a collection (e.g. On Repeat, Liked Songs, or a playlist) is active, child song shortcuts no longer show redundant equalizer bars.
- Equalizer bars properly pause animation when playback is paused.

# Spokify 0.3.0

- Slide minimized mini player horizontally to change tracks (swipe right for next song, swipe left for previous song), with fluid track offset animations.
- Match official Spotify Queue UI: rounded bottom card tiles for Shuffle, Repeat, and Timer with dynamic active green states.
- Replicate Spotify Queue header with "Playing [Song]" format, sleek dark pill "Edit" button, and circular white Play/Pause control for current song.
- Replicate Spotify Queue Edit mode with individual selection checkboxes, crisp drag handles, and "Move up" / "Remove" pill actions.
- Replicate Spotify Sleep Timer bottom sheet format: 5 min, 10 min, 15 min, 30 min, 45 min, 1 hour, "End of track", dynamic "Sleep timer - X min left" title, and "Turn off timer".
- Clean up Now Playing screen: relocate Queue shortcut to bottom-right toolbar alongside Share, eliminate redundant top-bar Queue button and bottom timer text clutter.
- Replicate Spotify Your Library screen: "Sort by" bottom sheet (Recents, Recently added, Alphabetical, Creator) with green checkmarks, opposing arrows sort row, pinned pushpin on Liked Songs, and green title highlighting for playing playlist/album.
- Replicate Spotify Liked Songs & Collection screens: large bold headers, shuffle button, and 56dp giant green circular play button.
- Replicate Spotify Home screen: "All", "Music", "Podcasts" filter pills, 2x4 solid shortcuts grid with animated equalizer bars for active tracks.
- Read Spotify playlists beyond the 100-song public preview, with explicit warnings when a full import is unavailable.
- Match full artist credits, featured performers and film-title labels without confusing covers, alternate languages or different recording versions.
- Keep playlist order, repeated songs and unavailable source positions intact during import and review.

# Spokify 0.2.2

- Reliable Spotify playlist imports with review drafts and recover interrupted saves.
- System inset clearance in bottom chrome.
- Prioritize manual songs and refine playback queue controls.

# Spokify 0.2.1

- Keep install and manual update checks above release notes so they are easy to reach.
- Check once on the first app open each day, remember available updates, and show
  the yellow profile dot and Update Spokify menu entry.

## Spokify 0.2.0

- Update Spokify from Settings → App updates without connecting a USB cable.
- Check for a new version on the first app open each day, with a yellow profile
  dot and an Update Spokify entry in the profile menu when one is available.
- See the available version, release notes and download progress before installing.
- Check downloaded updates before opening Android's installer.
- Enjoy smoother scrolling and faster artwork display.
- Keep existing playlists, favorites, listening records and offline artwork.
