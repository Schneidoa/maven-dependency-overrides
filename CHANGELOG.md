<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Maven-dependency-overrides Changelog

## Unreleased

### Changed
- The Add Override dialog opens wider so a full groupId and the "currently managed at …"
  hint line are readable without resizing, and it now remembers a width you set yourself.

### Added
- BOM and parent POMs missing from the local Maven repository are now downloaded from the
  remote repositories configured in `settings.xml` — mirrors, proxies and credentials
  included — so the Override Overview, the Add/Edit dialogs and the BOM chain view can give
  a verdict where they previously reported "inconclusive". The editor inspection stays
  offline and unchanged.
- Maven's "Work offline" setting is honored: nothing is downloaded, and the Override
  Overview says so when it has inconclusive rows.

### Fixed
- A parent POM missing from the local repository silently ended the parent-chain walk,
  which could drop an override from the report entirely or report it as unmanaged. Such a
  chain is now reported as inconclusive.

## 0.0.2 - 2026-08-13

### Changed

- Compatibility moved to IntelliJ IDEA 2026.2 (build `262.*`); 2025.3 is no
  longer supported. The plugin is built and verified against 2026.2.1.
- Versions written as a property (`<version>${widget.version}</version>`, or a
  BOM imported at one) are no longer resolved before the IDE's first Maven sync
  finishes: IDEA 2026.2 makes `MavenPropertyResolver` a no-op until
  `MavenProjectsManager` is initialized. Such an override is reported as "not
  comparable", and a BOM imported at a property version as unreadable, until
  the sync completes — never as safe to remove.

## 0.0.1 - 2026-08-12

First release, so this describes the plugin as a whole rather than changes
against a version anyone has seen.

### Added

- Editor inspection: `<dependencyManagement>` version overrides are flagged as
  a weak warning on the `<version>` tag in two cases — the imported BOM chain
  now manages the dependency at exactly the pinned version (the pin is
  redundant), or it manages it at a higher version (the pin is holding the
  dependency back). Quick fixes remove the override or suppress the warning
  with a marker comment. A pin that still raises the version above the BOM is
  left alone; so is one whose version can't be compared, such as an unresolved
  property or a version range.
- Maven Overrides tool window: every override across all `pom.xml` files in the
  project in one table — module, dependency, declared versus managed version,
  the BOM that manages it, and a status icon with the full explanation on
  hover. Includes the overrides the editor stays quiet about. Double-click a
  row to jump to the exact `<version>` tag.
- BOM provenance: the full parent-chain path down to the BOM that actually
  manages the version, e.g. `spring-boot-starter-parent →
  spring-boot-dependencies → com.fasterxml.jackson:jackson-bom:2.21.2`,
  instead of the bare BOM name — which is usually something nobody wrote by
  hand.
- Show BOM Chain: a dialog listing every BOM consulted for a row, in the
  precedence order Maven itself applies — which one wins, which don't manage
  the dependency, and which couldn't be read from the local repository. This is
  what makes an inconclusive verdict inspectable rather than merely asserted.
- Analyze Dependencies: hands a row's coordinate to IntelliJ's Maven Dependency
  Analyzer to see what pulls the dependency in. Disabled until the owning
  module has a completed Maven sync, since the analyzer has nothing to show
  before then.
- Add, edit, and remove overrides from the tool window without opening the POM.
  The Add dialog offers Group ID and Artifact ID fields backed by what the
  target module's BOM chain actually manages — type to filter or browse the
  full list; choosing a group narrows the artifacts, and an artifactId owned by
  exactly one group fills the group in. The version field prefills from what
  the BOM manages, with a live note on what the version you type would mean:
  redundant, above the BOM, below it, or not comparable.

### Notes

- Detection is local-only: it reads BOM POMs from the local Maven repository
  and never accesses the network. When a BOM in the chain isn't present there,
  the override is reported as inconclusive and no removal fix is offered — a
  wrong "safe to remove" suggestion could silently reintroduce a patched CVE.
