# Modrinth Release Notes

AMCTimber normally builds a polished Modrinth changelog from the matching `CHANGELOG.md` section. The
automation adds the release title, compatibility panel, signed-build evidence, verification links, and
safe upgrade reminder automatically.

For a release that needs a richer player-facing story, add `docs/releases/x.y.z.md` in the tagged source.
Write only the release-specific body: do not repeat the `AMCTimber x.y.z` title, compatibility panel, or
verification section. Standard Keep a Changelog headings such as `### Added`, `### Fixed`, and
`### Security` are promoted and decorated consistently.

The override is reviewed and versioned with the release source. It never grants publication permissions
and cannot choose the uploaded JAR, project, loaders, or supported Minecraft range.
