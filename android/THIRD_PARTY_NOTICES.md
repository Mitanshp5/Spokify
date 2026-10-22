# Third-party notices

## Playback transport adaptations

The ReelPlaybackResolver implementation and the SABR transport files under
app/src/main/java/com/mitansh/sidea/playback/sabr are adapted from the
user-supplied Levyra playback reference by LUC4N3X and contributors.
These adaptations retain the GPL-3.0-or-later license identifiers in their
source files. Renaming app-owned symbols does not change their provenance
or license. The GPL license text is provided in LICENSE.

## Vendored extractor

The source dependency in third_party/LevyraExtractor retains its original
identity, copyright headers, LICENSE and THIRD_PARTY_NOTICES.md. Consult
those files for the extractor's upstream lineage and dependency notices.

## Lyrics service

Lyrics are fetched from LRCLIB (https://lrclib.net), a community lyrics service.
The player attributes lyrics to LRCLIB. Loaded lyrics are cached locally for
offline viewing; availability and synchronization depend on the recording's
metadata and the lyrics contributed to that service. No lyrics are bundled
with the application.
