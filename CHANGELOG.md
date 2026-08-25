<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Maven-dependency-overrides Changelog

## Unreleased

### Added
- The Override Overview toolbar has a **Filter by Status** button: check or uncheck any of
  the six statuses to show only matching rows. The filter stays applied across Refresh, and
  a note above the table says how many rows it's currently hiding.

## 0.0.6 - 2026-08-24

### Changed
- Updated the vendor contact email address in the plugin metadata.

## 0.0.5 - 2026-08-24

### Changed
- Reverted the "hide the results table when every override depends on an unresolved
  `${...}` property" behavior shipped in 0.0.4. In real large projects (internal BOMs
  imported at `${revision}`, modules Maven never imported) it hid the panel
  permanently rather than only before a first sync, which made the panel less useful
  than always showing its rows and letting incomplete `${...} → ?` entries speak for
  themselves. The Override Overview now always shows its table.

## 0.0.4 - 2026-08-20

### Added
- Overrides that no BOM in the module's chain manages are now listed in the Override Overview
  with a **Not managed by BOM** status, instead of being dropped from the results. This is the
  shape of the most common hand-written pin there is — an artifact pulled in transitively, and
  pinned precisely because no BOM governs it — so the overview was silently incomplete on
  exactly the entries most worth reviewing. No verdict is offered on whether such a pin is
  still needed: that depends on the resolved dependency tree, not on the BOM chain, and
  **Analyze Dependencies** on the row is the way to check. The editor inspection stays silent
  about them, like it already does for overrides above the BOM version.

### Changed
- The Override Overview no longer presents a table built entirely from unresolved `${...}`
  versions. Before Maven has resolved a project's properties, a property-declared override — or
  a BOM imported at a property version — becomes a `${foo.version} → ?` row that disappears again
  on the next refresh, so a whole page of confident-looking findings could be an artifact of the
  sync not having run. When every override found is in that state the panel now explains it and
  points at Refresh; when only some are, the table is still shown with a note saying how many
  rows are incomplete. The check looks at the rows themselves rather than at Maven's
  initialization flag, so a project that simply isn't a Maven import keeps its overview.
  Reverted in 0.0.5.

## 0.0.3 - 2026-08-14

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
