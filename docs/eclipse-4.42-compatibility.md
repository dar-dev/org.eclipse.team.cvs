# Eclipse 4.42 compatibility

Eclipse Team Core 3.10.1200 no longer contains
`org.eclipse.team.internal.core.subscribers.DescendantResourceVariantByteStore`.
The old CVS dependency caused CVS Core activation to fail, followed by errors
loading CVS UI decorators and resource listeners.

The installed platform also lacks the Team stream helpers and several Team UI
implementation classes used by CVS. Fixing just the first missing superclass
would leave networking, synchronization, checkout, and history actions broken.

## Implementation

The descendant variant cache is now implemented directly in
`org.eclipse.team.internal.ccvs.core.syncinfo.CVSDescendantResourceVariantByteStore`
on the public `ResourceVariantByteStore` and `PersistantResourceVariantByteStore`
APIs. The copied `DescendantResourceVariantByteStore` superclass has been removed.
CVS still needs its own cache policy: public Team APIs do not supply CVS revision
ordering, base fallback, or the distinction between an unfetched remote and a
known deletion. The implementation retains those semantics and only owns the
remote store's lifecycle.

This is the first public-API refactoring step, not a complete migration. The
following former implementations remain under CVS-owned packages:
- `org.eclipse.team.internal.ccvs.core.streams`: line-ending conversion,
  polling, timeout, progress-monitor, and size-constrained streams.
- `org.eclipse.team.internal.ccvs.ui.compat`: batching change-set manager,
  content and regex diff filters, scoped subscriber participant, action wrapper,
  scope-building operation, and project/working-set dialogs.

The originals come from this repository's history, immediately before commit
`1f9b1461e` (`delete non-cvs bundles`). Their EPL-2.0 headers and IBM attribution
are preserved. Stream and UI message strings are now packaged with CVS rather
than depending on removed Team message fields. Stream implementations retain their original algorithms.

The stream package is exported for CVS SSH2, and the UI compatibility package
is exported only for the CVS tests. Existing, still-present Team internals remain
in use; this is not a complete migration to public Eclipse APIs.

## Verify against an installed Eclipse (Windows)

From the repository root, with a JDK on PATH (or JAVA_HOME set):

```powershell
.\tests\verify-compatibility.ps1 -EclipseHome C:\java-latest-released\eclipse
```

Optional: pass `-JavaHome 'C:\path\to\jdk'`.
The Eclipse installation must include JUnit 3 or 4, normally available with PDE.

The script uses the exact bundle locations in the installation's `bundles.info`
(including a shared p2 pool), excludes the installed CVS bundles, compiles CVS
Core, SSH2, and UI for Java 17, and runs `CompatibilityTests`. Output is written
to a unique temporary directory; the installation and workspace are untouched.

Validated against Eclipse build `4.42.0.20261008-0639`, Team Core
`3.10.1200.v20260901-0703`, and JDK 27: all three bundles compile and all 15
standalone regression tests pass. The tests cover cache fallback/deletion,
member merging, cache delegation, line endings, protocol size boundaries,
polling retries/timeouts, and stream byte transfer. Cache tests also exercise
actual CVS revision and branch selection, equal-revision metadata differences,
clearing a known deletion with base bytes, and member deduplication.

## Build an installable distribution

```powershell
.\tests\build-test-distribution.ps1 -EclipseHome C:\java-latest-released\eclipse
```

This reruns verification, packages the four CVS plug-ins and feature using their
PDE binary resource declarations, and publishes an unsigned p2 repository using
Eclipse's FeaturesAndBundlesPublisher in an isolated configuration. The output
is `target/eclipse-cvs-4.42-<timestamp>.zip`, with source code included.
Source versions are unchanged; the build replaces `.qualifier` only in staged
manifests and pins the feature to the resulting plug-in versions.

Install through **Help > Install New Software > Add > Archive**. If no feature
is visible, uncheck **Group items by category**. Confirm the unsigned content
prompt and restart Eclipse. Neither script installs or updates your Eclipse.

The verification script is a source compatibility check, not a PDE/p2 release
build or an OSGi activation test. The distribution builder is intended for local
testing rather than a signed release. Build/export the plug-ins or CVS feature using the usual
PDE/Tycho workflow. Before publishing an update site, test the rebuilt feature
in a separate Eclipse installation/workspace: startup and decorators, repository
connection (including SSH2), checkout, update/commit, synchronization and merge,
and history/regex filters. Live CVS integration has not been exercised by the
standalone tests.
