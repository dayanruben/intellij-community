# content-module-packer

Packs the `lib/` jars of a product's content modules, one jar per module, from already-built module and library jars.
It is the tool behind `content_module_jar` (`../../platform/build-scripts/bazel-rules/content_module_jar.bzl`) — 2 524
targets across the repository — which names it directly, as a private attribute.

Directly, because it lives here. The recipe used to be five attributes on the `jvm_library` itself, and the packer had
to be pushed in through a `--@rules_jvm//:content-module-packer` `label_flag` whose default in `rules_jvm` was a stub
that failed at execution time: `jvm_library` is a `rules_jvm` rule, and `rules_jvm` ships as a consumable archive that
may not name a label in a repository consuming it. Moving the packer into `@community//`, which both the community and
the ultimate tree can name, removed the flag, its stub, its `ContentModulePackerInfo` provider — a provider rather
than a plain label only because the two implementations had different action *shapes* — and its `.bazelrc` line.

## What is verified

Byte-identity with the Kotlin implementation it replaced (`@rules_jvm//content-module-packer`, deleted in the same
commit), because the distribution consumes these jars and a packer that drifts surfaces at class-load time in the IDE
and nowhere earlier:

- **192 of 192 jars byte-identical** when built as real Bazel actions over all 2 524 targets, compared against jars
  snapshotted from the Kotlin packer beforehand.
- **26 real recipes** taken from the live graph with `--verify-crc`, so every carried-over CRC was also proved to
  describe its data.
- **4 constructed recipes** covering what the real sample could not reach, because every library-merging recipe in it
  pointed at an `http_file` repo that was not materialised: library+module merge, the full library drop-filter,
  DEFLATED→inflate, first-source-wins duplicates, `keep-manifest`, and the Boot-Class-Path rewrite with its index
  asymmetry. Both packers run on the same recipe; output compared byte for byte.
- The `__index__` hashes against the **2 050 reference vectors** in `internal/xxh3`, which are the same C reference
  values `XxHash3Test.java` holds the platform's own implementation to.

Those were one-off comparisons against an implementation that no longer exists. Two gates stand, and they are what a
change to this tool has to pass:

- **`./build/dev-dist.cmd jars` — 486 byte-identical, 0 differing on 2026-09-26.** The standing comparison against `JarPackager`, and
  the only thing that still holds the two producers of these bytes to each other.
- **`bazel test @community//build/content-module-packer/internal/jarpack:jarpack_test`**, which runs in a second. Six
  frozen digests over the recipes the real sample cannot reach, plus the structural claims - normalised headers, STORED
  everywhere, no extra fields, the
  index pointer in the end record - asserted by `archive/zip`, an implementation that shares no code with this writer.
  Any change to the reader, the writer or the index fails here before it reaches a distribution.

## Performance

Bazel runs the packer as one process per action, with the disk cache on and the remote cache off. ADR 0019 in the
monorepo's `build/decisions/` states why, and `build/dev-dist-measurements.md` holds every run. Medians over 3 599 jars
on darwin arm64, 2026-09-26:

| configuration | wall clock |
| --- | --- |
| one process per action, no cache | 25.1 s |
| every jar a disk-cache hit | 4.9 s |
| multiplex worker, no cache (before ADR 0019) | 10.8 s |

The packing itself is a small part of that. A stub that only creates empty outputs takes 23.7 s in the same build. So
the process starts cost the time, and the language of the packer does not change them.

Mapping the source jars, instead of reading them entry by entry, is for CPU and not for wall clock. `pread` was 61.8 %
of the packer's 5.91 s of CPU over the whole tranche, and the merge is now 2.44 s of CPU.

### How to measure it again

Five things about this that are easy to get wrong, and cost an afternoon each:

- **A comment-only edit does not re-key anything.** A Go build is reproducible, so the binary is byte-identical and all
  packing actions are action-cache hits. Re-keying needs a byte in the binary to move. A scratch `native_binary` at
  `_packer` with a new `-buildid` is the cheapest way.
- **`--modify_execution_info` re-keys every action of a mnemonic** (execution info is in the action key). *Changing* it
  also discards the analysis cache. Hold the flag constant within a comparison, or the difference measured is the
  analysis.
- **It does not change the *cache* key.** A run with a new execution-info key and an unchanged binary is served from the
  disk cache, packs nothing, and looks fast. Point `--disk_cache` at an empty directory to force a genuine miss.
- **Keep the remote cache configured in every arm.** Switching it off re-runs every action whose outputs are remote
  only, which is most compilation.
- **The one-shot mode is the profiler.** It accepts thousands of `output=` groups in one file, so the whole tranche
  profiles in one process with no Bazel in the loop:

      bazel aquery --output=text --include_param_files \
        'mnemonic("PackContentModuleJar", //... + @community//...)'

  gives every recipe as its expanded command line; concatenate them into one flag file, rewrite the `output=` paths so
  a run does not overwrite `bazel-out`, drop the groups whose sources are unmaterialised `http_file` repos, then run it
  from the exec root with `--cpuprofile=`.

### Spans

A `trace-file=` line in the flag file, or `--trace-file=<path>` on the command line, writes what this run spent its time
on as Jaeger JSON - the format
[`JaegerJsonSpanExporter`](../../platform/diagnostic/telemetry.exporters/src/JaegerJsonSpanExporter.kt) writes on the JVM
side, so a packing action's spans land in the same trace as the rest of the build's. A run writes a `pack content
modules` root span and one `pack jar` child per output, tagged with the jar's name, how many sources it merged, how many
bytes it wrote and how many duplicate entries it reported.

Four things about it that are not obvious:

- **One file per action**, and the destination arrives *inside the flag file* as a `trace-file=` line, not as a
  `--trace-file=` argument, because a packing action passes only `--flagfile=`. `--trace-file=` stays for one-shot and
  whole-tranche runs, which are command lines rather than actions, and it wins where both are present. Either way the
  path is resolved against the working directory, which for a build is the exec root.
- **The recipe is parsed before the tracer starts**, since in a build the destination is in the recipe. So the parse sits
  outside the root span, and a recipe that does not parse writes no span file at all.
- **The ids are random, and deliberately so.** Every span file in a build is written by a different process, and the
  merge that puts them in one trace has nothing but the ids to tell two actions' spans apart. Each file therefore also
  carries its *own* trace id, and a merge across producers has to rewrite them onto one trace.
- **It is a pure side output.** Nothing reads it during the build, the packing action is `+no-cache`, and a run without
  a destination behaves byte for byte as it did before - which is what `internal/jarpack`'s digests and
  `./build/dev-dist.cmd jars` check.
